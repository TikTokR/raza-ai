package com.raza.ai

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.raza.ai.data.SettingsStore

/**
 * After a restart or an app update Android has stopped the listener. Since Android 14/15 a background receiver is
 * not allowed to start a microphone service, and RAZA does not try to get around that. It only tells you, honestly,
 * that listening is off and lets you turn it back on with one tap.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!SettingsStore(context).wakeEnabled) return
        val why = if (action == Intent.ACTION_BOOT_COMPLETED) "The phone restarted." else "RAZA AI was updated."
        WakeNotifications.resumePrompt(context, "$why Background listening is off. Tap to turn it back on.")
    }
}
