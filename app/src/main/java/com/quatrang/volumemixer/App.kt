package com.quatrang.volumemixer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.quatrang.volumemixer.engine.MixerEngine
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // Allow reflection on hidden android.media.* APIs (AudioPlaybackConfiguration, PlayerProxy, IAudioService)
                HiddenApiBypass.addHiddenApiExemptions("L")
            } catch (t: Throwable) {
                Log.w("App", "Hidden API exemption failed", t)
            }
        }
        MixerEngine.init(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "mixer_service"
    }
}
