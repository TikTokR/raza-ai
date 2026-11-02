package com.raza.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasksTest {
    private var now = 1_000L
    private fun book(initial: List<TaskItem> = emptyList()) = TaskBook(initial) { now++ }

    // ---- TaskBook ----
    @Test
    fun addTrimsCollapsesWhitespaceAndAssignsIncreasingIds() {
        val b = book()
        val a = b.add("  buy   milk ") as AddResult.Added
        val c = b.add("call the bank") as AddResult.Added
        assertEquals("buy milk", a.item.title)
        assertEquals(1L, a.item.id)
        assertEquals(2L, c.item.id)
        assertFalse(a.item.done)
    }

    @Test
    fun invalidTitlesAreRejected() {
        val b = book()
        assertTrue(b.add("   ") is AddResult.Rejected)
        assertTrue(b.add("x".repeat(TaskRules.MAX_TITLE + 1)) is AddResult.Rejected)
        assertTrue(b.add("bad\u0007title") is AddResult.Rejected)
        assertEquals(0, b.all().size)
    }

    @Test
    fun listIsCappedAndSaysSo() {
        val b = book()
        repeat(TaskRules.MAX_TASKS) { assertTrue(b.add("task $it") is AddResult.Added) }
        val r = b.add("one too many")
        assertTrue(r is AddResult.Rejected)
        assertTrue((r as AddResult.Rejected).reason.contains("full"))
        assertEquals(TaskRules.MAX_TASKS, b.all().size)
    }

    @Test
    fun idsAreNotReusedAfterDelete() {
        val b = book()
        b.add("a"); b.add("b")
        b.remove(2)
        val c = b.add("c") as AddResult.Added
        assertEquals(2L, c.item.id) // max existing id (1) + 1
        b.remove(1); b.remove(2)
        assertEquals(1L, (b.add("d") as AddResult.Added).item.id)
    }

    @Test
    fun doneToggleRemoveAndClearDone() {
        val b = book()
        b.add("a"); b.add("b"); b.add("c")
        assertTrue(b.setDone(1, true))
        assertTrue(b.setDone(3, true))
        assertFalse(b.setDone(99, true))
        assertEquals(listOf("b"), b.open().map { it.title })
        assertEquals(2, b.clearDone())
        assertEquals(listOf("b"), b.all().map { it.title })
        assertTrue(b.remove(2))
        assertFalse(b.remove(2))
    }

    @Test
    fun completeByNumberUsesTheNumberingThatWasReadAloud() {
        val b = book()
        b.add("a"); b.add("b"); b.add("c")
        b.setDone(1, true) // "a" no longer shown, so "b" is now number 1
        val done = b.completeByNumber(1)
        assertEquals("b", done?.title)
        assertEquals(listOf("c"), b.open().map { it.title })
        assertNull(b.completeByNumber(5))
        assertNull(b.completeByNumber(0))
    }

    // ---- notes ----
    @Test
    fun notesRefuseSecretsAndCardNumbers() {
        for (bad in listOf(
            "my password is hunter2", "my PIN is 4821", "the otp is 123456", "cvv 123",
            "card 4111 1111 1111 1111", "card 4111-1111-1111-1111", "my api key is abc",
        )) {
            assertEquals("should reject: $bad", NoteRules.NO_SECRETS, NoteRules.validate(bad))
        }
    }

    @Test
    fun ordinaryNotesAreAccepted() {
        assertNull(NoteRules.validate("my sister's name is Ayesha"))
        assertNull(NoteRules.validate("I take tea without sugar"))
        assertNotNull(NoteRules.validate(""))
        assertNotNull(NoteRules.validate("x".repeat(NoteRules.MAX_NOTE + 1)))
    }

    @Test
    fun noteBookCapsAndBuildsContext() {
        val nb = NoteBook(clock = { 5L })
        assertNull(nb.asContext())
        nb.add("likes tea"); nb.add("lives in Lahore")
        assertEquals("Facts I asked you to remember about me: likes tea; lives in Lahore", nb.asContext())
        repeat(NoteRules.MAX_NOTES) { nb.add("fact $it") }
        assertEquals(NoteRules.MAX_NOTES, nb.all().size)
        assertTrue(nb.add("overflow") is AddResult.Rejected)
        assertTrue(nb.add("my password is x") is AddResult.Rejected)
        nb.clear()
        assertTrue(nb.all().isEmpty())
    }

    // ---- LocalCommands ----
    @Test
    fun addTaskPhrasings() {
        assertEquals(LocalCommand.AddTask("buy milk"), LocalCommands.parse("add task buy milk"))
        assertEquals(LocalCommand.AddTask("buy milk"), LocalCommands.parse("Add a task: buy milk"))
        assertEquals(LocalCommand.AddTask("call mum"), LocalCommands.parse("remind me to call mum."))
        assertEquals(LocalCommand.AddTask("milk"), LocalCommands.parse("add milk to my to-do list"))
        assertEquals(LocalCommand.AddTask("pay rent"), LocalCommands.parse("create task to pay rent"))
    }

    @Test
    fun rememberShowAndCompletePhrasings() {
        assertEquals(LocalCommand.Remember("I like tea"), LocalCommands.parse("remember that I like tea"))
        assertEquals(LocalCommand.Remember("my dog is Bruno"), LocalCommands.parse("please remember my dog is Bruno"))
        assertEquals(LocalCommand.ShowTasks, LocalCommands.parse("show my tasks"))
        assertEquals(LocalCommand.ShowTasks, LocalCommands.parse("what are my tasks?"))
        assertEquals(LocalCommand.ShowNotes, LocalCommands.parse("what do you remember about me"))
        assertEquals(LocalCommand.CompleteTask(2), LocalCommands.parse("complete task 2"))
        assertEquals(LocalCommand.CompleteTask(3), LocalCommands.parse("mark task number 3 as done"))
    }

    @Test
    fun deviceCommandsAndChatAreNotStolenByLocalCommands() {
        for (t in listOf("call 03001234567", "open settings", "what is the time", "set alarm for 7", "how are you", "tell me a joke", "", "   ")) {
            assertNull("must not match: '$t'", LocalCommands.parse(t))
        }
    }

    @Test
    fun spokenSummaries() {
        assertEquals("You have no open tasks.", LocalCommands.speakTasks(emptyList()))
        val one = listOf(TaskItem(1, "buy milk", false, 0))
        assertEquals("You have 1 open task: 1, buy milk.", LocalCommands.speakTasks(one))
        val many = (1..7).map { TaskItem(it.toLong(), "t$it", false, 0) }
        val text = LocalCommands.speakTasks(many)
        assertTrue(text.startsWith("You have 7 open tasks: 1, t1. 2, t2"))
        assertTrue(text.endsWith("And 2 more."))
        assertTrue(LocalCommands.speakNotes(emptyList()).contains("remember that"))
    }

    // ---- Persistence ----
    @Test
    fun tasksRoundTrip() {
        val list = listOf(TaskItem(1, "buy milk", true, 10), TaskItem(2, "اردو ٹاسک", false, 20))
        assertEquals(list, Persistence.tasksFromJson(Persistence.tasksToJson(list)))
    }

    @Test
    fun notesLogAndChatRoundTrip() {
        val notes = listOf(MemoryNote(1, "likes tea", 5))
        assertEquals(notes, Persistence.notesFromJson(Persistence.notesToJson(notes)))
        val log = listOf(ActionLogEntry(9, "Open the app \"Camera\".", true, "Asked Android to open Camera."))
        assertEquals(log, Persistence.logFromJson(Persistence.logToJson(log)))
        val chat = listOf(ChatRecord("user", "hi", 1), ChatRecord("assistant", "hello", 2))
        assertEquals(chat, Persistence.chatFromJson(Persistence.chatToJson(chat)))
    }

    @Test
    fun damagedFilesGiveEmptyListsNotCrashes() {
        for (bad in listOf(null, "", "not json", "{\"a\":1}", "[1,2,3]", "[{\"id\":1}]")) {
            assertTrue(Persistence.tasksFromJson(bad).isEmpty())
            assertTrue(Persistence.notesFromJson(bad).isEmpty())
            assertTrue(Persistence.logFromJson(bad).isEmpty())
            assertTrue(Persistence.chatFromJson(bad).isEmpty())
        }
    }

    @Test
    fun badRowsAreSkippedGoodRowsKept() {
        val raw = """[{"id":1,"title":"ok","done":false,"createdAt":1},{"id":2},{"id":3,"title":"","done":false}]"""
        assertEquals(listOf("ok"), Persistence.tasksFromJson(raw).map { it.title })
    }

    @Test
    fun aSecretLookingNoteInAFileIsNeverLoaded() {
        val raw = """[{"id":1,"text":"my pin is 1234","createdAt":1},{"id":2,"text":"likes tea","createdAt":1}]"""
        assertEquals(listOf("likes tea"), Persistence.notesFromJson(raw).map { it.text })
    }

    @Test
    fun chatRolesOtherThanUserAndAssistantAreDropped() {
        val raw = """[{"role":"system","text":"x","time":1},{"role":"user","text":"hi","time":2}]"""
        assertEquals(listOf("hi"), Persistence.chatFromJson(raw).map { it.text })
    }

    @Test
    fun newestKeepsTheTail() {
        assertEquals(listOf(4, 5), Persistence.newest(listOf(1, 2, 3, 4, 5), 2))
        assertEquals(listOf(1), Persistence.newest(listOf(1), 5))
    }

    // ---- ConversationHistory preamble / restore ----
    @Test
    fun preambleComesFirstAndTotalStaysWithinTheBackendLimit() {
        val pre = listOf(HistoryItem("user", "Facts: likes tea"), HistoryItem("assistant", "Understood."))
        val h = ConversationHistory(maxItems = 20, preamble = { pre })
        repeat(15) { h.addExchange("q$it", "a$it") }
        val snap = h.snapshot()
        assertEquals(20, snap.size)
        assertEquals(pre, snap.take(2))
        assertEquals("user", snap[2].role)
        assertEquals("a14", snap.last().content)
    }

    @Test
    fun restoreKeepsOnlyAlternatingTurnsAndDropsADanglingQuestion() {
        val h = ConversationHistory()
        h.restore(
            listOf(
                HistoryItem("assistant", "stray"),
                HistoryItem("user", "q1"), HistoryItem("assistant", "a1"),
                HistoryItem("user", "q2"), HistoryItem("assistant", "a2"),
                HistoryItem("user", "unanswered"),
            ),
        )
        assertEquals(listOf("q1", "a1", "q2", "a2"), h.snapshot().map { it.content })
    }
}
