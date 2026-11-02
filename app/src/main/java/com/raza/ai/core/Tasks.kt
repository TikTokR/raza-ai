package com.raza.ai.core

data class TaskItem(val id: Long, val title: String, val done: Boolean, val createdAt: Long)
data class MemoryNote(val id: Long, val text: String, val createdAt: Long)
data class ActionLogEntry(val time: Long, val summary: String, val ok: Boolean, val message: String)
data class ChatRecord(val role: String, val text: String, val time: Long)

sealed class AddResult<out T> {
    data class Added<T>(val item: T) : AddResult<T>()
    data class Rejected(val reason: String) : AddResult<Nothing>()
}

object TaskRules {
    const val MAX_TITLE = 120
    const val MAX_TASKS = 100

    fun cleanTitle(raw: String?): String? {
        val t = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        return if (t.length in 1..MAX_TITLE && t.none { it.isISOControl() }) t else null
    }
}

/** The user's own to-do list. Local only. */
class TaskBook(initial: List<TaskItem> = emptyList(), private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val items = initial.toMutableList()

    @Synchronized fun all(): List<TaskItem> = items.toList()
    @Synchronized fun open(): List<TaskItem> = items.filter { !it.done }

    @Synchronized fun add(rawTitle: String): AddResult<TaskItem> {
        val title = TaskRules.cleanTitle(rawTitle)
            ?: return AddResult.Rejected("A task needs 1 to ${TaskRules.MAX_TITLE} characters.")
        if (items.size >= TaskRules.MAX_TASKS) return AddResult.Rejected("Your task list is full (${TaskRules.MAX_TASKS}). Remove some first.")
        val item = TaskItem((items.maxOfOrNull { it.id } ?: 0L) + 1, title, false, clock())
        items.add(item)
        return AddResult.Added(item)
    }

    @Synchronized fun setDone(id: Long, done: Boolean): Boolean {
        val i = items.indexOfFirst { it.id == id }
        if (i < 0) return false
        items[i] = items[i].copy(done = done)
        return true
    }

    @Synchronized fun remove(id: Long): Boolean = items.removeAll { it.id == id }

    @Synchronized fun clearDone(): Int {
        val n = items.count { it.done }
        items.removeAll { it.done }
        return n
    }

    /** 1-based position in the list of OPEN tasks, as shown when the assistant reads them out. */
    @Synchronized fun completeByNumber(number: Int): TaskItem? {
        val target = open().getOrNull(number - 1) ?: return null
        setDone(target.id, true)
        return target.copy(done = true)
    }

    @Synchronized fun clear() = items.clear()
}

object NoteRules {
    const val MAX_NOTE = 200
    const val MAX_NOTES = 30
    const val NO_SECRETS = "I don't store passwords, PINs, one-time codes or card numbers."
    private val SECRET = Regex("\\b(password|passcode|passwd|pin|otp|cvv|cvc|secret|api key|ssn)\\b", RegexOption.IGNORE_CASE)
    private val LONG_DIGITS = Regex("(?:\\d[ -]?){13,19}")

    /** Returns an error message, or null if the note may be saved. */
    fun validate(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        return when {
            t.isEmpty() -> "There is nothing to remember."
            t.length > MAX_NOTE -> "Keep notes under $MAX_NOTE characters."
            t.any { it.isISOControl() } -> "That note has unsupported characters."
            SECRET.containsMatchIn(t) || LONG_DIGITS.containsMatchIn(t) -> NO_SECRETS
            else -> null
        }
    }
}

/** Facts the user explicitly asked RAZA to remember. Local, viewable and deletable. */
class NoteBook(initial: List<MemoryNote> = emptyList(), private val clock: () -> Long = { System.currentTimeMillis() }) {
    private val items = initial.toMutableList()

    @Synchronized fun all(): List<MemoryNote> = items.toList()

