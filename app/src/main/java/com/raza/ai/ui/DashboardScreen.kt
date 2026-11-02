package com.raza.ai.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.raza.ai.AssistantSession
import com.raza.ai.AssistantSession.Role
import com.raza.ai.core.ConnectionState
import com.raza.ai.core.MicStatusResolver
import com.raza.ai.core.WakeState
import com.raza.ai.voice.WakeHub

/** Command-centre overview: status, quick actions, tasks, recent chats and system info. */
class DashboardScreen(ui: Ui, session: AssistantSession, host: Host) : Screen(ui, session, host) {
    private val content = ui.vertical().apply { setPadding(0, ui.dp(8), 0, ui.dp(16)) }
    override val root: View = ScrollView(ui.ctx).apply { addView(content) }

    private data class Quick(val label: String, val command: String)

    private val quick = listOf(
        Quick("Time", "what is the time"),
        Quick("Date", "what is the date"),
        Quick("Battery", "what is my battery level"),
        Quick("Wi-Fi", "open wifi settings"),
        Quick("Bluetooth", "open bluetooth settings"),
        Quick("Volume +", "volume up"),
        Quick("Volume -", "volume down"),
        Quick("My tasks", "show my tasks"),
    )

    override fun refresh() {
        content.removeAllViews()
        content.addView(ui.text("COMMAND CENTER", 18f, Theme.cyan, true).apply {
            letterSpacing = 0.2f
            setPadding(ui.dp(16), ui.dp(4), 0, ui.dp(4))
        })
        content.addView(statusCard(), ui.cardParams())
        content.addView(quickCard(), ui.cardParams())
        content.addView(tasksCard(), ui.cardParams())
        content.addView(recentCard(), ui.cardParams())
        content.addView(systemCard(), ui.cardParams())
    }

    private fun statusCard(): View {
        val c = ui.card("ASSISTANT STATUS")
        val phase = session.phase
        c.addView(ui.row("Assistant", phase.name, Theme.phaseColor(phase)))
        val mic = MicStatusResolver.label(MicStatusResolver.resolve(host.hasMic(), WakeHub.state, phase == AssistantSession.Phase.LISTENING))
        c.addView(ui.row("Microphone", mic.removePrefix("MIC: "), if (host.hasMic()) Theme.text else Theme.amber))
        val conn = session.connection
        val connColor = when (conn) {
            is ConnectionState.Online -> Theme.green
            is ConnectionState.Unreachable -> Theme.red
            ConnectionState.Checking, ConnectionState.Disabled -> Theme.muted
            else -> Theme.amber
        }
        c.addView(ui.row("Cloud AI", conn.label, connColor))
        c.addView(
            ui.row(
                "Wake phrase",
                when (WakeHub.state) {
                    WakeState.OFF -> "off"
                    WakeState.STARTING -> "starting"
                    WakeState.LISTENING -> "listening"
                    WakeState.COMMAND_LISTENING -> "heard you"
                    WakeState.PAUSED -> "paused"
                    WakeState.ERROR -> "error"
                },
                if (WakeHub.state == WakeState.ERROR) Theme.red else Theme.text,
            ),
        )
        c.addView(ui.button("Refresh connection") { session.refreshConnection() }, ui.lp().apply { topMargin = ui.dp(6) })
        return c
    }

    private fun quickCard(): View {
        val c = ui.card("QUICK ACTIONS")
        c.addView(ui.note("Device changes still ask you to confirm on the Assistant screen."))
        var row: LinearLayout? = null
        quick.forEachIndexed { i, q ->
            if (i % 2 == 0) {
                row = ui.horizontal()
                c.addView(row)
            }
            row?.addView(
                ui.button(q.label) {
                    host.openTab(0)
                    session.submit(q.command)
                },
                ui.weighted(3),
            )
        }
        return c
    }

    private fun tasksCard(): View {
        val c = ui.card("ACTIVE TASKS")
        val open = session.tasks.open()
        if (open.isEmpty()) c.addView(ui.text("No open tasks. Say \"add task ...\".", 13f, Theme.muted))
        open.take(5).forEach { c.addView(ui.text("•  ${it.title}", 14f, Theme.text).apply { setPadding(0, ui.dp(2), 0, ui.dp(2)) }) }
        if (open.size > 5) c.addView(ui.note("+ ${open.size - 5} more"))
        c.addView(ui.button("Open tasks") { host.openTab(2) }, ui.lp().apply { topMargin = ui.dp(6) })
        return c
    }

    private fun recentCard(): View {
        val c = ui.card("RECENT CONVERSATIONS")
        val chat = session.messages.filter { it.role != Role.SYSTEM }
        if (chat.isEmpty()) c.addView(ui.text("Nothing yet.", 13f, Theme.muted))
        // Pair each question with the answer that followed it; show the newest few.
        val pairs = ArrayList<Pair<String, String?>>()
        var i = 0
        while (i < chat.size) {
            val m = chat[i]
            if (m.role == Role.USER) {
                val reply = chat.getOrNull(i + 1)?.takeIf { it.role == Role.ASSISTANT }
                pairs.add(m.text to reply?.text)
                i += if (reply != null) 2 else 1
            } else {
                i++
            }
        }
        pairs.takeLast(4).reversed().forEach { (q, a) ->
            c.addView(ui.text("You: ${q.take(80)}", 13f, Theme.text, true).apply { setPadding(0, ui.dp(4), 0, 0) })
            if (a != null) c.addView(ui.text("RAZA: ${a.take(120)}", 13f, Theme.muted))
        }
        c.addView(ui.button("Open assistant") { host.openTab(0) }, ui.lp().apply { topMargin = ui.dp(6) })
        return c
    }

    private fun systemCard(): View {
        val c = ui.card("SYSTEM")
        session.systemInfo().forEach { (k, v) -> c.addView(ui.row(k, v)) }
        return c
    }
}
