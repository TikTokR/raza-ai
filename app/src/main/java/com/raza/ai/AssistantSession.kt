package com.raza.ai

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.raza.ai.core.ActionLogEntry
import com.raza.ai.core.AddResult
import com.raza.ai.core.ApiClient
import com.raza.ai.core.ApiConfig
import com.raza.ai.core.AssistantEngine
import com.raza.ai.core.ConfirmationManager
import com.raza.ai.core.ConnectionResolver
import com.raza.ai.core.ConnectionState
import com.raza.ai.core.ConversationHistory
import com.raza.ai.core.ChatRecord
import com.raza.ai.core.DeviceAction
import com.raza.ai.core.HistoryItem
import com.raza.ai.core.Languages
import com.raza.ai.core.LocalCommand
import com.raza.ai.core.LocalCommandParser
import com.raza.ai.core.LocalCommands
import com.raza.ai.core.NoteBook
import com.raza.ai.core.Outcome
import com.raza.ai.core.ParseResult
import com.raza.ai.core.Persistence
import com.raza.ai.core.RecognizerErrors
import com.raza.ai.core.Source
import com.raza.ai.core.TaskBook
import com.raza.ai.core.UrlConnectionTransport
import com.raza.ai.core.UrlValidator
import com.raza.ai.data.LocalStores
import com.raza.ai.data.SecureTokenStore
import com.raza.ai.data.SettingsStore
import com.raza.ai.device.ActionExecutor
import com.raza.ai.device.AndroidConnectivity
import com.raza.ai.device.AndroidContactResolver
import com.raza.ai.device.AndroidRegion
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

    /** Live microphone level (0..1) while listening. Used only to animate the waveform. */
    var levelSink: ((Float) -> Unit)? = null

    /** Set by the visible Activity: shows Android's own lock-screen check. */
    var authLauncher: ((ConfirmationManager.Pending) -> Unit)? = null

    private val connectivity = AndroidConnectivity(app)
    private val confirmations = ConfirmationManager()
    private val api = ApiClient({ ApiConfig(settings.apiBaseUrl, tokens.get()) }, UrlConnectionTransport())
    private val deviceInfo = AndroidDeviceInfo(app)
    private val keyguard: KeyguardManager? = app.getSystemService(KeyguardManager::class.java)
    private val stores = LocalStores(app)
    private val diskIo: ExecutorService = Executors.newSingleThreadExecutor()

    /** The user's own to-do list, saved notes and a log of what RAZA did on the phone. All local. */
    val tasks = TaskBook(Persistence.tasksFromJson(stores.tasks.read()))
    val notes = NoteBook(Persistence.notesFromJson(stores.notes.read()))
    val actionLog: MutableList<ActionLogEntry> = Persistence.logFromJson(stores.actionLog.read()).toMutableList()

    private val history = ConversationHistory(preamble = {
        notes.asContext()?.let { listOf(HistoryItem("user", it), HistoryItem("assistant", "Understood.")) } ?: emptyList()
    })
    private val engine = AssistantEngine(
        api = api,
        confirmations = confirmations,
        connectivity = connectivity,
        deviceInfo = deviceInfo,
        history = history,
        authAvailable = { keyguard?.isDeviceSecure == true },
        contacts = AndroidContactResolver(app),
        defaultCountryCode = { settings.defaultCountryCode.ifBlank { AndroidRegion(app).callingCode() } },
        directCalls = { settings.directCalls },
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

            override fun onLevel(rmsDb: Float) {
                levelSink?.invoke(((rmsDb + 2f) / 12f).coerceIn(0f, 1f))
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
        loadSavedConversation()
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
                    if (d === ConfirmationManager.Decision.Rejected) logAction(open.action.describe(), false, "Cancelled by you.")
                    if (d === ConfirmationManager.Decision.Expired) logAction(open.action.describe(), false, "Timed out. Nothing was done.")
                    handleDecision(d, byVoice)
                    return
                }
            }
        }

        LocalCommands.parse(text)?.let {
            handleLocal(it)
            return
        }

        if (!settings.cloudAiEnabled && LocalCommandParser.parse(text) is ParseResult.NoMatch) {
            say("Cloud AI is switched off in Settings, so I can only run on-device commands, tasks and notes.")
            return
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
            is Outcome.Run -> runAction(outcome.action)
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
        val p = confirmations.current()
        handleDecision(confirmations.reject(), false)
        if (p != null) logAction(p.action.describe(), false, "Cancelled by you.")
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

    /** Actions can wait on Android (and later on other apps' screens), so they never run on the main thread. */
    private fun runAction(action: DeviceAction) {
        setPhase(Phase.PROCESSING)
        io.execute {
            val result = executor.execute(action)
            main.post {
                logAction(action.describe(), result.ok, result.message)
                say(result.message)
            }
        }
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

    /** Plays a short sample so the user can check the voice, volume and installed language data. */
    fun sampleVoice(urdu: Boolean) {
        val text = if (urdu) "السلام علیکم، میں رضا اے آئی ہوں۔ آواز کی جانچ۔" else "Hello, I am RAZA AI. This is a voice check."
        if (!speakText(text, listen = false, force = true)) setPhase(Phase.IDLE)
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
    private fun speakText(text: String, listen: Boolean, force: Boolean = false): Boolean {
        if (!settings.voiceReplies && !force) return false
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
        if (!settings.cloudAiEnabled) {
            connection = ConnectionState.Disabled
            notifyChanged()
            return
        }
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

    // ---- tasks, notes, memory ----

    private fun handleLocal(c: LocalCommand) {
        when (c) {
            is LocalCommand.AddTask -> when (val r = tasks.add(c.title)) {
                is AddResult.Added -> {
                    saveTasks()
                    say("Added to your task list: ${r.item.title}. For a timed reminder, ask me to set an alarm.")
                }
                is AddResult.Rejected -> say(r.reason)
            }
            is LocalCommand.Remember -> when (val r = notes.add(c.note)) {
                is AddResult.Added -> {
                    saveNotes()
                    say("Okay, I'll remember that. You can view or delete notes in Settings.")
                }
                is AddResult.Rejected -> say(r.reason)
            }
            is LocalCommand.CompleteTask -> {
                val done = tasks.completeByNumber(c.number)
                if (done != null) {
                    saveTasks()
                    say("Marked done: ${done.title}.")
                } else {
                    say("I couldn't find open task number ${c.number}.")
                }
            }
            LocalCommand.ShowTasks -> say(LocalCommands.speakTasks(tasks.open()))
            LocalCommand.ShowNotes -> say(LocalCommands.speakNotes(notes.all()))
        }
    }

    /** Returns an error message, or null on success. */
    fun addTask(title: String): String? = when (val r = tasks.add(title)) {
        is AddResult.Added -> { saveTasks(); notifyChanged(); null }
        is AddResult.Rejected -> r.reason
    }

    fun setTaskDone(id: Long, done: Boolean) { tasks.setDone(id, done); saveTasks(); notifyChanged() }
    fun removeTask(id: Long) { tasks.remove(id); saveTasks(); notifyChanged() }
    fun clearDoneTasks() { tasks.clearDone(); saveTasks(); notifyChanged() }

    /** Returns an error message, or null on success. */
    fun addNote(text: String): String? = when (val r = notes.add(text)) {
        is AddResult.Added -> { saveNotes(); notifyChanged(); null }
        is AddResult.Rejected -> r.reason
    }

    fun removeNote(id: Long) { notes.remove(id); saveNotes(); notifyChanged() }

    private fun saveTasks() {
        val json = Persistence.tasksToJson(tasks.all())
        diskIo.execute { stores.tasks.write(json) }
    }

    private fun saveNotes() {
        val json = Persistence.notesToJson(notes.all())
        diskIo.execute { stores.notes.write(json) }
    }

    private fun logAction(summary: String, ok: Boolean, message: String) {
        actionLog.add(ActionLogEntry(System.currentTimeMillis(), summary, ok, message))
        while (actionLog.size > MAX_LOG) actionLog.removeAt(0)
        val json = Persistence.logToJson(actionLog)
        diskIo.execute { stores.actionLog.write(json) }
    }

    private fun saveChat() {
        if (!settings.rememberConversations) return
        val records = messages.filter { it.role != Role.SYSTEM }
            .map { ChatRecord(if (it.role == Role.USER) "user" else "assistant", it.text, it.time) }
        val json = Persistence.chatToJson(Persistence.newest(records, MAX_SAVED_CHAT))
        diskIo.execute { stores.chat.write(json) }
    }

    private fun loadSavedConversation() {
        if (!settings.rememberConversations) return
        val saved = Persistence.chatFromJson(stores.chat.read())
        for (r in saved) {
            messages.add(ChatMessage(if (r.role == "user") Role.USER else Role.ASSISTANT, r.text, null, r.time))
        }
        history.restore(saved.map { HistoryItem(it.role, it.text) })
    }

    // ---- data controls (Settings > Privacy) ----

    fun deleteConversation() {
        clearConversation()
        diskIo.execute { stores.chat.delete() }
    }

    fun deleteTasks() { tasks.clear(); diskIo.execute { stores.tasks.delete() }; notifyChanged() }
    fun deleteNotes() { notes.clear(); diskIo.execute { stores.notes.delete() }; notifyChanged() }
    fun deleteActionLog() { actionLog.clear(); diskIo.execute { stores.actionLog.delete() }; notifyChanged() }

    /** Wipes everything this app stores: chat, tasks, notes, log, settings and the saved token. */
    fun eraseEverything() {
        speaker.stop()
        voice.cancel()
        confirmations.reject()
        deleteConversation()
        deleteTasks()
        deleteNotes()
        deleteActionLog()
        tokens.clear()
        settings.clearAll()
        refreshConnection()
    }

    fun storedDataSummary(): String {
        val chatCount = messages.count { it.role != Role.SYSTEM }
        return buildString {
            appendLine("Saved on this phone only:")
            appendLine("- Chat messages: $chatCount" + if (settings.rememberConversations) "" else " (saving is off)")
            appendLine("- Tasks: ${tasks.all().size}")
            appendLine("- Notes you asked me to remember: ${notes.all().size}")
            appendLine("- Action log entries: ${actionLog.size}")
            appendLine("- Settings: language, voice, wake phrase, backend address")
            append("- Backend access token: " + if (tokens.get().isNotEmpty()) "saved (encrypted)" else "not saved")
        }
    }

    /** Label/value rows for the dashboard. */
    fun systemInfo(): List<Pair<String, String>> = listOf(
        "Battery" to (deviceInfo.batteryText() ?: "unknown"),
        "Network" to if (connectivity.isOnline()) "online" else "offline",
        "Time" to deviceInfo.timeText(),
        "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "App" to "RAZA AI ${BuildConfig.VERSION_NAME}",
    )

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
        if (m.role != Role.SYSTEM) saveChat()
        notifyChanged()
    }

    private fun system(text: String) = addMessage(ChatMessage(Role.SYSTEM, text))

    private fun systemOnce(key: String, text: String) {
        if (warned.add(key)) system(text)
    }

    companion object {
        private const val MAX_MESSAGES = 200
        private const val MAX_SAVED_CHAT = 100
        private const val MAX_LOG = 100
    }
}
