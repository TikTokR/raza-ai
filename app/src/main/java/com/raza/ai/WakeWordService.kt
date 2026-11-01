package com.raza.ai

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Experimental wake phrase loop. This is not a hardware low-power hotword engine. */
class WakeWordService : Service() {
    private var recognizer: SpeechRecognizer? = null
    @Volatile private var active = false
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "RAZA AI microphone", NotificationManager.IMPORTANCE_LOW).apply { description = "Visible notification while wake listening is active" })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notification("Wake listener active — say Hey Raza")
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIFICATION_ID, notification)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { stopSelf(); return START_NOT_STICKY }
        active = true; listenOnce(); return START_NOT_STICKY
    }
    private fun listenOnce() {
        if (!active) return
        try {
            recognizer?.destroy()
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: android.os.Bundle?) {
                    val phrase = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().lowercase(Locale.ROOT)
                    if (phrase.contains("hey raza") || phrase.contains("ہیلو رضا") || phrase.contains("اے رضا")) {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification("Wake phrase detected. Open RAZA AI to continue."))
                    }
                    if (active) listenOnce()
                }
                override fun onError(error: Int) { if (active) listenOnce() }
                override fun onReadyForSpeech(params: android.os.Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: android.os.Bundle?) {}
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            recognizer?.startListening(intent)
        } catch (_: Exception) { stopSelf() }
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("RAZA AI").setContentText(message).setContentIntent(open).setOngoing(true).build()
    }
    override fun onDestroy() { active = false; recognizer?.cancel(); recognizer?.destroy(); recognizer = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { private const val CHANNEL = "raza_ai_mic"; private const val NOTIFICATION_ID = 501 }
}
