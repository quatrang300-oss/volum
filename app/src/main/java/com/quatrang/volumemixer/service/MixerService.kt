package com.quatrang.volumemixer.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.quatrang.volumemixer.App
import com.quatrang.volumemixer.R
import com.quatrang.volumemixer.engine.AppSettings
import com.quatrang.volumemixer.engine.MixerEngine
import com.quatrang.volumemixer.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the volume engine alive while the user watches
 * YouTube / TikTok etc., and hosts the floating volume panel.
 */
class MixerService : Service() {

    companion object {
        private const val TAG = "MixerService"
        private const val NOTIFICATION_ID = 42

        const val ACTION_START = "com.quatrang.volumemixer.START"
        const val ACTION_STOP = "com.quatrang.volumemixer.STOP"
        const val ACTION_SHOW_OVERLAY = "com.quatrang.volumemixer.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.quatrang.volumemixer.HIDE_OVERLAY"
        const val ACTION_TOGGLE_PANEL = "com.quatrang.volumemixer.TOGGLE_PANEL"

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context, action: String = ACTION_START) {
            val i = Intent(context, MixerService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(context, i)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not start service", t)
            }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            try {
                context.startService(Intent(context, MixerService::class.java).setAction(ACTION_STOP))
            } catch (t: Throwable) {
                context.stopService(Intent(context, MixerService::class.java))
            }
        }

        /** Sends a command to an already-running service (no-op otherwise). */
        fun command(context: Context, action: String) {
            if (isRunning) start(context, action)
        }
    }

    private lateinit var overlay: OverlayController
    private lateinit var settings: AppSettings
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastCount = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        overlay = OverlayController(this)
        if (!goForeground()) {
            stopSelf()
            return
        }
        isRunning = true
        MixerEngine.start()

        scope.launch {
            MixerEngine.state
                .map { st -> st.apps.count { it.active && (it.muted || it.volume < 100) } }
                .distinctUntilChanged()
                .collect { count ->
                    lastCount = count
                    updateNotification()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always (re)confirm foreground state: required after every startForegroundService().
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                settings.enabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW_OVERLAY -> {
                settings.overlayEnabled = true
                overlay.show()
            }
            ACTION_HIDE_OVERLAY -> {
                settings.overlayEnabled = false
                overlay.hide()
            }
            ACTION_TOGGLE_PANEL -> {
                if (Settings.canDrawOverlays(this)) {
                    if (!overlay.isShowing) {
                        settings.overlayEnabled = true
                        overlay.show()
                    }
                    overlay.togglePanel()
                } else {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            else -> if (settings.overlayEnabled) overlay.show()
        }
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        try {
            overlay.hide()
        } catch (_: Throwable) {
        }
        MixerEngine.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        true
    } catch (t: Throwable) {
        Log.e(TAG, "startForeground failed", t)
        false
    }

    private fun updateNotification() {
        if (!isRunning) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted – the service still works.
        }
    }

    private fun buildNotification(): Notification {
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            piFlags
        )
        val togglePanel = PendingIntent.getService(
            this, 1,
            Intent(this, MixerService::class.java).setAction(ACTION_TOGGLE_PANEL),
            piFlags
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, MixerService::class.java).setAction(ACTION_STOP),
            piFlags
        )
        val text = if (lastCount > 0) getString(R.string.notif_text_count, lastCount)
        else getString(R.string.notif_text_idle)

        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_equalizer)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_equalizer, getString(R.string.notif_action_panel), togglePanel)
            .addAction(R.drawable.ic_close, getString(R.string.notif_action_stop), stop)
            .build()
    }
}
