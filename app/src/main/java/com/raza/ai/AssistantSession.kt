package com.raza.ai

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import com.raza.ai.core.ApiClient
import com.raza.ai.core.ApiConfig
import com.raza.ai.core.AssistantEngine
import com.raza.ai.core.ConfirmationManager
import com.raza.ai.core.ConnectionResolver
import com.raza.ai.core.ConnectionState
import com.raza.ai.core.ConversationHistory
import com.raza.ai.core.DeviceAction
import com.raza.ai.core.Languages
import com.raza.ai.core.LocalCommandParser
import com.raza.ai.core.Outcome
import com.raza.ai.core.ParseResult
import com.raza.ai.core.RecognizerErrors
import com.raza.ai.core.Source
import com.raza.ai.core.UrlConnectionTransport
import com.raza.ai.core.UrlValidator
import com.raza.ai.data.SecureTokenStore
import com.raza.ai.data.SettingsStore
import com.raza.ai.device.ActionExecutor
import com.raza.ai.device.AndroidConnectivity
import com.raza.ai.device.AndroidDeviceInfo
import com.raza.ai.voice.Speaker
import com.raza.ai.voice.VoiceInput
import com.raza.ai.voice.WakeHub
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The one place that connects voice, the engine, confirmation, owner check and the executor.
 * All state is read and changed on the main thread; network work runs on a background thread.
 */