    @Synchronized fun add(raw: String): AddResult<MemoryNote> {
        NoteRules.validate(raw)?.let { return AddResult.Rejected(it) }
        if (items.size >= NoteRules.MAX_NOTES) return AddResult.Rejected("I can keep ${NoteRules.MAX_NOTES} notes. Delete some first.")
        val note = MemoryNote((items.maxOfOrNull { it.id } ?: 0L) + 1, raw.trim().replace(Regex("\\s+"), " "), clock())
        items.add(note)
        return AddResult.Added(note)
    }

    @Synchronized fun remove(id: Long): Boolean = items.removeAll { it.id == id }
    @Synchronized fun clear() = items.clear()

    /** One compact line sent to the backend as context (disclosed in the privacy text). */
    @Synchronized fun asContext(): String? =
        if (items.isEmpty()) null else "Facts I asked you to remember about me: " + items.joinToString("; ") { it.text }
}

/** Short, local, non-sensitive commands handled on the phone before anything else. */
sealed class LocalCommand {
    data class AddTask(val title: String) : LocalCommand()
    data class Remember(val note: String) : LocalCommand()
    data class CompleteTask(val number: Int) : LocalCommand()
    data object ShowTasks : LocalCommand()
    data object ShowNotes : LocalCommand()
}

object LocalCommands {
    private val I = RegexOption.IGNORE_CASE
    private val ADD_A = Regex("^(?:add|create|new)\\s+(?:a\\s+)?task(?:\\s+to|\\s*:)?\\s+(.+)$", I)
    private val ADD_B = Regex("^remind me to\\s+(.+)$", I)
    private val ADD_C = Regex("^(?:add|put)\\s+(.+?)\\s+(?:to|on)\\s+(?:my\\s+)?(?:tasks?|to-?do|todo)(?:\\s+list)?$", I)
    private val REMEMBER = Regex("^(?:please\\s+)?remember(?:\\s+that)?\\s+(.+)$", I)
    private val COMPLETE = Regex("^(?:complete|finish|mark)\\s+task\\s+(?:number\\s+)?(\\d{1,3})(?:\\s+(?:as\\s+)?done)?$", I)
    private val SHOW_TASKS = Regex("^(?:show|list|read)\\s+(?:me\\s+)?(?:my\\s+)?(?:tasks|to-?do list|todo list)$|^what are my tasks$", I)
    private val SHOW_NOTES = Regex("^what do you remember(?:\\s+about me)?$|^(?:show|list)\\s+(?:my\\s+)?notes$", I)

    fun parse(input: String): LocalCommand? {
        val t = input.trim().trimEnd('.', '!', '?')
        if (t.isEmpty()) return null
        COMPLETE.find(t)?.let { return LocalCommand.CompleteTask(it.groupValues[1].toInt()) }
        SHOW_TASKS.find(t)?.let { return LocalCommand.ShowTasks }
        SHOW_NOTES.find(t)?.let { return LocalCommand.ShowNotes }
        (ADD_A.find(t) ?: ADD_B.find(t) ?: ADD_C.find(t))?.let { return LocalCommand.AddTask(it.groupValues[1].trim()) }
        REMEMBER.find(t)?.let { return LocalCommand.Remember(it.groupValues[1].trim()) }
        return null
    }

    fun speakTasks(open: List<TaskItem>): String =
        if (open.isEmpty()) "You have no open tasks."
        else "You have ${open.size} open ${if (open.size == 1) "task" else "tasks"}: " +
            open.take(5).withIndex().joinToString(". ") { (i, t) -> "${i + 1}, ${t.title}" } +
            if (open.size > 5) ". And ${open.size - 5} more." else "."

    fun speakNotes(notes: List<MemoryNote>): String =
        if (notes.isEmpty()) "I'm not remembering anything yet. Say \"remember that\" followed by a fact."
        else "I remember: " + notes.take(8).joinToString(". ") { it.text } + "."
}
