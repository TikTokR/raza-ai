package com.raza.ai.ui

import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.raza.ai.AssistantSession
import com.raza.ai.AssistantSession.Phase
import com.raza.ai.AssistantSession.Role
import com.raza.ai.core.ConfirmationManager
import com.raza.ai.core.ConnectionState
import com.raza.ai.core.Languages
import com.raza.ai.core.MicStatusResolver
import com.raza.ai.core.WakeState
import com.raza.ai.voice.WakeHub

class AssistantScreen(ui: Ui, session: AssistantSession, host: Host) : Screen(ui, session, host) {
    private val orb = OrbView(ui.ctx)
    private val wave = WaveformView(ui.ctx)
    private val phaseText = ui.text("IDLE", 16f, Theme.cyan, true).apply { gravity = Gravity.CENTER; letterSpacing = 0.2f }
    private val micText = ui.text("", 11f, Theme.muted).apply { gravity = Gravity.CENTER }
    private val cloudText = ui.text("", 11f, Theme.muted).apply { gravity = Gravity.CENTER }
    private val wakeText = ui.text("", 11f, Theme.muted).apply { gravity = Gravity.CENTER }
    private val log = ui.vertical().apply { setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4)) }
    private val scroll = ScrollView(ui.ctx).apply { addView(log) }
    private val partial = ui.text("", 13f, Theme.muted).apply { setPadding(ui.dp(14), ui.dp(2), ui.dp(14), ui.dp(2)) }
    private val confirmText = ui.text("", 14f, Theme.amber, true)
    private val confirmBar = ui.vertical().apply {
        background = ui.shape(android.graphics.Color.rgb(44, 34, 8), Theme.amber, 14f)
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
        visibility = View.GONE
    }
    private val input = EditText(ui.ctx).apply {
        hint = "Type or tap the mic..."
        setHintTextColor(Theme.muted)
        setTextColor(Theme.text)
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_SEND
        background = ui.shape(Theme.panel, Theme.cyanDim, 12f)
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
    }
    private val micButton = ui.button("MIC", accent = true) { onMic() }
    private val wakeButton = ui.button("WAKE") { host.toggleWake() }
    private val langButton = ui.button("") { cycleLanguage() }

    private var shownCount = -1
    private var shownLast: AssistantSession.ChatMessage? = null

    override val root: View = ui.vertical().apply {
        val header = ui.horizontal().apply {
            setPadding(ui.dp(14), ui.dp(10), ui.dp(10), 0)
            addView(ui.text("R A Z A   A I", 20f, Theme.cyan, true), ui.lp(0, -2, 1f))
            addView(langButton, ui.lp(-2, -2))
        }
        addView(header)
        addView(orb, ui.lp(h = ui.dp(190)).apply { setMargins(ui.dp(40), ui.dp(2), ui.dp(40), 0) })
        addView(phaseText)
        addView(wave, ui.lp(h = ui.dp(36)).apply { setMargins(ui.dp(24), ui.dp(4), ui.dp(24), ui.dp(4)) })
        addView(micText)
        addView(cloudText)
        addView(wakeText)
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(6) })
        addView(partial)

        confirmBar.addView(confirmText)
        val yn = ui.horizontal()
        yn.addView(ui.button("YES, DO IT", accent = true) { session.confirmYes() }, ui.weighted(3))
        yn.addView(ui.button("NO, CANCEL") { session.confirmNo() }, ui.weighted(3))
        confirmBar.addView(yn)
        addView(confirmBar, ui.lp(margin = 8))

        val inputRow = ui.horizontal().apply { setPadding(ui.dp(8), 0, ui.dp(8), 0) }
        inputRow.addView(input, ui.lp(0, -2, 1f))
        inputRow.addView(ui.button("SEND") { send() }, ui.lp(-2, -2, margin = 2))
        inputRow.addView(micButton, ui.lp(-2, -2, margin = 2))
        addView(inputRow)

        val controls = ui.horizontal().apply { setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(4)) }
        controls.addView(ui.button("STOP VOICE") { session.stopSpeaking(); session.stopListening() }, ui.weighted())
        controls.addView(ui.button("REPEAT") { session.repeatLast() }, ui.weighted())
        controls.addView(wakeButton, ui.weighted())
        addView(controls)

        input.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        session.levelSink = { lvl ->
            orb.setLevel(lvl)
            wave.pushLevel(lvl)
        }
    }

    private fun send() {
        val t = input.text.toString()
        input.setText("")
        session.submit(t)
    }

    private fun onMic() {
        if (session.phase == Phase.LISTENING) session.stopListening() else host.withMic { session.startListening() }
    }

    private fun cycleLanguage() {
        val all = Languages.ALL
        val next = all[(all.indexOf(host.settings.language) + 1) % all.size]
        host.settings.language = next
        refresh()
    }

    private fun languageChip(): String = when (host.settings.language) {
        Languages.ENGLISH -> "LANG: EN"
        Languages.URDU -> "LANG: اردو"
        else -> "LANG: EN + اردو"
    }

    override fun refresh() {
        val phase = session.phase
        orb.phase = phase
        wave.phase = phase
        val conn = session.connection
        orb.online = conn is ConnectionState.Online || conn is ConnectionState.Checking || conn is ConnectionState.Disabled

        phaseText.text = when (phase) {
            Phase.IDLE -> "STANDING BY"
            Phase.LISTENING -> "LISTENING"
            Phase.PROCESSING -> "PROCESSING"
            Phase.SPEAKING -> "SPEAKING"
        }
        phaseText.setTextColor(Theme.phaseColor(phase))
        langButton.text = languageChip()
        micButton.text = if (phase == Phase.LISTENING) "STOP" else "MIC"

        val granted = host.hasMic()
        val mic = MicStatusResolver.label(MicStatusResolver.resolve(granted, WakeHub.state, phase == Phase.LISTENING))
        micText.text = if (!granted && host.micBlocked()) "$mic (blocked: enable it in Settings > Permissions)" else mic
        micText.setTextColor(if (granted) Theme.muted else Theme.amber)

        cloudText.text = conn.label
        cloudText.setTextColor(
            when (conn) {
                is ConnectionState.Online -> Theme.green
                is ConnectionState.Unreachable -> Theme.red
                ConnectionState.Checking, ConnectionState.Disabled -> Theme.muted
                else -> Theme.amber
            },
        )

        wakeText.text = when (WakeHub.state) {
            WakeState.OFF -> "WAKE PHRASE: off"
            WakeState.STARTING -> "WAKE PHRASE: starting..."
            WakeState.LISTENING -> "WAKE PHRASE: say \"${host.settings.wakePhrase}\""
            WakeState.COMMAND_LISTENING -> "WAKE PHRASE: heard you"
            WakeState.PAUSED -> "WAKE PHRASE: paused"
            WakeState.ERROR -> "WAKE PHRASE ERROR: ${WakeHub.error ?: "unknown"}"
        }
        wakeText.setTextColor(if (WakeHub.state == WakeState.ERROR) Theme.red else Theme.muted)
        wakeButton.text = if (WakeHub.state == WakeState.OFF || WakeHub.state == WakeState.ERROR) "WAKE: OFF" else "WAKE: ON"
        partial.text = session.partial

        val pending = session.pending()
        if (pending != null) {
            val left = ((pending.createdAt + ConfirmationManager.DEFAULT_TIMEOUT_MS - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            val auth = if (pending.needsAuth && !pending.authenticated) "\nYou will be asked to unlock your phone first." else ""
            confirmText.text = "CONFIRM (${left}s): ${pending.action.describe()}$auth"
            confirmBar.visibility = View.VISIBLE
        } else {
            confirmBar.visibility = View.GONE
        }

        renderChat()
    }

    private fun renderChat() {
        val msgs = session.messages
        if (msgs.size == shownCount && msgs.lastOrNull() === shownLast) return
        shownCount = msgs.size
        shownLast = msgs.lastOrNull()
        log.removeAllViews()
        if (msgs.isEmpty()) {
            log.addView(ui.text("Say \"${host.settings.wakePhrase}\" or tap MIC. Try: \"what is the time\", \"open settings\", \"add task buy milk\".", 13f, Theme.muted).apply { setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10)) })
        }
        for (m in msgs) log.addView(bubble(m))
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(m: AssistantSession.ChatMessage): View {
        val (who, color, fill) = when (m.role) {
            Role.USER -> Triple("YOU", Theme.text, Theme.panel2)
            Role.ASSISTANT -> Triple("RAZA", Theme.cyan, Theme.panel)
            Role.SYSTEM -> Triple("SYSTEM", Theme.amber, android.graphics.Color.rgb(36, 30, 12))
        }
        val col = ui.vertical().apply {
            background = ui.shape(fill, if (m.role == Role.USER) Theme.cyanDim else Theme.alpha(color, 120), 14f)
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
            addView(ui.text(who, 10f, color, true).apply { letterSpacing = 0.15f })
            addView(ui.text(m.text, 15f, Theme.text))
        }
        val lp = ui.lp().apply {
            setMargins(if (m.role == Role.USER) ui.dp(40) else 0, ui.dp(3), if (m.role == Role.USER) 0 else ui.dp(40), ui.dp(3))
        }
        col.layoutParams = lp
        return col
    }
}
