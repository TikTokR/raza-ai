package com.raza.ai

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.raza.ai.core.Languages
import com.raza.ai.core.RecognizerErrors
import com.raza.ai.core.RestartBackoff
import com.raza.ai.core.Script
import com.raza.ai.core.WakeMatcher
import com.raza.ai.core.WakeState
import com.raza.ai.core.WakeStateMachine
import com.raza.ai.data.SettingsStore
import com.raza.ai.voice.WakeHub

/**
 * Foreground microphone service that listens for the user's wake phrase while the app is closed or in the background.
 *
 * What it does and does not promise (also shown in the app):
 *  - It keeps running after you press Home or remove RAZA from Recent Apps (stopWithTask=false).
 *  - Audio is processed by Android's speech recogniser and only inspected for the wake phrase; RAZA records, saves
 *    and uploads nothing. On Android 13+ it uses the on-device recogniser when the phone has one, so audio stays on the phone.
 *    Otherwise Android's speech service is used and may use the network. The status line says which one is active.
 *  - It is NOT a low-power hotword chip, so it costs battery.
 *  - Android may still end it (force-stop, battery managers, low memory). It is started by the user while the app is
 *    visible, as Android 12+/14+ require. If Android kills it, the system may restart it, but since Android 14 a
 *    restarted service cannot reopen the microphone from the background; then RAZA posts a "not listening, tap to
 *    turn on" notice instead of pretending. It is never started silently after a reboot.
 *  - The microphone indicator and the notification are always shown; this service never hides them.
 */
class WakeWordService : Service(), WakeHub.Control {

    private val machine = WakeStateMachine()
    private val backoff = RestartBackoff()
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private lateinit var settings: SettingsStore
    private var pausedByUser = false
    private var usingOnDevice = false
    private var onDeviceBroken = false

    private val listenRunnable = Runnable { listen() }
    private val commandTimeout = Runnable {
        // The UI took the wake event but never came back: do not stay deaf forever.
        if (machine.state == WakeState.COMMAND_LISTENING) {
            machine.onCommandFinished()
            publish()
            listen()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        WakeNotifications.ensureChannels(this)
        WakeHub.control = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> shutdown(byUser = true)
            ACTION_USER_PAUSE -> userPause()
            ACTION_USER_RESUME -> userResume()
            ACTION_RESTART -> restart()
            null -> recoverAfterKill() // Android restarted us after it ended our process
            else -> startWake()
        }
        // STICKY: if Android ends the process, ask it to bring the service back. An explicit stop is never restarted.
        return START_STICKY
    }

    /** Called when the system recreates the service with no intent. */
    private fun recoverAfterKill() {
        if (!settings.wakeEnabled) {
            stopSelf()
            return
        }
        // startWake() posts the honest "tap to turn on" notice if Android will not let a background service use the mic.
        startWake()
    }

    private fun restart() {
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        machine.stop()
        pausedByUser = false
        onDeviceBroken = false
        settings.wakeEnabled = true
        startWake()
    }

    // ---- lifecycle ----