class AssistantSession(
    private val app: Application,
    private val settings: SettingsStore,
    private val tokens: SecureTokenStore,
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
    enum class Phase { IDLE, LISTENING, PROCESSING, SPEAKING }
    data class ChatMessage(val role: Role, val text: String, val source: Source? = null, val time: Long = System.currentTimeMillis())

    private val main = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    val messages: MutableList<ChatMessage> = ArrayList()
    var phase: Phase = Phase.IDLE
        private set
    var partial: String = ""
        private set
    var connection: ConnectionState = ConnectionState.Checking
        private set

    /** Set by the visible Activity: shows Android's own lock-screen check. */
    var authLauncher: ((ConfirmationManager.Pending) -> Unit)? = null

    private val connectivity = AndroidConnectivity(app)
    private val confirmations = ConfirmationManager()
    private val api = ApiClient({ ApiConfig(settings.apiBaseUrl, tokens.get()) }, UrlConnectionTransport())
    private val keyguard: KeyguardManager? = app.getSystemService(KeyguardManager::class.java)
    private val history = ConversationHistory()
    private val engine = AssistantEngine(
        api = api,
        confirmations = confirmations,
        connectivity = connectivity,
        deviceInfo = AndroidDeviceInfo(app),
        history = history,
        authAvailable = { keyguard?.isDeviceSecure == true },
    )
    private val executor = ActionExecutor(app)
    private val speaker = Speaker(app, settings) { main.post { onSpeechFinished() } }
    private val voice = VoiceInput(
        app,
        object : VoiceInput.Callback {
            override fun onReady() {
                partial = ""
                notifyChanged()
            }

            override fun onPartial(text: String) {
                partial = text
                notifyChanged()
            }

            override fun onFinal(text: String) {
                partial = ""
                submit(text, byVoice = true)
            }

            override fun onError(code: Int) {
                partial = ""
                system(RecognizerErrors.message(code))
                setPhase(Phase.IDLE)
            }
        },
    )

    private var authPendingId: Long? = null
    private var lastReply = ""
    private var listenAfterSpeak = false
    private val warned = HashSet<String>()

    init {
        registerNetworkCallback()
        refreshConnection()
    }

    // ---- observation ----

    fun addListener(l: () -> Unit) {
        listeners.addIfAbsent(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    private fun notifyChanged() {
        listeners.forEach { it() }
    }

    fun pending(): ConfirmationManager.Pending? = confirmations.current()

    // ---- input ----

    /** Typed or spoken text. Main thread. */
    fun submit(raw: String, byVoice: Boolean = false) {
        val text = raw.trim()
        if (text.isEmpty()) return
        if (phase == Phase.LISTENING && !byVoice) voice.cancel()
        speaker.stop()
        listenAfterSpeak = false
        addMessage(ChatMessage(Role.USER, text))

        val open = confirmations.current()
        if (open != null) {
            val d = confirmations.respond(text)
            when (d) {
                ConfirmationManager.Decision.Unclear -> {
                    if (LocalCommandParser.parse(text) is ParseResult.NoMatch) {
                        say("I still need a yes or no: ${open.action.describe()}", listen = byVoice)
                        return
                    }
                    confirmations.reject() // a new command replaces the old proposal
                }
                // The proposal vanished while we were looking: treat the text as a normal message.
                ConfirmationManager.Decision.NothingPending -> Unit
                else -> {
                    handleDecision(d, byVoice)
                    return
                }
            }
        }

        setPhase(Phase.PROCESSING)
        val language = settings.language
        io.execute {
            val outcome = engine.handle(text, language)
            main.post { onOutcome(outcome, byVoice) }
        }
    }

    private fun onOutcome(outcome: Outcome, byVoice: Boolean) {
        when (outcome) {
            is Outcome.Reply -> say(outcome.text, source = outcome.source)
            is Outcome.Failure -> say(outcome.text)
            is Outcome.Confirm -> {
                val p = outcome.pending
                val auth = if (p.needsAuth) " I will ask you to confirm with your phone's screen lock first." else ""
                say("${p.action.describe()}$auth Say yes to go ahead, or no to cancel.", listen = byVoice)
            }
        }
    }

    // ---- confirmation ----

    fun confirmYes() {
        val p = confirmations.current()
        if (p == null) {
            handleDecision(confirmations.respond("yes"), false)
            return
        }
        handleDecision(confirmations.confirm(p.id), false)
    }

    fun confirmNo() {
        handleDecision(confirmations.reject(), false)
    }

    private fun handleDecision(d: ConfirmationManager.Decision, byVoice: Boolean) {
        when (d) {
            is ConfirmationManager.Decision.Execute -> runAction(d.action)
            is ConfirmationManager.Decision.NeedsAuth -> requestAuth(d.pending)
            ConfirmationManager.Decision.Rejected -> say("Cancelled. Nothing was done.")
            ConfirmationManager.Decision.Expired -> say("That request timed out, so nothing was done. Please ask again.")
            ConfirmationManager.Decision.NothingPending -> say("There is nothing waiting for confirmation.")
            ConfirmationManager.Decision.Unclear -> say("Please answer yes or no.", listen = byVoice)
        }
    }

    private fun requestAuth(p: ConfirmationManager.Pending) {
        val launcher = authLauncher
        if (launcher == null) {
            confirmations.reject()
            say("I need you to unlock your phone to continue, but I can't show the unlock screen right now. Open the app and ask again.")
            return
        }
        authPendingId = p.id
        system("Confirm it's you with your phone's screen lock.")
        launcher(p)
    }

    /** Result of Android's own device-credential screen. RAZA never sees the PIN, pattern or password. */
    fun onAuthResult(ok: Boolean) {
        val id = authPendingId ?: return
        authPendingId = null
        if (!ok) {
            confirmations.reject()
            say("The unlock was cancelled, so nothing was done.")
            return
        }
        if (!confirmations.markAuthenticated(id)) {
            say("That request is no longer valid, so nothing was done. Please ask again.")
            return
        }
        handleDecision(confirmations.confirm(id), false)
    }

    private fun runAction(action: DeviceAction) {
        setPhase(Phase.PROCESSING)
        val result = executor.execute(action)
        say(result.message)
    }

    // ---- voice ----

    fun startListening(): Boolean {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            system("The microphone permission is needed to listen. Allow it and try again.")
            return false
        }
        if (!voice.isAvailable()) {
            system("Speech recognition is not available on this phone.")
            return false
        }
        if (phase == Phase.LISTENING) return true
        speaker.stop()
        WakeHub.control?.pauseForCommand()
        partial = ""
        return try {
            voice.start(Languages.speechTag(settings.language), !connectivity.isOnline() || settings.preferOfflineSpeech)
            setPhase(Phase.LISTENING)
            true
        } catch (e: Exception) {
            system("Could not start listening (${e.javaClass.simpleName}).")
            setPhase(Phase.IDLE)
            false
        }
    }

    fun stopListening() {
        voice.cancel()
        partial = ""
        if (phase == Phase.LISTENING) setPhase(Phase.IDLE)
    }

    fun stopSpeaking() {
        listenAfterSpeak = false
        speaker.stop()
        if (phase == Phase.SPEAKING) setPhase(Phase.IDLE)
    }

    fun repeatLast() {
        if (lastReply.isBlank()) return
        speakText(lastReply, listen = false)
    }

    /** Called by the wake service while the UI is visible. Returns true if the UI took over. */
    fun handleWake(command: String?): Boolean {
        if (phase != Phase.IDLE) return false
        if (!command.isNullOrBlank()) {
            submit(command, byVoice = true)
        } else if (!startListening()) {
            WakeHub.control?.resumeAfterCommand()
        }
        return true
    }

    private fun say(text: String, listen: Boolean = false, source: Source? = null) {
        addMessage(ChatMessage(Role.ASSISTANT, text, source))
        lastReply = text
        if (speakText(text, listen)) return
        if (listen && startListening()) return
        setPhase(Phase.IDLE)
    }

    /** Speaks if voice replies are on. Returns true if speech started. */
    private fun speakText(text: String, listen: Boolean): Boolean {
        if (!settings.voiceReplies) return false
        return when (val r = speaker.speak(text, preferOffline = !connectivity.isOnline())) {
            Speaker.Result.Started -> {
                listenAfterSpeak = listen
                setPhase(Phase.SPEAKING)
                true
            }
            Speaker.Result.NotReady -> {
                systemOnce("tts-not-ready", "The voice engine isn't ready yet, so I'm showing text only.")
                false
            }
            is Speaker.Result.LanguageMissing -> {
                systemOnce(
                    "tts-${r.tag}",
                    "This phone has no ${r.tag} voice installed, so I'm showing text only. " +
                        "Install it in Android Settings > Text-to-speech output.",
                )
                false
            }
        }
    }

    private fun onSpeechFinished() {
        if (phase != Phase.SPEAKING) return
        if (listenAfterSpeak) {
            listenAfterSpeak = false
            if (startListening()) return
        }
        setPhase(Phase.IDLE)
    }

    // ---- connection ----

    fun refreshConnection() {
        val configured = UrlValidator.normalizeBase(settings.apiBaseUrl) != null
        if (!configured || !connectivity.isOnline()) {
            connection = ConnectionResolver.resolve(configured, connectivity.isOnline(), null)
            notifyChanged()
            return
        }
        connection = ConnectionState.Checking
        notifyChanged()
        io.execute {
            val health = api.health()
            main.post {
                connection = ConnectionResolver.resolve(true, connectivity.isOnline(), health)
                notifyChanged()
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    main.post { refreshConnection() }
                }

                override fun onLost(network: Network) {
                    main.post { refreshConnection() }
                }
            })
        } catch (_: Exception) {
            // Without the callback the status still refreshes whenever the app is opened.
        }
    }

    // ---- helpers ----

    fun clearConversation() {
        messages.clear()
        history.clear()
        lastReply = ""
        notifyChanged()
    }

    private fun setPhase(p: Phase) {
        phase = p
        notifyChanged()
        if (p == Phase.IDLE) WakeHub.control?.resumeAfterCommand()
    }

    private fun addMessage(m: ChatMessage) {
        messages.add(m)
        while (messages.size > MAX_MESSAGES) messages.removeAt(0)
        notifyChanged()
    }

    private fun system(text: String) = addMessage(ChatMessage(Role.SYSTEM, text))

    private fun systemOnce(key: String, text: String) {
        if (warned.add(key)) system(text)
    }

    companion object {
        private const val MAX_MESSAGES = 200
    }
}
