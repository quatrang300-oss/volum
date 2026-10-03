package com.quatrang.volumemixer.engine

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Small helper around the Shizuku API. All calls are safe to make from any thread. */
object ShizukuState {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val REQUEST_CODE = 4210

    enum class Status { NOT_INSTALLED, NOT_RUNNING, TOO_OLD, NO_PERMISSION, READY }

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    private fun isPreV11(): Boolean = try {
        Shizuku.isPreV11()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        isRunning() && !isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    fun isReady(): Boolean = hasPermission()

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    fun status(context: Context): Status = when {
        !isRunning() -> if (isInstalled(context)) Status.NOT_RUNNING else Status.NOT_INSTALLED
        isPreV11() -> Status.TOO_OLD
        !hasPermission() -> Status.NO_PERMISSION
        else -> Status.READY
    }

    fun requestPermission() {
        try {
            if (isRunning() && !isPreV11()) Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {
        }
    }
}