    private fun startWake() {
        // startForegroundService() obliges us to call startForeground quickly, even if we then give up.
        val inForeground = enterForeground()
        if (machine.state != WakeState.OFF && machine.state != WakeState.ERROR) return // already running
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!machine.start(granted)) { // no permission: machine records the error
            publish()
            WakeNotifications.resumePrompt(this, (machine.error ?: "Microphone permission is missing.") + " Open RAZA AI to fix it.")
            stopSelf()
            return
        }
        if (!inForeground) {
            val msg = "Android would not let the wake listener use the microphone from the background."
            machine.onError(msg)
            publish()
            WakeNotifications.resumePrompt(this, "$msg Tap to turn listening back on.")
            stopSelf()
            return
        }
        WakeNotifications.clearResumePrompt(this)
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            machine.onError("Speech recognition is not available on this phone.")
            publish()
            stopSelf()
            return
        }
        machine.onReady()
        pausedByUser = false
        backoff.reset()
        publish()
        listen()
    }

    private fun enterForeground(): Boolean = try {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        true
    } catch (e: Exception) {
        false
    }

    private fun shutdown(byUser: Boolean) {
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        machine.stop()
        if (byUser) {
            settings.wakeEnabled = false // an explicit Stop must never be undone by a sticky restart
            WakeNotifications.clearResumePrompt(this)
        }
        WakeHub.engine = ""
        WakeHub.publish(WakeState.OFF, null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The app was swiped away from Recent Apps. The service keeps running on purpose; nothing to do. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        if (WakeHub.control === this) WakeHub.control = null
        WakeHub.engine = ""
        if (machine.state != WakeState.ERROR) WakeHub.publish(WakeState.OFF, null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- WakeHub.Control (called by the UI on the main thread) ----

    override fun pauseForCommand(): Boolean {
        if (machine.state != WakeState.LISTENING) return false
        machine.pause()
        destroyRecognizer()
        publish()
        return true
    }

    override fun resumeAfterCommand() {
        if (pausedByUser) return
        main.removeCallbacks(commandTimeout)
        val moved = when (machine.state) {
            WakeState.PAUSED -> machine.resume()
            WakeState.COMMAND_LISTENING -> machine.onCommandFinished()
            else -> false
        }
        if (moved) {
            publish()
            main.removeCallbacks(listenRunnable)
            main.postDelayed(listenRunnable, 400)
        }
    }

    private fun userPause() {
        pausedByUser = true
        main.removeCallbacks(listenRunnable)
        if (machine.state == WakeState.LISTENING || machine.state == WakeState.COMMAND_LISTENING) {
            machine.pause()
            destroyRecognizer()
        }
        publish()
    }

    private fun userResume() {
        pausedByUser = false
        if (machine.state == WakeState.PAUSED && machine.resume()) {
            publish()
            listen()
        }
    }

    // ---- recognition loop ----

    private fun listen() {
        if (machine.state != WakeState.LISTENING) return
        destroyRecognizer()
        val phrase = settings.wakePhrase
        val tag = if (Script.isUrdu(phrase)) Languages.speechTag(Languages.URDU) else Languages.speechTag(Languages.ENGLISH)
        try {
            usingOnDevice = Build.VERSION.SDK_INT >= 33 && settings.preferOfflineSpeech && !onDeviceBroken &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            val r = if (usingOnDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(this) else SpeechRecognizer.createSpeechRecognizer(this)
            WakeHub.engine = if (usingOnDevice) "on-device recogniser (audio stays on the phone)" else "Android speech service (may use the network)"
            recognizer = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onPartialResults(partialResults: Bundle?) = checkForWake(partialResults, phrase, isFinal = false)
                override fun onResults(results: Bundle?) = checkForWake(results, phrase, isFinal = true)
                override fun onError(error: Int) = handleError(error)
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            r.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // privacy: avoid streaming ambient audio when possible
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                },
            )
        } catch (e: Exception) {
            fail("Could not start the wake listener (${e.javaClass.simpleName}).")
        }
    }

    private fun checkForWake(bundle: Bundle?, phrase: String, isFinal: Boolean) {
        val heard = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        val hit = heard.firstOrNull { WakeMatcher.matches(it, phrase) }
        if (hit != null) {
            onWake(hit, phrase)
        } else if (isFinal) {
            backoff.reset()
            scheduleListen(150)
        }
    }

    private fun onWake(transcript: String, phrase: String) {
        if (!machine.onWakeDetected()) return // already handling one
        destroyRecognizer()
        publish()
        val command = WakeMatcher.commandAfter(transcript, phrase)
        val handled = WakeHub.wakeHandler?.invoke(command) ?: false
        if (handled) {
            main.removeCallbacks(commandTimeout)
            main.postDelayed(commandTimeout, COMMAND_TIMEOUT_MS)
        } else {
            // App not visible: Android will not let a background service open the app or take over the
            // screen, so we post a notification the user can tap, then keep listening.
            postWakeAlert()
            machine.onCommandFinished()
            publish()
            scheduleListen(2000)
        }
    }

    private fun handleError(code: Int) {
        if (usingOnDevice && (code == 12 || code == 13)) {
            // The on-device model does not cover this language: fall back to Android's speech service and say so.
            onDeviceBroken = true
            scheduleListen(200)
            return
        }
        when (RecognizerErrors.classify(code)) {
            RecognizerErrors.Action.RETRY_QUICK -> {
                backoff.reset()
                scheduleListen(300)
            }
            RecognizerErrors.Action.RETRY_BACKOFF -> {
                if (backoff.failureCount >= MAX_HARD_FAILURES) {
                    fail("Wake listener stopped after repeated errors: ${RecognizerErrors.message(code)}")
                } else {
                    scheduleListen(backoff.next())
                }
            }
            RecognizerErrors.Action.FATAL_PERMISSION -> fail(RecognizerErrors.message(code))
            RecognizerErrors.Action.FATAL_LANGUAGE -> fail(RecognizerErrors.message(code))
        }
    }

    private fun fail(message: String) {
        main.removeCallbacksAndMessages(null)
        destroyRecognizer()
        machine.onError(message)
        WakeHub.engine = ""
        publish()
        WakeNotifications.resumePrompt(this, "$message Tap to open RAZA AI.")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun scheduleListen(delayMs: Long) {
        main.removeCallbacks(listenRunnable)
        main.postDelayed(listenRunnable, delayMs)
    }

    private fun destroyRecognizer() {
        val r = recognizer ?: return
        recognizer = null
        try {
            r.cancel()
            r.destroy()
        } catch (_: Exception) {
        }
    }

    // ---- notifications ----

    private fun publish() {
        WakeHub.publish(machine.state, machine.error)
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    private fun openAppIntent(fromWake: Boolean): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_FROM_WAKE, fromWake)
        return PendingIntent.getActivity(this, if (fromWake) 1 else 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun serviceAction(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, WakeWordService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun buildNotification(): Notification {
        val phrase = settings.wakePhrase
        val text = when (machine.state) {
            WakeState.LISTENING, WakeState.STARTING -> "Microphone is on. Listening for \"$phrase\"."
            WakeState.COMMAND_LISTENING -> "Microphone is on. Listening to your command."
            WakeState.PAUSED -> "Paused. The microphone is off."
            WakeState.ERROR -> machine.error ?: "Stopped because of an error."
            WakeState.OFF -> "Starting..."
        }
        val toggle = if (machine.state == WakeState.PAUSED) {
            Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_play), "Resume", serviceAction(ACTION_USER_RESUME, 2)).build()
        } else {
            Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Pause", serviceAction(ACTION_USER_PAUSE, 3)).build()
        }
        return Notification.Builder(this, WakeNotifications.CHANNEL_LISTENING)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("RAZA AI")
            .setContentText(text)
            .setContentIntent(openAppIntent(false))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(toggle)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Stop", serviceAction(ACTION_STOP, 4)).build())
            .build()
    }

    private fun postWakeAlert() {
        try {
            val n = Notification.Builder(this, WakeNotifications.CHANNEL_ALERT)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("RAZA AI heard you")
                .setContentText("Tap to talk. Android does not let a background service open the app by itself.")
                .setContentIntent(openAppIntent(true))
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_START = "com.raza.ai.action.WAKE_START"
        const val ACTION_STOP = "com.raza.ai.action.WAKE_STOP"
        const val ACTION_USER_PAUSE = "com.raza.ai.action.WAKE_USER_PAUSE"
        const val ACTION_USER_RESUME = "com.raza.ai.action.WAKE_USER_RESUME"
        const val ACTION_RESTART = "com.raza.ai.action.WAKE_RESTART"
        const val EXTRA_FROM_WAKE = "from_wake"
        private const val NOTIFICATION_ID = 501
        private const val ALERT_ID = 502
        private const val MAX_HARD_FAILURES = 8
        private const val COMMAND_TIMEOUT_MS = 60_000L

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, WakeWordService::class.java).setAction(ACTION_START))
        }

        /** Stop, then listen again from scratch. Must be called while the app is visible (Android 12+/14+ rule). */
        fun restart(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, WakeWordService::class.java).setAction(ACTION_RESTART))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(ACTION_STOP))
        }

        fun userPause(ctx: Context) {
            ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(ACTION_USER_PAUSE))
        }

        fun userResume(ctx: Context) {
            ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(ACTION_USER_RESUME))
        }
    }
}
