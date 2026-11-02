package com.raza.ai.ui

import android.app.AlertDialog
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import com.raza.ai.AssistantSession
import com.raza.ai.WakeWordService
import com.raza.ai.core.ConnectionState
import com.raza.ai.core.Languages
import com.raza.ai.core.UrlValidator
import com.raza.ai.core.WakePhraseValidator
import com.raza.ai.core.WakeState
import com.raza.ai.voice.WakeHub

class SettingsScreen(ui: Ui, session: AssistantSession, host: Host) : Screen(ui, session, host) {
    private val content = ui.vertical().apply { setPadding(0, ui.dp(8), 0, ui.dp(24)) }
    override val root: View = ScrollView(ui.ctx).apply { addView(content) }

    // Fields that must survive a redraw while the user is typing.
    private val urlField = field("https://your-backend.example.com", false)
    private val tokenField = field("Paste access token", true)
    private val phraseField = field("hey raza  (or: ہیلو رضا)", false)

    private fun field(hintText: String, secret: Boolean) = EditText(ui.ctx).apply {
        hint = hintText
        setHintTextColor(Theme.muted)
        setTextColor(Theme.text)
        setSingleLine(true)
        if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        background = ui.shape(Theme.panel2, Theme.cyanDim, 12f)
        setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
    }

    private var filled = false

    override fun onShown() {
        filled = false
        refresh()
    }

