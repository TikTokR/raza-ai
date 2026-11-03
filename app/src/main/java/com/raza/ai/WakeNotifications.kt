package com.raza.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Notification channels and the "tap to turn listening back on" prompt (Android will not let us restart the microphone ourselves). */
object WakeNotifications {
    const val CHANNEL_LISTENING = "raza_ai_mic"
    const val CHANNEL_ALERT = "raza_ai_wake_alert"
    const val CHANNEL_PROMPT = "raza_ai_resume"
    const val ID_RESUME = 503
    const val EXTRA_RESUME_WAKE = "resume_wake"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_LISTENING, "Wake listening", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown the whole time RAZA AI is using the microphone to listen for your wake phrase."
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "Wake phrase heard", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Tap to talk to RAZA AI after it hears your wake phrase."
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROMPT, "Listening stopped", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when background listening has stopped and lets you turn it back on."
            },
        )
    }

    /** Honest notice: listening is NOT running. Tapping opens the app, which can start the microphone legitimately. */
    fun resumePrompt(ctx: Context, text: String) {
        try {
            ensureChannels(ctx)
            val open = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_RESUME_WAKE, true)
            val pi = PendingIntent.getActivity(ctx, 7, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, CHANNEL_PROMPT)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("RAZA AI is NOT listening")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(ID_RESUME, n)
        } catch (_: Exception) {
        }
    }

    fun clearResumePrompt(ctx: Context) {
        try {
            ctx.getSystemService(NotificationManager::class.java).cancel(ID_RESUME)
        } catch (_: Exception) {
        }
    }
}
