package com.quatrang.volumemixer.engine

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.quatrang.volumemixer.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** One app that currently owns audio players (or has a saved volume setting). */
data class AppAudio(
    val packageName: String,
    val label: String,
    val playing: Boolean,
    val active: Boolean,
    val playerCount: Int,
    val volume: Int,
    val muted: Boolean
)

/**
 * The core of the app. Runs on its own background thread while [MixerService] is alive:
 * every ~0.8 s it reads the list of audio players from the system and applies each app's
 * chosen volume to all of that app's players (including brand new ones, e.g. when TikTok
 * swipes to the next live stream or YouTube starts the next video).
 */
object MixerEngine {

    private const val TAG = "MixerEngine"
    private const val TICK_MS = 800L
    private const val FULL_REAPPLY_MS = 4000L
    private const val APPLY_DEBOUNCE_MS = 25L

    enum class Status { STOPPED, RUNNING, SHIZUKU_UNAVAILABLE, NOT_PRIVILEGED, ERROR }

    data class State(
        val status: Status = Status.STOPPED,
        val apps: List<AppAudio> = emptyList(),
        val message: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var appContext: Context
    lateinit var prefs: VolumePrefs
        private set

    @Volatile
    private var running = false

    /** Single long-lived worker thread: every map below is only touched on it, so start/stop can't race. */
    private val handler: Handler by lazy {
        val t = HandlerThread("VolumeMixerEngine", Process.THREAD_PRIORITY_BACKGROUND)
        t.start()
        Handler(t.looper)
    }

    // ---- accessed only on the engine thread ----
    private val applied = HashMap<Int, Float>()          // piid -> multiplier we applied
    private val proxies = HashMap<Int, Any>()            // piid -> PlayerProxy
    private val piidsByPkg = HashMap<String, List<Int>>()
    private val identityByUid = HashMap<Int, Pair<String, String>>() // uid -> (package, label)
    private val labelByPkg = ConcurrentHashMap<String, String>()
    private var lastFullReapply = 0L
    // --------------------------------------------

    private val pendingPkgs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            safeTick()
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    private val applyPendingRunnable = Runnable { applyPending() }

    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = VolumePrefs(appContext)
    }

    val isRunning: Boolean get() = running

    @Synchronized
    fun start() {
        if (running) return
        running = true
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(tickRunnable)
        handler.removeCallbacks(applyPendingRunnable)
        handler.post {
            try {
                restoreAll()
            } catch (e: Throwable) {
                Log.w(TAG, "restoreAll failed", e)
            }
        }
        _state.value = State(Status.STOPPED)
    }