    override fun refresh() {
        listOf(urlField, tokenField, phraseField).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        if (!filled) {
            urlField.setText(host.settings.apiBaseUrl)
            tokenField.setText("")
            tokenField.hint = if (host.tokens.get().isNotEmpty()) "Saved (encrypted). Leave empty to keep it." else "Paste access token"
            phraseField.setText(host.settings.wakePhrase)
            filled = true
        }
        content.removeAllViews()
        content.addView(ui.text("SETTINGS", 18f, Theme.cyan, true).apply {
            letterSpacing = 0.2f
            setPadding(ui.dp(16), ui.dp(4), 0, ui.dp(4))
        })
        content.addView(wakeCard(), ui.cardParams())
        content.addView(voiceCard(), ui.cardParams())
        content.addView(ownerCard(), ui.cardParams())
        content.addView(permissionsCard(), ui.cardParams())
        content.addView(cloudCard(), ui.cardParams())
        content.addView(privacyCard(), ui.cardParams())
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(ui.ctx).apply {
        text = label
        isChecked = checked
        setTextColor(Theme.text)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    // ---------- wake word ----------
    private fun wakeCard(): View {
        val c = ui.card("WAKE WORD & VOICE ACTIVATION")
        val running = WakeHub.state != WakeState.OFF && WakeHub.state != WakeState.ERROR
        c.addView(switchRow("Voice activation (keeps the microphone on)", running) { want ->
            if (want != running) host.toggleWake()
        })
        c.addView(
            ui.row(
                "Status",
                when (WakeHub.state) {
                    WakeState.OFF -> "off"
                    WakeState.STARTING -> "starting"
                    WakeState.LISTENING -> "listening"
                    WakeState.COMMAND_LISTENING -> "heard you"
                    WakeState.PAUSED -> "paused"
                    WakeState.ERROR -> "error: ${WakeHub.error ?: "unknown"}"
                },
                if (WakeHub.state == WakeState.ERROR) Theme.red else Theme.text,
            ),
        )
        c.addView(ui.text("Wake phrase", 12f, Theme.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
        c.addView(phraseField)
        val presets = ui.horizontal()
        WakePhraseValidator.DEFAULTS.forEach { p -> presets.addView(ui.button(p) { phraseField.setText(p) }, ui.weighted(2)) }
        c.addView(presets)
        val actions = ui.horizontal()
        actions.addView(ui.button("Save phrase", accent = true) { savePhrase() }, ui.weighted(2))
        actions.addView(ui.button(if (WakeHub.state == WakeState.PAUSED) "Resume" else "Pause") { host.toggleWakePause() }, ui.weighted(2))
        c.addView(actions)
        c.addView(ui.note("A new phrase takes effect on the next listening cycle. Use 2 words (like \"hey raza\") to avoid false triggers. Urdu phrases switch the recogniser to Urdu."))
        c.addView(
            ui.note(
                "Honest limits: this uses Android's speech recogniser, not a low-power hotword chip, so it uses more battery and can miss the phrase in noise. " +
                    "It can only be started while the app is open, stops after a reboot, and some phone makers' battery managers may kill it. " +
                    "While it runs you will always see a notification and Android's microphone indicator.",
            ),
        )
        return c
    }

    private fun savePhrase() {
        when (val r = WakePhraseValidator.validate(phraseField.text.toString())) {
            is WakePhraseValidator.Result.Rejected -> host.toast(r.reason)
            is WakePhraseValidator.Result.Ok -> {
                host.settings.wakePhrase = r.phrase
                host.toast("Wake phrase saved: ${r.phrase}")
                refresh()
            }
        }
    }

    // ---------- voice & speech ----------
    private fun voiceCard(): View {
        val c = ui.card("VOICE & LANGUAGE")
        c.addView(ui.text("Language", 12f, Theme.muted))
        val group = RadioGroup(ui.ctx)
        Languages.ALL.forEachIndexed { i, l ->
            group.addView(RadioButton(ui.ctx).apply {
                text = if (l == Languages.URDU) "Urdu (اردو)" else l
                id = 3000 + i
                isChecked = host.settings.language == l
                setTextColor(Theme.text)
            })
        }
        group.setOnCheckedChangeListener { _, id -> host.settings.language = Languages.ALL.getOrElse(id - 3000) { Languages.BOTH } }
        c.addView(group)
        c.addView(ui.note("Speech recognition listens in one language at a time: English for \"English\" and \"English + Urdu\", Urdu for \"Urdu\". Replies are spoken in the language they are written in."))
        c.addView(switchRow("Speak replies aloud", host.settings.voiceReplies) { host.settings.voiceReplies = it })
        c.addView(ui.text("Spoken reply volume", 12f, Theme.muted).apply { setPadding(0, ui.dp(8), 0, 0) })
        c.addView(SeekBar(ui.ctx).apply {
            max = 100
            progress = host.settings.speechVolume
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) host.settings.speechVolume = progress
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })
        c.addView(ui.note("This scales the assistant's own voice. The phone's media volume still applies; turn it up with the volume keys."))
        c.addView(switchRow("Prefer on-device speech recognition", host.settings.preferOfflineSpeech) { host.settings.preferOfflineSpeech = it })
        val test = ui.horizontal()
        test.addView(ui.button("Test English voice") { session.sampleVoice(false) }, ui.weighted(2))
        test.addView(ui.button("Test Urdu voice") { session.sampleVoice(true) }, ui.weighted(2))
        c.addView(test)
        c.addView(ui.note("The voice is a lowered-pitch voice, and a male-named voice if your phone's text-to-speech engine has one. It is not a copy of any real or fictional person. Urdu needs Urdu voice data installed in Android Settings > Text-to-speech."))
        return c
    }

    // ---------- owner security ----------
    private fun ownerCard(): View {
        val c = ui.card("OWNER SECURITY")
        c.addView(ui.row("Owner voice enrollment", "NOT AVAILABLE", Theme.amber))
        c.addView(
            ui.note(
                "Speech recognition turns your words into text; it cannot prove who is speaking, and Android has no built-in speaker-verification API. " +
                    "A recording of you could also fool a voice check, so RAZA does not pretend to offer one.",
            ),
        )
        val lock = host.screenLockSet()
        c.addView(ui.row("Phone screen lock", if (lock) "SET" else "NOT SET", if (lock) Theme.green else Theme.amber))
        c.addView(
            ui.note(
                if (lock) "Instead, calls and texts need your phone's own unlock check (PIN, pattern, password or fingerprint) after you say yes. RAZA never sees or stores it."
                else "Set a screen lock in Android Settings. Without one, calls and texts only need your yes/no confirmation, so anyone holding the phone could approve them.",
            ),
        )
        c.addView(ui.button("Open security settings") { host.openSecuritySettings() })
        c.addView(ui.note("RAZA cannot unlock your phone and never tries to. On the lock screen it does only what Android itself allows."))
        return c
    }

    // ---------- permissions ----------
    private fun permissionsCard(): View {
        val c = ui.card("PERMISSIONS")
        val mic = host.hasMic()
        c.addView(ui.row("Microphone", if (mic) "granted" else if (host.micBlocked()) "blocked" else "not granted", if (mic) Theme.green else Theme.amber))
        c.addView(ui.note("Needed for voice commands and the wake phrase. Requested only when you use them."))
        if (!mic) {
            c.addView(ui.button(if (host.micBlocked()) "Open app settings" else "Grant microphone", accent = true) {
                if (host.micBlocked()) host.openAppSettings() else host.withMic { host.toast("Microphone allowed.") }
            })
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val n = host.notificationsGranted()
            c.addView(ui.row("Notifications", if (n) "granted" else "not granted", if (n) Theme.green else Theme.amber))
            c.addView(ui.note("Shows the \"microphone is on\" notification. Without it Android still shows its microphone indicator."))
            if (!n) c.addView(ui.button("Open app settings") { host.openAppSettings() })
        }
        val b = host.batteryUnrestricted()
        c.addView(ui.row("Battery optimization", if (b) "unrestricted" else "optimized", if (b) Theme.green else Theme.muted))
        c.addView(ui.note("Optional. Setting this to unrestricted can help the wake listener survive on phones with aggressive battery managers. It is never required."))
        c.addView(ui.button("Open battery settings") { host.openBatterySettings() })
        c.addView(ui.row("Accessibility service", "not used", Theme.text))
        c.addView(ui.note("RAZA does not use Accessibility, hidden APIs, or any way around Android's security. Calls and texts only open the dialer or message screen; you press call or send."))
        return c
    }

    // ---------- cloud ----------
    private fun cloudCard(): View {
        val c = ui.card("ONLINE / OFFLINE")
        c.addView(switchRow("Use cloud AI when online", host.settings.cloudAiEnabled) {
            host.settings.cloudAiEnabled = it
            session.refreshConnection()
        })
        val conn = session.connection
        c.addView(
            ui.row(
                "Status",
                conn.label,
                when (conn) {
                    is ConnectionState.Online -> Theme.green
                    is ConnectionState.Unreachable -> Theme.red
                    else -> Theme.amber
                },
            ),
        )
        c.addView(ui.text("Backend address (HTTPS)", 12f, Theme.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
        c.addView(urlField)
        c.addView(ui.text("Access token", 12f, Theme.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
        c.addView(tokenField)
        val row = ui.horizontal()
        row.addView(ui.button("Save & test connection", accent = true) { saveCloud() }, ui.weighted(2))
        row.addView(ui.button("Clear token") { host.tokens.clear(); filled = false; refresh(); host.toast("Token removed.") }, ui.weighted(2))
        c.addView(row)
        c.addView(ui.note("Offline, RAZA still handles time, date, battery, opening apps and settings, alarms, volume, media, calls/texts (opens the dialer/composer), tasks and notes. It never pretends cloud AI answered while offline."))
        c.addView(ui.note("AI provider keys stay on your backend server. This app only holds the backend address and an access token (in Android's encrypted storage)."))
        return c
    }

    private fun saveCloud() {
        val url = urlField.text.toString().trim()
        if (url.isNotEmpty() && UrlValidator.normalizeBase(url) == null) {
            host.toast("The backend address must be a valid https:// address.")
            return
        }
        val token = tokenField.text.toString()
        if (token.isNotBlank() && !host.tokens.set(token)) {
            host.toast("Secure storage is unavailable on this phone, so the token was NOT saved.")
            return
        }
        host.settings.apiBaseUrl = url
        filled = false
        session.refreshConnection()
        host.toast("Saved. Checking the connection...")
        refresh()
    }

    // ---------- privacy ----------
    private fun privacyCard(): View {
        val c = ui.card("PRIVACY & DATA")
        c.addView(switchRow("Remember conversations on this phone", host.settings.rememberConversations) { on ->
            host.settings.rememberConversations = on
            if (!on) session.deleteConversation()
        })
        c.addView(ui.note("Chat history is stored only on this phone, and recent turns are sent to your backend as context when you chat online."))
        c.addView(ui.button("View what is stored") {
            AlertDialog.Builder(ui.ctx).setTitle("Stored on this phone").setMessage(session.storedDataSummary()).setPositiveButton("OK", null).show()
        }, ui.lp().apply { topMargin = ui.dp(4) })
        c.addView(ui.button("Privacy & safety details") { showPrivacy() }, ui.lp().apply { topMargin = ui.dp(4) })
        val del1 = ui.horizontal()
        del1.addView(ui.button("Delete chat") { confirm("Delete saved chat?") { session.deleteConversation() } }, ui.weighted(2))
        del1.addView(ui.button("Delete tasks") { confirm("Delete all tasks?") { session.deleteTasks() } }, ui.weighted(2))
        c.addView(del1)
        val del2 = ui.horizontal()
        del2.addView(ui.button("Delete notes") { confirm("Delete all notes?") { session.deleteNotes() } }, ui.weighted(2))
        del2.addView(ui.button("Delete action log") { confirm("Delete the action log?") { session.deleteActionLog() } }, ui.weighted(2))
        c.addView(del2)
        c.addView(ui.button("Erase everything", accent = true) {
            confirm("Erase ALL RAZA data on this phone?", "Chat, tasks, notes, log, settings and the saved token will be deleted, and wake listening will stop.") {
                WakeWordService.stop(ui.ctx)
                session.eraseEverything()
                filled = false
                host.toast("Everything was erased.")
                refresh()
            }
        }, ui.lp().apply { topMargin = ui.dp(6) })
        return c
    }

    private fun confirm(title: String, message: String = "This cannot be undone.", onYes: () -> Unit) {
        AlertDialog.Builder(ui.ctx).setTitle(title).setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> onYes(); refresh() }
            .show()
    }

    private fun showPrivacy() {
        val text = """
            Microphone: used only when you tap MIC, or while voice activation is on. A notification and Android's microphone indicator are shown whenever it is on.

            Wake phrase: runs on Android's speech recogniser. It asks for on-device recognition, but Android does not guarantee that. It never restarts after a reboot.

            Stored on this phone: settings, optional chat history, your tasks, notes you asked me to remember, and a log of device actions. The backend token is in Android's encrypted storage. You can view and delete all of it here.

            Sent over the network: only text you type or say (after speech-to-text), recent chat turns and your saved notes, to the backend address you set. Your backend then calls its AI provider. Nothing is sent when cloud AI is off, no backend is set, or you are offline. Audio is not uploaded by this app; Android's speech service may process it.

            Safety: every device-changing command asks yes/no first. Calls and texts only open the dialer or message screen. They also need your phone's screen lock when one is set. RAZA never sees or stores your PIN, pattern or password and cannot unlock the phone.

            Owner voice check: not available (see Owner security).
        """.trimIndent()
        AlertDialog.Builder(ui.ctx).setTitle("Privacy & safety").setMessage(text).setPositiveButton("OK", null).show()
    }
}
