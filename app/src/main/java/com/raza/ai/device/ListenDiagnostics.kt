package com.raza.ai.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.raza.ai.core.ExitReasons
import java.text.DateFormat
import java.util.Date

/** Asks Android (API 30+) why it last ended RAZA's process. Nothing is guessed. */
class ListenDiagnostics(private val ctx: Context) {
    fun lastStopReason(): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return null
            val info = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return null
            val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(info.timestamp))
            "${ExitReasons.describe(info.reason)} ($whenText)"
        } catch (e: Exception) {
            null
        }
    }
}
