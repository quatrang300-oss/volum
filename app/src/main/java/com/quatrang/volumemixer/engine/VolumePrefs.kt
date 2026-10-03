package com.quatrang.volumemixer.engine

import android.content.Context

/** Per-app volume settings, keyed by package name. */
class VolumePrefs(context: Context) {

    data class Setting(val volume: Int = 100, val muted: Boolean = false) {
        val isDefault: Boolean get() = volume >= 100 && !muted

        /**
         * Converts the 0..100 slider into an amplitude multiplier.
         * A squared curve feels much more natural to the ear than a linear one
         * (50% on the slider ≈ -12 dB).
         */
        fun gain(): Float {
            if (muted || volume <= 0) return 0f
            val v = volume.coerceIn(0, 100) / 100f
            return v * v
        }
    }

    private val sp = context.getSharedPreferences("app_volumes", Context.MODE_PRIVATE)

    fun get(pkg: String): Setting =
        Setting(sp.getInt(KEY_VOL + pkg, 100), sp.getBoolean(KEY_MUTE + pkg, false))

    fun set(pkg: String, setting: Setting) {
        val e = sp.edit()
        if (setting.isDefault) {
            e.remove(KEY_VOL + pkg).remove(KEY_MUTE + pkg)
        } else {
            e.putInt(KEY_VOL + pkg, setting.volume.coerceIn(0, 100))
                .putBoolean(KEY_MUTE + pkg, setting.muted)
        }
        e.apply()
    }

    fun savedPackages(): Set<String> =
        sp.all.keys.mapNotNull { k ->
            when {
                k.startsWith(KEY_VOL) -> k.removePrefix(KEY_VOL)
                k.startsWith(KEY_MUTE) -> k.removePrefix(KEY_MUTE)
                else -> null
            }
        }.toSet()

    fun clearAll() {
        sp.edit().clear().apply()
    }

    companion object {
        private const val KEY_VOL = "v_"
        private const val KEY_MUTE = "m_"
    }
}

/** General app settings. */
class AppSettings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay", false)
        set(v) = sp.edit().putBoolean("overlay", v).apply()

    var bubbleX: Int
        get() = sp.getInt("bubble_x", -1)
        set(v) = sp.edit().putInt("bubble_x", v).apply()

    var bubbleY: Int
        get() = sp.getInt("bubble_y", -1)
        set(v) = sp.edit().putInt("bubble_y", v).apply()
}
