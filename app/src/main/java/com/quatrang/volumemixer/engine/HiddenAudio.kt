package com.quatrang.volumemixer.engine

import android.content.Context
import android.media.AudioPlaybackConfiguration
import android.os.IBinder
import android.util.Log
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Talks to the system AudioService through Shizuku (shell privileges).
 *
 * How per-app volume works:
 *  1. As shell (via Shizuku) we call IAudioService.getActivePlaybackConfigurations(). Because the
 *     caller holds MODIFY_AUDIO_ROUTING, the returned configurations are NOT anonymised: each one
 *     contains the owning app's UID and a binder to the player (IPlayer).
 *  2. AudioPlaybackConfiguration.getPlayerProxy().setVolume(v) sets a volume *multiplier* on that
 *     player (the same mechanism Android itself uses to mute players). It is multiplied with the
 *     app's own volume and the system media volume, so each app can be turned down/muted
 *     independently.
 */
object HiddenAudio {

    private const val TAG = "HiddenAudio"

    const val PLAYER_STATE_STARTED = 2
    const val UID_INVALID = -1

    data class Player(val piid: Int, val uid: Int, val state: Int, val proxy: Any?)

    private var audioService: Any? = null

    private val iAudioServiceClass: Class<*> by lazy { Class.forName("android.media.IAudioService") }

    private val getConfigsMethod: Method by lazy {
        iAudioServiceClass.getMethod("getActivePlaybackConfigurations").apply { isAccessible = true }
    }

    private val apcClass = AudioPlaybackConfiguration::class.java

    private val getUidMethod: Method? by lazy { findMethod(apcClass, "getClientUid") }
    private val getPiidMethod: Method? by lazy { findMethod(apcClass, "getPlayerInterfaceId") }
    private val getStateMethod: Method? by lazy { findMethod(apcClass, "getPlayerState") }
    private val getProxyMethod: Method? by lazy { findMethod(apcClass, "getPlayerProxy") }

    private val proxySetVolumeMethod: Method? by lazy {
        try {
            Class.forName("android.media.PlayerProxy")
                .getDeclaredMethod("setVolume", java.lang.Float.TYPE)
                .apply { isAccessible = true }
        } catch (t: Throwable) {
            Log.e(TAG, "PlayerProxy.setVolume not found", t)
            null
        }
    }

    private fun findMethod(cls: Class<*>, name: String): Method? = try {
        cls.getDeclaredMethod(name).apply { isAccessible = true }
    } catch (t: Throwable) {
        Log.w(TAG, "Method $name not found", t)
        null
    }

    @Synchronized
    private fun service(): Any {
        audioService?.let { return it }
        val raw: IBinder = SystemServiceHelper.getSystemService(Context.AUDIO_SERVICE)
            ?: throw IllegalStateException("Audio service not available")
        val wrapped = ShizukuBinderWrapper(raw)
        val stub = Class.forName("android.media.IAudioService\$Stub")
        val svc = stub.getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
            ?: throw IllegalStateException("IAudioService.asInterface returned null")
        audioService = svc
        return svc
    }

    @Synchronized
    fun reset() {
        audioService = null
    }

    /** Reads all registered players. Throws if Shizuku/AudioService is unavailable. */
    fun readPlayers(): List<Player> {
        val raw: List<*> = try {
            getConfigsMethod.invoke(service()) as? List<*> ?: emptyList<Any>()
        } catch (t: Throwable) {
            reset()
            throw unwrap(t)
        }
        val out = ArrayList<Player>(raw.size)
        for (item in raw) {
            val conf = item as? AudioPlaybackConfiguration ?: continue
            out += Player(
                piid = invokeInt(getPiidMethod, conf, "mPlayerIId", -1),
                uid = invokeInt(getUidMethod, conf, "mClientUid", UID_INVALID),
                state = invokeInt(getStateMethod, conf, "mPlayerState", 0),
                proxy = try {
                    getProxyMethod?.invoke(conf)
                } catch (_: Throwable) {
                    null
                }
            )
        }
        return out
    }

    /** Sets the volume multiplier (0..1) on a player. Returns false if the player is gone. */
    fun setVolume(proxy: Any, volume: Float): Boolean {
        val m = proxySetVolumeMethod ?: return false
        return try {
            m.invoke(proxy, volume.coerceIn(0f, 1f))
            true
        } catch (t: Throwable) {
            Log.d(TAG, "setVolume failed: ${unwrap(t).message}")
            false
        }
    }

    private fun invokeInt(method: Method?, target: Any, fieldName: String, def: Int): Int {
        if (method != null) {
            try {
                return (method.invoke(target) as? Int) ?: def
            } catch (_: Throwable) {
            }
        }
        return try {
            val f = apcClass.getDeclaredField(fieldName)
            f.isAccessible = true
            f.getInt(target)
        } catch (_: Throwable) {
            def
        }
    }

    private fun unwrap(t: Throwable): Throwable =
        if (t is InvocationTargetException) t.targetException ?: t else t
}
