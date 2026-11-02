package com.raza.ai.ui

import android.app.AlertDialog
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import com.raza.ai.AssistantSession
import java.text.DateFormat
import java.util.Date

/** Your to-do list, what RAZA has done on your phone, and the facts you asked it to remember. */
class TasksScreen(ui: Ui, session: AssistantSession, host: Host) : Screen(ui, session, host) {
    private val content = ui.vertical().apply { setPadding(0, ui.dp(8), 0, ui.dp(16)) }
    override val root: View = ScrollView(ui.ctx).apply { addView(content) }

    // The text fields live outside refresh() so typing is not lost when the screen redraws.
    private val taskField = field("Add a task...")
    private val noteField = field("Something for RAZA to remember...")

    private fun field(hintText: String) = EditText(ui.ctx).apply {
        hint = hintText
        setHintTextColor(Theme.muted)
        setTextColor(Theme.text)
        setSingleLine(true)
        background = ui.shape(Theme.panel2, Theme.cyanDim, 12f)
        setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
    }

    override fun refresh() {
        // Keep the fields (and their text/focus) alive across redraws.
        (taskField.parent as? android.view.ViewGroup)?.removeView(taskField)
        (noteField.parent as? android.view.ViewGroup)?.removeView(noteField)
        content.removeAllViews()
        content.addView(ui.text("TASKS & MEMORY", 18f, Theme.cyan, true).apply {
            letterSpacing = 0.2f
            setPadding(ui.dp(16), ui.dp(4), 0, ui.dp(4))
        })
        content.addView(tasksCard(), ui.cardParams())
        content.addView(logCard(), ui.cardParams())
        content.addView(notesCard(), ui.cardParams())
    }

    private fun tasksCard(): View {
        val c = ui.card("MY TASKS")
        val add = ui.horizontal()
        add.addView(taskField, ui.lp(0, -2, 1f))
        add.addView(ui.button("ADD", accent = true) {
            val err = session.addTask(taskField.text.toString())
            if (err == null) taskField.setText("") else host.toast(err)
        }, ui.lp(-2, -2, margin = 2))
        c.addView(add)
        val all = session.tasks.all()
        if (all.isEmpty()) c.addView(ui.note("No tasks yet. You can also say \"add task buy milk\" or \"remind me to call Ali\"."))
        for (t in all) {
            val line = ui.horizontal()
            line.addView(CheckBox(ui.ctx).apply {
                isChecked = t.done
                text = t.title
                setTextColor(if (t.done) Theme.muted else Theme.text)
                paintFlags = if (t.done) paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG else paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
                setOnCheckedChangeListener { _, checked -> session.setTaskDone(t.id, checked) }
            }, ui.lp(0, -2, 1f))
            line.addView(ui.button("X") { session.removeTask(t.id) }, ui.lp(-2, -2, margin = 2))
            c.addView(line)
        }
        if (all.any { it.done }) {
            c.addView(ui.button("Clear completed") { session.clearDoneTasks() }, ui.lp().apply { topMargin = ui.dp(6) })
        }
        c.addView(ui.note("Tasks are not timed reminders. For those, say \"set alarm for 7:30\"."))
        return c
    }

    private fun logCard(): View {
        val c = ui.card("WHAT RAZA DID ON YOUR PHONE")
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val entries = session.actionLog.takeLast(15).reversed()
        if (entries.isEmpty()) c.addView(ui.text("Nothing yet. Every approved or cancelled device command is listed here.", 13f, Theme.muted))
        for (e in entries) {
            c.addView(ui.text(fmt.format(Date(e.time)), 10f, Theme.muted).apply { setPadding(0, ui.dp(6), 0, 0) })
            c.addView(ui.text(e.summary, 13f, Theme.text, true))
            c.addView(ui.text((if (e.ok) "OK: " else "NOT DONE: ") + e.message, 12f, if (e.ok) Theme.green else Theme.amber))
        }
        c.addView(ui.note("\"OK\" means Android accepted the request. It cannot always confirm what the other app then did."))
        if (entries.isNotEmpty()) {
            c.addView(ui.button("Clear log") {
                confirm("Clear the action log?", "This removes the list above. It cannot be undone.") { session.deleteActionLog() }
            }, ui.lp().apply { topMargin = ui.dp(6) })
        }
        return c
    }

    private fun notesCard(): View {
        val c = ui.card("THINGS I REMEMBER")
        val add = ui.horizontal()
        add.addView(noteField, ui.lp(0, -2, 1f))
        add.addView(ui.button("SAVE", accent = true) {
            val err = session.addNote(noteField.text.toString())
            if (err == null) noteField.setText("") else host.toast(err)
        }, ui.lp(-2, -2, margin = 2))
        c.addView(add)
        val notes = session.notes.all()
        if (notes.isEmpty()) c.addView(ui.note("Nothing saved. Say \"remember that my sister is Ayesha\"."))
        for (n in notes) {
            val line = ui.horizontal()
            line.addView(ui.text("•  ${n.text}", 14f, Theme.text), ui.lp(0, -2, 1f))
            line.addView(ui.button("X") { session.removeNote(n.id) }, ui.lp(-2, -2, margin = 2))
            c.addView(line)
        }
        c.addView(ui.note("Notes stay on this phone, but when you chat online they are sent to your backend as context. I never store passwords, PINs, codes or card numbers."))
        return c
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(ui.ctx).setTitle(title).setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> onYes() }
            .show()
    }
}