    /** Re-reads players immediately (e.g. right after Shizuku permission was granted). */
    fun refreshNow() {
        if (!running) return
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    // ------------------------------------------------------------------ user actions

    fun setVolume(pkg: String, volume: Int) {
        val s = prefs.get(pkg).copy(volume = volume.coerceIn(0, 100), muted = false)
        commit(pkg, s)
    }

    fun toggleMute(pkg: String) {
        val cur = prefs.get(pkg)
        // Un-muting an app whose slider is at 0 brings it back to a usable level.
        val s = if (cur.muted) cur.copy(muted = false, volume = if (cur.volume == 0) 50 else cur.volume)
        else cur.copy(muted = true)
        commit(pkg, s)
    }

    fun reset(pkg: String) = commit(pkg, VolumePrefs.Setting())

    fun resetAll() {
        val pkgs = prefs.savedPackages()
        prefs.clearAll()
        _state.update { st -> st.copy(apps = st.apps.map { it.copy(volume = 100, muted = false) }) }
        pendingPkgs.addAll(pkgs)
        scheduleApply()
    }

    private fun commit(pkg: String, s: VolumePrefs.Setting) {
        prefs.set(pkg, s)
        _state.update { st ->
            st.copy(apps = st.apps.map {
                if (it.packageName == pkg) it.copy(volume = s.volume, muted = s.muted) else it
            })
        }
        pendingPkgs.add(pkg)
        scheduleApply()
    }

    private fun scheduleApply() {
        if (!running) return
        handler.removeCallbacks(applyPendingRunnable)
        handler.postDelayed(applyPendingRunnable, APPLY_DEBOUNCE_MS)
    }

    // ------------------------------------------------------------------ engine thread

    private fun applyPending() {
        val pkgs = pendingPkgs.toList()
        pendingPkgs.removeAll(pkgs.toSet())
        for (pkg in pkgs) {
            val gain = prefs.get(pkg).gain()
            piidsByPkg[pkg]?.forEach { piid ->
                proxies[piid]?.let { proxy -> applyTo(piid, proxy, gain, force = true) }
            }
        }
    }

    private fun applyTo(piid: Int, proxy: Any, gain: Float, force: Boolean) {
        if (gain >= 0.999f) {
            // Back to normal: only touch players we changed before.
            if (applied.containsKey(piid)) {
                HiddenAudio.setVolume(proxy, 1f)
                applied.remove(piid)
            }
            return
        }
        val prev = applied[piid]
        if (force || prev == null || abs(prev - gain) > 0.0005f) {
            if (HiddenAudio.setVolume(proxy, gain)) applied[piid] = gain
        }
    }

    private fun restoreAll() {
        for (piid in applied.keys.toList()) {
            proxies[piid]?.let { HiddenAudio.setVolume(it, 1f) }
        }
        applied.clear()
        proxies.clear()
        piidsByPkg.clear()
    }

    private fun safeTick() {
        try {
            tick()
        } catch (t: Throwable) {
            Log.w(TAG, "tick failed", t)
            publish(State(Status.ERROR, savedOnlyApps(emptySet()), t.message ?: t.javaClass.simpleName))
        }
    }

    private fun tick() {
        if (!ShizukuState.isReady()) {
            applied.clear()
            proxies.clear()
            piidsByPkg.clear()
            HiddenAudio.reset()
            publish(State(Status.SHIZUKU_UNAVAILABLE, savedOnlyApps(emptySet())))
            return
        }

        val players = HiddenAudio.readPlayers()

        // Without MODIFY_AUDIO_ROUTING the system hides UIDs (-1) and player binders.
        if (players.isNotEmpty() && players.all { it.uid == HiddenAudio.UID_INVALID }) {
            publish(State(Status.NOT_PRIVILEGED, savedOnlyApps(emptySet())))
            return
        }

        val now = SystemClock.elapsedRealtime()
        val forceAll = now - lastFullReapply >= FULL_REAPPLY_MS
        if (forceAll) lastFullReapply = now

        val myUid = Process.myUid()
        val livePiids = HashSet<Int>()
        val newPiidsByPkg = HashMap<String, MutableList<Int>>()
        val apps = ArrayList<AppAudio>()

        val byUid = players
            .filter { it.uid != myUid && it.uid % 100_000 >= Process.FIRST_APPLICATION_UID }
            .groupBy { it.uid }

        for ((uid, list) in byUid) {
            val (pkg, label) = identify(uid)
            val setting = prefs.get(pkg)
            val gain = setting.gain()
            val piids = newPiidsByPkg.getOrPut(pkg) { ArrayList() }
            for (p in list) {
                livePiids += p.piid
                piids += p.piid
                val proxy = p.proxy ?: continue
                proxies[p.piid] = proxy
                applyTo(p.piid, proxy, gain, force = forceAll)
            }
            val existing = apps.indexOfFirst { it.packageName == pkg }
            val playing = list.any { it.state == HiddenAudio.PLAYER_STATE_STARTED }
            if (existing >= 0) {
                val e = apps[existing]
                apps[existing] = e.copy(playing = e.playing || playing, playerCount = e.playerCount + list.size)
            } else {
                apps += AppAudio(pkg, label, playing, true, list.size, setting.volume, setting.muted)
            }
        }

        applied.keys.retainAll(livePiids)
        proxies.keys.retainAll(livePiids)
        piidsByPkg.clear()
        piidsByPkg.putAll(newPiidsByPkg)

        val activePkgs = apps.map { it.packageName }.toSet()
        apps += savedOnlyApps(activePkgs)

        val sorted = apps.sortedWith(
            compareByDescending<AppAudio> { it.playing }
                .thenByDescending { it.active }
                .thenBy { it.label.lowercase() }
        )
        publish(State(Status.RUNNING, sorted))
    }

    private fun publish(s: State) {
        if (!running) return // stopped meanwhile
        _state.value = s
    }

    /** Apps the user customised that are not playing anything right now. */
    private fun savedOnlyApps(exclude: Set<String>): List<AppAudio> =
        prefs.savedPackages()
            .filter { it !in exclude }
            .map { pkg ->
                val s = prefs.get(pkg)
                AppAudio(pkg, labelFor(pkg), false, false, 0, s.volume, s.muted)
            }
            .sortedBy { it.label.lowercase() }

    private fun identify(uid: Int): Pair<String, String> {
        identityByUid[uid]?.let { return it }
        val pm = appContext.packageManager
        val pkgs = try {
            pm.getPackagesForUid(uid)
        } catch (_: Throwable) {
            null
        }
        val pkg = pkgs?.let { list ->
            // Prefer the package that has a launcher icon (shared-UID apps)
            list.firstOrNull { pm.getLaunchIntentForPackage(it) != null } ?: list.firstOrNull()
        }
        val id = if (pkg != null) {
            pkg to labelFor(pkg)
        } else {
            "uid:$uid" to appContext.getString(R.string.unknown_app, uid)
        }
        identityByUid[uid] = id
        return id
    }

    fun labelFor(pkg: String): String {
        labelByPkg[pkg]?.let { return it }
        val label = try {
            val pm = appContext.packageManager
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        } catch (_: Exception) {
            if (pkg.startsWith("uid:")) appContext.getString(R.string.unknown_app, pkg.removePrefix("uid:").toIntOrNull() ?: 0)
            else pkg
        }
        labelByPkg[pkg] = label
        return label
    }
}
