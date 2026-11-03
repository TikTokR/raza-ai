package com.raza.ai.ui

import android.app.AlertDialog
import android.Manifest
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
import com.raza.ai.RazaAccessibilityService
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
    private val ccField = field("e.g. 92 (blank = SIM country)", false)

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
        listOf(urlField, tokenField, phraseField, ccField).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        if (!filled) {
            urlField.setText(host.settings.apiBaseUrl)
            tokenField.setText("")
            tokenField.hint = if (host.tokens.get().isNotEmpty()) "Saved (encrypted). Leave empty to keep it." else "Paste access token"
            phraseField.setText(host.settings.wakePhrase)
            ccField.setText(host.settings.defaultCountryCode)
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
        val c = ui.card("BACKGROUND LISTENING (WAKE PHRASE)")
        val ls = host.listenStatus()
        c.addView(ui.text(ls.label, 15f, Theme.severityColor(ls.severity), true))
        c.addView(ui.note(ls.detail))
        c.addView(ui.row("Microphone", if (ls.micIsOn) "ON (really capturing)" else "off", if (ls.micIsOn) Theme.green else Theme.text))
        if (WakeHub.engine.isNotEmpty()) c.addView(ui.row("Recogniser", WakeHub.engine))

        val controls = ui.horizontal()
        controls.addView(ui.button("START", accent = ls.canStart) { host.startWake() }.apply { isEnabled = ls.canStart }, ui.weighted(2))
        controls.addView(ui.button("STOP") { host.stopWake() }.apply { isEnabled = ls.canStop }, ui.weighted(2))
        controls.addView(ui.button("RESTART") { host.restartWake() }.apply { isEnabled = ls.canRestart }, ui.weighted(2))
        c.addView(controls)
        val paused = WakeHub.state == WakeState.PAUSED
        if (WakeHub.state == WakeState.LISTENING || WakeHub.state == WakeState.COMMAND_LISTENING || paused) {
            c.addView(ui.button(if (paused) "Resume listening" else "Pause listening (mic off)") { host.toggleWakePause() }, ui.lp().apply { topMargin = ui.dp(4) })
        }

        c.addView(ui.text("Wake phrase", 12f, Theme.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
        c.addView(phraseField)
        val presets = ui.horizontal()
        WakePhraseValidator.DEFAULTS.forEach { p -> presets.addView(ui.button(p) { phraseField.setText(p) }, ui.weighted(2)) }
        c.addView(presets)
        c.addView(ui.button("Save phrase", accent = true) { savePhrase() }, ui.lp().apply { topMargin = ui.dp(4) })
        c.addView(ui.note("A new phrase takes effect on the next listening cycle. Use 2 words (like \"hey raza\") to avoid false triggers. Urdu phrases switch the recogniser to Urdu."))

        c.addView(ui.text("HOW TO KEEP IT LISTENING (Android 13+)", 11f, Theme.cyan, true).apply { setPadding(0, ui.dp(10), 0, ui.dp(2)) })
        c.addView(
            ui.note(
                "1. Allow Microphone and Notifications (Settings > Permissions below). Without Notifications Android may hide the \"microphone is on\" notice.\n" +
                    "2. Tap START while RAZA AI is open. Android only lets a microphone service start while the app is on screen.\n" +
                    "3. Then press Home or remove RAZA from Recent Apps: listening continues, and the notification stays.\n" +
                    "4. Tap \"Allow background use\" below so Android's battery manager ends RAZA less often.\n" +
                    "5. On Xiaomi, Oppo, Vivo, Realme, Infinix, Tecno or Samsung, also allow Autostart / \"No restrictions\" for RAZA AI in the phone's own battery or app settings, and lock RAZA in Recent Apps if your phone offers that.",
            ),
        )
        c.addView(ui.button("Allow background use (battery)") { host.requestIgnoreBattery() })
        c.addView(
            ui.note(
                "Honest limits: this uses Android's speech recogniser, not a hotword chip, so it costs battery and can miss the phrase in noise. " +
                    "RAZA records, saves and uploads nothing; on Android 13+ it uses the on-device recogniser when your phone has one (the line above says which is active), otherwise Android's speech service may use the network. " +
                    "Force-stopping RAZA from Settings, a cleaner app or the battery manager stops listening, and Android will not let RAZA restart the microphone by itself afterwards (and not after a reboot). " +
                    "In those cases RAZA shows STOPPED here and posts a \"not listening, tap to turn on\" notification. A notification and Android's microphone indicator are always visible while it is on.",
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
        val contacts = host.has(Manifest.permission.READ_CONTACTS)
        c.addView(ui.row("Contacts", if (contacts) "granted" else "not granted", if (contacts) Theme.green else Theme.muted))
        c.addView(ui.note("Optional. Lets \"WhatsApp Ali ...\" find Ali's number. Contacts are searched only when you ask and are not stored or uploaded."))
        if (!contacts) c.addView(ui.button("Grant contacts") { host.ask(Manifest.permission.READ_CONTACTS) { refresh() } })
        val phone = host.has(Manifest.permission.CALL_PHONE)
        c.addView(ui.row("Phone (direct calls)", if (phone) "granted" else "not granted", if (phone) Theme.green else Theme.muted))
        c.addView(switchRow("Place calls directly (after confirmation and screen lock)", host.settings.directCalls) { on ->
            host.settings.directCalls = on
            if (on && !phone) host.ask(Manifest.permission.CALL_PHONE) { granted ->
                if (!granted) host.toast("Without the Phone permission RAZA will still only open the dialer.")
                refresh()
            }
        })
        c.addView(ui.note("Off (default): RAZA opens the dialer and you press call. On: RAZA calls only after you say yes and pass the screen-lock check."))
        c.addView(ui.text("Default country code for saved numbers", 12f, Theme.muted).apply { setPadding(0, ui.dp(8), 0, ui.dp(2)) })
        c.addView(ccField)
        c.addView(ui.button("Save country code") {
            host.settings.defaultCountryCode = ccField.text.toString()
            host.toast("Saved.")
        })
        c.addView(ui.note("WhatsApp needs the full international number. Numbers saved like 0300... get this code added. Digits only, e.g. 92."))
        val a11y = RazaAccessibilityService.isConnected()
        c.addView(ui.row("Accessibility service", if (a11y) "ON" else "off", if (a11y) Theme.green else Theme.muted))
        c.addView(ui.note("Optional. Lets RAZA press Back/Home/Recents, scroll and swipe (for example next TikTok video), press WhatsApp's Send for a message you confirmed, and tap the first YouTube result. It can only see WhatsApp, YouTube and TikTok screens, and saves or uploads nothing it sees."))
        c.addView(ui.button("Open accessibility settings", accent = !a11y) { host.openAccessibilitySettings() })
        c.addView(ui.note("To switch on: Accessibility > Downloaded apps (or Installed apps) > RAZA AI > On. If the switch is greyed out (Android 13+ for apps installed from a file): Settings > Apps > RAZA AI > the three dots at the top right > Allow restricted settings, then try again."))
        c.addView(ui.note("RAZA uses no hidden APIs and cannot unlock the phone. Calls and texts open the dialer or composer unless you switch on direct calls above."))
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

            Contacts: read only when you ask to message someone by name; not stored or uploaded. Accessibility (optional, off until you enable it): limited to WhatsApp, YouTube and TikTok screens; used to press Send for a message you confirmed, tap the first YouTube result, and press Back/Home/scroll/swipe; nothing it sees is saved or sent.

            Safety: every command that changes something or contacts someone asks yes/no first (Back, Home and scrolling run at once). Calls, texts and WhatsApp messages also need your phone's screen lock when one is set. Calls open the dialer unless you switch on direct calls. RAZA never sees or stores your PIN, pattern or password and cannot unlock the phone.

            Owner voice check: not available (see Owner security).
        """.trimIndent()
        AlertDialog.Builder(ui.ctx).setTitle("Privacy & safety").setMessage(text).setPositiveButton("OK", null).show()
    }
}
