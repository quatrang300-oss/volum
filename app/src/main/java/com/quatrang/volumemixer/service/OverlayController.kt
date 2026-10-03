package com.quatrang.volumemixer.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.quatrang.volumemixer.R
import com.quatrang.volumemixer.engine.AppAudio
import com.quatrang.volumemixer.engine.AppSettings
import com.quatrang.volumemixer.engine.MixerEngine
import com.quatrang.volumemixer.ui.AppVolumeAdapter
import com.quatrang.volumemixer.ui.MaxHeightRecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Floating bubble + compact volume panel drawn over other apps.
 * Designed for split-screen / pop-up window use: tap the bubble, drag a slider, tap outside to close.
 */
class OverlayController(private val context: Context) {

    private val themed = ContextThemeWrapper(context, R.style.Theme_VolumeMixer)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val settings = AppSettings(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var panelAdapter: AppVolumeAdapter? = null
    private var panelEmpty: TextView? = null
    private var lastCollapseAt = 0L

    val isShowing: Boolean get() = bubble != null

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    // ------------------------------------------------------------------ bubble

    fun show() {
        if (bubble != null) return
        if (!Settings.canDrawOverlays(context)) return
        val size = dp(52)
        val (sw, sh) = screenSize()

        val view = ImageView(themed).apply {
            setImageResource(R.drawable.ic_equalizer)
            setBackgroundResource(R.drawable.bg_bubble)
            val pad = dp(13)
            setPadding(pad, pad, pad, pad)
            elevation = dp(6).toFloat()
            alpha = 0.92f
            contentDescription = context.getString(R.string.overlay_bubble_desc)
        }

        val params = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.bubbleX.takeIf { it >= 0 } ?: (sw - size)
            y = settings.bubbleY.takeIf { it >= 0 } ?: (sh / 3)
            x = x.coerceIn(0, max(0, sw - size))
            y = y.coerceIn(0, max(0, sh - size))
        }

        attachDrag(view, params)

        try {
            wm.addView(view, params)
        } catch (t: Throwable) {
            Log.e("Overlay", "addView bubble failed", t)
            return
        }
        bubble = view
        bubbleParams = params

        collectJob = scope.launch {
            MixerEngine.state.collect { render(it) }
        }
    }

    fun hide() {
        collapse()
        bubble?.let { safeRemove(it) }
        bubble = null
        bubbleParams = null
        collectJob?.cancel()
        collectJob = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        collapse()
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        safeUpdate(v, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        snapToEdge(v, params)
                    } else {
                        v.performClick()
                        // A tap that just closed the panel (outside touch) must not reopen it.
                        if (SystemClock.uptimeMillis() - lastCollapseAt > 350) togglePanel()
                    }
                }
            }
            true
        }
    }

    private fun snapToEdge(v: View, params: WindowManager.LayoutParams) {
        val (sw, sh) = screenSize()
        val size = v.width.takeIf { it > 0 } ?: dp(52)
        params.x = if (params.x + size / 2 < sw / 2) 0 else sw - size
        params.y = params.y.coerceIn(0, max(0, sh - size))
        safeUpdate(v, params)
        settings.bubbleX = params.x
        settings.bubbleY = params.y
    }

    // ------------------------------------------------------------------ panel

    fun togglePanel() {
        if (panel != null) collapse() else expand()
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    private fun expand() {
        if (panel != null || bubble == null) return
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_panel, null)
        val list = view.findViewById<MaxHeightRecyclerView>(R.id.panel_list)
        val empty = view.findViewById<TextView>(R.id.panel_empty)
        view.findViewById<ImageButton>(R.id.panel_close).setOnClickListener { collapse() }

        val (sw, sh) = screenSize()
        list.maxHeightPx = min(dp(340), (sh * 0.6f).toInt())
        list.layoutManager = LinearLayoutManager(themed)
        list.itemAnimator = null
        val adapter = AppVolumeAdapter(compact = true, listener = object : AppVolumeAdapter.Listener {
            override fun onVolumeChanged(app: AppAudio, volume: Int) =
                MixerEngine.setVolume(app.packageName, volume)

            override fun onToggleMute(app: AppAudio) = MixerEngine.toggleMute(app.packageName)
        })
        list.adapter = adapter

        view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                collapse()
                true
            } else false
        }

        val width = min(dp(310), sw - dp(16))
        val bp = bubbleParams ?: return
        val bubbleSize = bubble?.width?.takeIf { it > 0 } ?: dp(52)
        val onLeft = bp.x + bubbleSize / 2 < sw / 2
        val params = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (onLeft) min(bp.x + bubbleSize + dp(6), max(0, sw - width))
            else max(0, bp.x - width - dp(6))
            y = max(0, min(bp.y, sh - dp(220)))
        }

        try {
            wm.addView(view, params)
        } catch (t: Throwable) {
            Log.e("Overlay", "addView panel failed", t)
            return
        }
        panel = view
        panelAdapter = adapter
        panelEmpty = empty
        render(MixerEngine.state.value)
    }

    private fun collapse() {
        val p = panel ?: return
        panel = null
        panelAdapter = null
        panelEmpty = null
        lastCollapseAt = SystemClock.uptimeMillis()
        safeRemove(p)
    }

    private fun render(state: MixerEngine.State) {
        val adapter = panelAdapter ?: return
        val empty = panelEmpty ?: return
        adapter.submitList(state.apps)
        val msg = when (state.status) {
            MixerEngine.Status.SHIZUKU_UNAVAILABLE -> context.getString(R.string.overlay_shizuku_off)
            MixerEngine.Status.NOT_PRIVILEGED -> context.getString(R.string.status_not_privileged_desc)
            MixerEngine.Status.ERROR -> context.getString(R.string.status_error_desc, state.message ?: "")
            else -> if (state.apps.isEmpty()) context.getString(R.string.overlay_empty) else null
        }
        empty.text = msg ?: ""
        empty.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun safeRemove(v: View) {
        try {
            wm.removeView(v)
        } catch (_: Throwable) {
        }
    }

    private fun safeUpdate(v: View, p: WindowManager.LayoutParams) {
        try {
            wm.updateViewLayout(v, p)
        } catch (_: Throwable) {
        }
    }
}
