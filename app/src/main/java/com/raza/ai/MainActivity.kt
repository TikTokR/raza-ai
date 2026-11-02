package com.raza.ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.raza.ai.core.Languages
import com.raza.ai.core.MicStatusResolver
import com.raza.ai.core.UrlValidator
import com.raza.ai.core.WakePhraseValidator
import com.raza.ai.core.WakeState
import com.raza.ai.voice.WakeHub

/** Stage B screen: the existing dark command look, now wired to the real assistant core. */
class MainActivity : Activity() {
    private val bg = Color.rgb(5, 12, 28)
    private val panel = Color.rgb(12, 27, 52)
    private val cyan = Color.rgb(42, 220, 255)
    private val warn = Color.rgb(255, 196, 61)

    private lateinit var razaApp: RazaApp
    private val session get() = razaApp.session
    private val settings get() = razaApp.settings

    private lateinit var log: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var micStatus: TextView
    private lateinit var cloudStatus: TextView
    private lateinit var wakeStatus: TextView
    private lateinit var phaseText: TextView
    private lateinit var partialText: TextView
    private lateinit var confirmBar: LinearLayout
    private lateinit var confirmText: TextView
    private lateinit var wakeBtn: Button
    private lateinit var pauseBtn: Button

    private val main = Handler(Looper.getMainLooper())
    private val onChange: () -> Unit = { render() }
    private val tick = Runnable { render() }
    private var afterPermission: (() -> Unit)? = null
    private var micPermanentlyDenied = false
    private var shownMessages = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        razaApp = application as RazaApp
        buildUi()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(WakeWordService.EXTRA_FROM_WAKE, false) == true) {
            i.removeExtra(WakeWordService.EXTRA_FROM_WAKE)
            withMic { session.startListening() }
        }
    }

    override fun onStart() {
        super.onStart()
        session.addListener(onChange)
        WakeHub.addListener(onChange)
        WakeHub.wakeHandler = { cmd -> session.handleWake(cmd) }
        session.authLauncher = { launchOwnerCheck() }
        session.refreshConnection()
        render()
    }

    override fun onStop() {
        session.removeListener(onChange)
        WakeHub.removeListener(onChange)
        WakeHub.wakeHandler = null
        session.authLauncher = null
        main.removeCallbacks(tick)
        super.onStop()
    }

    // ---------------- UI ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 11f
        setTextColor(cyan)
        setBackgroundColor(panel)
        setOnClickListener { onClick() }
    }

    private fun rowParams() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(16), dp(12), dp(8))
            setBackgroundColor(bg)
        }
        outer.addView(label("R A Z A   A I", 26f, cyan, true).apply { gravity = Gravity.CENTER })
        outer.addView(label("PERSONAL VOICE ASSISTANT", 10f, Color.LTGRAY).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(8)) })

        micStatus = label("", 11f, cyan).apply { setPadding(dp(8), dp(6), dp(8), dp(2)); setBackgroundColor(panel) }
        cloudStatus = label("", 11f, cyan).apply { setPadding(dp(8), dp(2), dp(8), dp(2)); setBackgroundColor(panel) }
        wakeStatus = label("", 11f, cyan).apply { setPadding(dp(8), dp(2), dp(8), dp(6)); setBackgroundColor(panel) }
        outer.addView(micStatus)
        outer.addView(cloudStatus)
        outer.addView(wakeStatus)

        phaseText = label("", 12f, cyan, true).apply { setPadding(dp(8), dp(6), dp(8), dp(2)) }
        outer.addView(phaseText)

        log = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        scroll = ScrollView(this).apply { addView(log) }
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        partialText = label("", 13f, Color.LTGRAY).apply { setPadding(dp(8), dp(2), dp(8), dp(2)) }
        outer.addView(partialText)

        confirmBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(40, 32, 8))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = View.GONE
        }
        confirmText = label("", 14f, warn, true)
        confirmBar.addView(confirmText)
        val yn = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        yn.addView(button("YES, DO IT") { session.confirmYes() }, rowParams())
        yn.addView(button("NO, CANCEL") { session.confirmNo() }, rowParams())
        confirmBar.addView(yn)
        outer.addView(confirmBar)

        input = EditText(this).apply {
            hint = "Ask RAZA anything..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setBackgroundColor(panel)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val send = { val t = input.text.toString(); input.setText(""); session.submit(t) }
        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        inputRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(button("SEND") { send() })
        inputRow.addView(button("MIC") { onMicTapped() })
        outer.addView(inputRow)

        wakeBtn = button("WAKE: OFF") { toggleWake() }
        pauseBtn = button("PAUSE WAKE") { toggleWakePause() }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(wakeBtn, rowParams())
        row1.addView(pauseBtn, rowParams())
        row1.addView(button("STOP VOICE") { session.stopSpeaking(); session.stopListening() }, rowParams())
        outer.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row2.addView(button("REPEAT") { session.repeatLast() }, rowParams())
        row2.addView(button("PRIVACY") { showPrivacy() }, rowParams())
        row2.addView(button("SETTINGS") { showSettings() }, rowParams())
        outer.addView(row2)

        setContentView(outer)
    }

    private fun render() {
        val granted = hasMic()
        val mic = MicStatusResolver.label(MicStatusResolver.resolve(granted, WakeHub.state, session.phase == AssistantSession.Phase.LISTENING))
        micStatus.text = if (micPermanentlyDenied && !granted) "$mic (blocked: enable it in Android Settings)" else mic
        cloudStatus.text = session.connection.label
        wakeStatus.text = when (WakeHub.state) {
            WakeState.OFF -> "WAKE PHRASE: off"
            WakeState.STARTING -> "WAKE PHRASE: starting..."
            WakeState.LISTENING -> "WAKE PHRASE: say \"${settings.wakePhrase}\""
            WakeState.COMMAND_LISTENING -> "WAKE PHRASE: heard you"
            WakeState.PAUSED -> "WAKE PHRASE: paused"
            WakeState.ERROR -> "WAKE PHRASE ERROR: ${WakeHub.error ?: "unknown"}"
        }
        phaseText.text = when (session.phase) {
            AssistantSession.Phase.IDLE -> "IDLE"
            AssistantSession.Phase.LISTENING -> "LISTENING..."
            AssistantSession.Phase.PROCESSING -> "PROCESSING..."
            AssistantSession.Phase.SPEAKING -> "SPEAKING..."
        }
        partialText.text = session.partial
        wakeBtn.text = if (WakeHub.state == WakeState.OFF || WakeHub.state == WakeState.ERROR) "WAKE: OFF" else "WAKE: ON"
        pauseBtn.text = if (WakeHub.state == WakeState.PAUSED) "RESUME WAKE" else "PAUSE WAKE"

        val pending = session.pending()
        main.removeCallbacks(tick)
        if (pending != null) {
            confirmBar.visibility = View.VISIBLE
            val auth = if (pending.needsAuth && !pending.authenticated) "\nYou will be asked to unlock your phone first." else ""
            confirmText.text = "CONFIRM: ${pending.action.describe()}$auth"
            main.postDelayed(tick, 1000) // pick up the 30 s expiry
        } else {
            confirmBar.visibility = View.GONE
        }

        if (shownMessages != session.messages.size) {
            shownMessages = session.messages.size
            log.removeAllViews()
            for (m in session.messages) {
                val (who, color) = when (m.role) {
                    AssistantSession.Role.USER -> "YOU" to Color.WHITE
                    AssistantSession.Role.ASSISTANT -> "RAZA" to cyan
                    AssistantSession.Role.SYSTEM -> "SYSTEM" to warn
                }
                val tv = label("$who\n${m.text}", 15f, color).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); setBackgroundColor(panel) }
                log.addView(tv, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(3), 0, dp(3)) })
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ---------------- permissions & mic ----------------

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun withMic(action: () -> Unit) {
        if (hasMic()) {
            action()
            return
        }
        afterPermission = action
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(perms.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val granted = hasMic()
        micPermanentlyDenied = !granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        val action = afterPermission
        afterPermission = null
        when {
            granted -> action?.invoke()
            micPermanentlyDenied -> AlertDialog.Builder(this)
                .setTitle("Microphone blocked")
                .setMessage("Android will not ask again. To use voice, open App settings > Permissions > Microphone and allow it.")
                .setNegativeButton("Not now", null)
                .setPositiveButton("Open App settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }
                .show()
            else -> Toast.makeText(this, "Microphone permission is needed for voice.", Toast.LENGTH_LONG).show()
        }
        render()
    }

    private fun onMicTapped() {
        if (session.phase == AssistantSession.Phase.LISTENING) session.stopListening() else withMic { session.startListening() }
    }

    // ---------------- wake service ----------------

    private fun toggleWake() {
        val s = WakeHub.state
        if (s == WakeState.OFF || s == WakeState.ERROR) {
            withMic {
                try {
                    settings.wakeEnabled = true
                    WakeWordService.start(this)
                } catch (e: Exception) {
                    Toast.makeText(this, "Android blocked the background microphone: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            settings.wakeEnabled = false
            WakeWordService.stop(this)
        }
    }

    private fun toggleWakePause() {
        when (WakeHub.state) {
            WakeState.PAUSED -> WakeWordService.userResume(this)
            WakeState.LISTENING, WakeState.COMMAND_LISTENING -> WakeWordService.userPause(this)
            else -> Toast.makeText(this, "Turn the wake phrase on first.", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------------- owner check (Android's own screen lock) ----------------

    @Suppress("DEPRECATION")
    private fun launchOwnerCheck() {
        val km = getSystemService(KeyguardManager::class.java)
        val i = km?.createConfirmDeviceCredentialIntent("RAZA AI", "Confirm it's you to continue.")
        if (i == null) {
            session.onAuthResult(false)
            return
        }
        startActivityForResult(i, REQ_AUTH)
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_AUTH) session.onAuthResult(resultCode == RESULT_OK)
    }

    // ---------------- dialogs ----------------

    private fun showPrivacy() {
        val text = """
            Microphone: used only when you tap MIC, or while the wake phrase is switched on. A notification and Android's microphone indicator are shown whenever it is on.

            Wake phrase: runs on Android's speech recogniser, not a low-power hotword chip. It asks for on-device recognition, but Android does not guarantee that, and it drains more battery. It stops if you turn it off, and is never restarted after a reboot.

            What is stored on this phone: your settings. The backend access token is kept in Android's encrypted storage. Nothing is saved to the cloud by this app.

            What goes over the network: only the text you type or say (after speech-to-text), plus short recent chat context, sent to the backend address you set. Your backend then calls its AI provider. Nothing is sent if no backend is set or you are offline.

            Safety: calls and texts only open the dialer or message screen; you press call or send. Every device-changing command asks YES/NO first. Calls and texts also need your phone's screen lock. RAZA never sees or stores your PIN, pattern or password and cannot unlock the phone.

            Owner voice check: not available. Voice recognition cannot prove who is speaking, so your screen lock is used instead.
        """.trimIndent()
        AlertDialog.Builder(this).setTitle("Privacy & safety").setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun showSettings() {
        val pad = dp(16)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun field(title: String, value: String, hint: String, secret: Boolean = false): EditText {
            form.addView(TextView(this).apply { text = title; setPadding(0, dp(10), 0, 0) })
            return EditText(this).apply {
                setText(value)
                this.hint = hint
                setSingleLine(true)
                if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }.also { form.addView(it) }
        }

        val url = field("Backend address (HTTPS)", settings.apiBaseUrl, "https://your-backend.example.com")
        val token = field(
            "Backend access token",
            "",
            if (razaApp.tokens.get().isNotEmpty()) "Saved. Leave empty to keep it." else "Paste token",
            secret = true,
        )
        val wake = field("Wake phrase", settings.wakePhrase, "hey raza  (or: ہیلو رضا)")

        form.addView(TextView(this).apply { text = "Language"; setPadding(0, dp(10), 0, 0) })
        val group = RadioGroup(this)
        Languages.ALL.forEachIndexed { i, l ->
            group.addView(RadioButton(this).apply { text = l; id = 1000 + i; isChecked = settings.language == l })
        }
        form.addView(group)

        val voiceSwitch = Switch(this).apply { text = "Speak replies aloud"; isChecked = settings.voiceReplies }
        form.addView(voiceSwitch)
        form.addView(TextView(this).apply { text = "Spoken reply volume"; setPadding(0, dp(10), 0, 0) })
        val vol = SeekBar(this).apply { max = 100; progress = settings.speechVolume }
        form.addView(vol)
        val offline = Switch(this).apply { text = "Prefer on-device speech recognition"; isChecked = settings.preferOfflineSpeech }
        form.addView(offline)
        form.addView(TextView(this).apply {
            text = "The voice is a deep male voice only if your phone's text-to-speech engine has one installed. Urdu speech needs Urdu voice data installed in Android settings."
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        })

        val clear = Button(this).apply {
            text = "Clear saved token"
            setOnClickListener { razaApp.tokens.clear(); Toast.makeText(this@MainActivity, "Token cleared.", Toast.LENGTH_SHORT).show() }
        }
        form.addView(clear)
        val wipe = Button(this).apply {
            text = "Clear chat on screen"
            setOnClickListener { session.clearConversation() }
        }
        form.addView(wipe)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(ScrollView(this).apply { addView(form) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                saveSettings(url.text.toString(), token.text.toString(), wake.text.toString(), group, voiceSwitch.isChecked, vol.progress, offline.isChecked)
            }
            .show()
    }

    private fun saveSettings(url: String, token: String, wake: String, group: RadioGroup, voice: Boolean, volume: Int, offline: Boolean) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isNotEmpty() && UrlValidator.normalizeBase(trimmedUrl) == null) {
            Toast.makeText(this, "The backend address must be a valid https:// address.", Toast.LENGTH_LONG).show()
            return
        }
        when (val r = WakePhraseValidator.validate(wake)) {
            is WakePhraseValidator.Result.Rejected -> {
                Toast.makeText(this, r.reason, Toast.LENGTH_LONG).show()
                return
            }
            is WakePhraseValidator.Result.Ok -> settings.wakePhrase = r.phrase
        }
        if (token.isNotBlank() && !razaApp.tokens.set(token)) {
            Toast.makeText(this, "Secure storage is unavailable on this phone, so the token was NOT saved.", Toast.LENGTH_LONG).show()
        }
        settings.apiBaseUrl = trimmedUrl
        val checked = group.checkedRadioButtonId - 1000
        settings.language = Languages.ALL.getOrElse(checked) { Languages.BOTH }
        settings.voiceReplies = voice
        settings.speechVolume = volume
        settings.preferOfflineSpeech = offline
        session.refreshConnection()
        Toast.makeText(this, "Saved. Restart the wake listener to use a new wake phrase.", Toast.LENGTH_LONG).show()
        render()
    }

    companion object {
        private const val REQ_PERMS = 40
        private const val REQ_AUTH = 41
    }
}
