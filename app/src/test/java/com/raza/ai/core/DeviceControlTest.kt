package com.raza.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControlTest {
    private fun parse(s: String) = LocalCommandParser.parse(s)
    private fun action(s: String): DeviceAction = (parse(s) as ParseResult.Match).action

    // ---------- WhatsApp parsing ----------
    @Test fun whatsAppByNameVariants() {
        assertEquals(DeviceAction.SendWhatsApp("Ali", null, "I will be late"), action("send whatsapp message to Ali saying I will be late"))
        assertEquals(DeviceAction.SendWhatsApp("Ali", null, "dinner is ready"), action("WhatsApp Ali that dinner is ready"))
        assertEquals(DeviceAction.SendWhatsApp("Sara Khan", null, "hello"), action("message Sara Khan on whatsapp saying hello"))
        assertEquals(DeviceAction.SendWhatsApp("Ali", null, "I'm outside"), action("tell Ali on whatsapp that I'm outside"))
    }

    @Test fun whatsAppByNumberKeepsNumberAndNoContact() {
        assertEquals(
            DeviceAction.SendWhatsApp(null, "+923001234567", "hi"),
            action("send whatsapp message to +92 300 1234567 saying hi"),
        )
    }

    @Test fun whatsAppUrdu() {
        assertEquals(
            DeviceAction.SendWhatsApp("علی", null, "میں آ رہا ہوں"),
            action("علی کو واٹس ایپ پر میسج بھیجو کہ میں آ رہا ہوں"),
        )
    }

    @Test fun whatsAppWithoutMessageIsNotCommandAndOpenAppStillWorks() {
        assertTrue(parse("whatsapp Ali") is ParseResult.NoMatch)
        assertEquals(DeviceAction.OpenApp("whatsapp"), action("open whatsapp"))
    }

    // ---------- YouTube ----------
    @Test fun youTubeSearchAndPlay() {
        assertEquals(DeviceAction.YouTubeSearch("despacito", true), action("play despacito on youtube"))
        assertEquals(DeviceAction.YouTubeSearch("cat videos", false), action("search cat videos on youtube"))
        assertEquals(DeviceAction.YouTubeSearch("lofi music", true), action("open youtube and play lofi music"))
        assertEquals(DeviceAction.YouTubeSearch("قرآن", true), action("یوٹیوب پر قرآن چلاؤ"))
    }

    @Test fun youTubeBeatsGenericMediaButPlainMusicStaysMedia() {
        assertTrue(action("play despacito song on youtube") is DeviceAction.YouTubeSearch)
        assertEquals(DeviceAction.Media(MediaCommand.PLAY), action("play music"))
        assertEquals(DeviceAction.Media(MediaCommand.NEXT), action("next song"))
    }

    // ---------- navigation ----------
    @Test fun navigationCommands() {
        assertEquals(DeviceAction.Navigate(NavKind.BACK), action("go back"))
        assertEquals(DeviceAction.Navigate(NavKind.HOME), action("Go home."))
        assertEquals(DeviceAction.Navigate(NavKind.RECENTS), action("recent apps"))
        assertEquals(DeviceAction.Navigate(NavKind.SCROLL_DOWN), action("scroll down"))
        assertEquals(DeviceAction.Navigate(NavKind.SCROLL_UP), action("scroll up"))
        assertEquals(DeviceAction.Navigate(NavKind.NEXT_VIDEO), action("next video"))
        assertEquals(DeviceAction.Navigate(NavKind.PREVIOUS_VIDEO), action("previous video"))
        assertEquals(DeviceAction.Navigate(NavKind.NEXT_VIDEO), action("اگلی ویڈیو"))
        assertEquals(DeviceAction.Navigate(NavKind.SCROLL_DOWN), action("نیچے سکرول کرو"))
    }

    @Test fun navigationIsExactNotSubstring() {
        assertTrue(parse("back to the future") is ParseResult.NoMatch)
        assertTrue(parse("tell me about the next video game") is ParseResult.NoMatch)
    }

    @Test fun navigationRunsImmediatelyAndIsNotSensitive() {
        val a = DeviceAction.Navigate(NavKind.BACK)
        assertFalse(a.sensitive)
        assertTrue(a.runsImmediately)
        assertFalse(DeviceAction.SendWhatsApp("A", "923001234567", "x").runsImmediately)
    }

    // ---------- timers and reminders ----------
    @Test fun timers() {
        assertEquals(DeviceAction.SetTimer(600, ""), action("set a timer for 10 minutes"))
        assertEquals(DeviceAction.SetTimer(7200, "call mom"), action("remind me in 2 hours to call mom"))
        assertEquals(DeviceAction.SetTimer(30, ""), action("set timer for 30 seconds"))
        assertTrue(parse("set timer for 0 minutes") is ParseResult.Invalid)
        assertTrue(parse("remind me in 5000 minutes") is ParseResult.Invalid)
    }

    @Test fun remindAtBecomesLabelledAlarmAndTaskReminderIsUntouched() {
        assertEquals(DeviceAction.SetAlarm(19, 30, "take medicine"), action("remind me at 7:30 pm to take medicine"))
        assertTrue(parse("remind me at 25 to x") is ParseResult.Invalid)
        assertTrue(parse("remind me to call Ali") is ParseResult.NoMatch) // handled as a task elsewhere
        assertEquals(DeviceAction.SetAlarm(7, 30), action("set alarm for 7:30"))
    }

    // ---------- number normalisation ----------
    @Test fun whatsAppNumberNormalisation() {
        assertEquals("923001234567", WhatsAppNumber.normalize("+92 300 1234567", ""))
        assertEquals("923001234567", WhatsAppNumber.normalize("0300 1234567", "92"))
        assertEquals("923001234567", WhatsAppNumber.normalize("0092 300 1234567", ""))
        assertEquals("923001234567", WhatsAppNumber.normalize("3001234567", "92"))
        assertEquals("923001234567", WhatsAppNumber.normalize("923001234567", ""))
        assertNull("local number with unknown country must not be guessed", WhatsAppNumber.normalize("03001234567", ""))
        assertNull(WhatsAppNumber.normalize("12345", "92"))
        assertNull(WhatsAppNumber.normalize("abc", "92"))
        assertNull(WhatsAppNumber.normalize(null, "92"))
    }

    @Test fun countryCodes() {
        assertEquals("92", CountryCodes.fromIso("PK"))
        assertEquals("", CountryCodes.fromIso("zz"))
        assertEquals("", CountryCodes.fromIso(null))
    }

    // ---------- contact matching ----------
    @Test fun contactMatcherPicksBestAndNeverGuessesBetweenPeople() {
        val rows = listOf("Ali Raza" to "0300 111 1111", "Ali" to "0300 222 2222", "Sara" to "0300 333 3333")
        assertEquals(ContactResult.Found("Ali", "0300 222 2222"), ContactMatcher.pick("ali", rows))
        assertEquals(ContactResult.NotFound, ContactMatcher.pick("ahmed", rows))
        val two = listOf("Ali Khan" to "0300 111 1111", "Ali Raza" to "0321 222 2222")
        val r = ContactMatcher.pick("ali", two)
        assertTrue(r is ContactResult.Ambiguous)
        assertEquals(2, (r as ContactResult.Ambiguous).options.size)
    }

    @Test fun sameNumberSavedTwiceIsOnePerson() {
        val rows = listOf("Ali" to "+92 300 1234567", "Ali" to "0300-1234567")
        assertTrue(ContactMatcher.pick("ali", rows) is ContactResult.Found)
    }

    // ---------- engine routing ----------
    private val info = object : DeviceInfo {
        override fun timeText() = "10:15"
        override fun dateText() = "d"
        override fun batteryText(): String? = "80%"
    }

    private fun engine(
        resolver: ContactResolver = ContactResolver { ContactResult.NoPermission },
        cc: String = "92",
        direct: Boolean = false,
        online: Boolean = false,
        api: FakeApi = FakeApi(),
    ) = AssistantEngine(api, ConfirmationManager(), { online }, info, ConversationHistory(), { true }, resolver, { cc }, { direct })

    @Test fun whatsAppByNameResolvesThenAsksForConfirmationWithOwnerAuth() {
        val e = engine(ContactResolver { ContactResult.Found("Ali Raza", "0300 1234567") })
        val r = e.handle("send whatsapp message to Ali saying I am late", "English") as Outcome.Confirm
        assertEquals(DeviceAction.SendWhatsApp("Ali Raza", "923001234567", "I am late"), r.pending.action)
        assertTrue(r.pending.needsAuth)
    }

    @Test fun whatsAppByNumberDoesNotTouchContacts() {
        var called = false
        val e = engine(ContactResolver { called = true; ContactResult.NotFound })
        val r = e.handle("send whatsapp message to +923001234567 saying hello", "English") as Outcome.Confirm
        assertEquals(DeviceAction.SendWhatsApp(null, "923001234567", "hello"), r.pending.action)
        assertFalse(called)
    }

    @Test fun whatsAppFailuresAreExplainedAndNothingIsProposed() {
        fun reply(res: ContactResult): String {
            val r = engine(ContactResolver { res }).handle("whatsapp Ali that hi", "English")
            return (r as Outcome.Reply).text
        }
        assertTrue(reply(ContactResult.NoPermission).contains("Contacts"))
        assertTrue(reply(ContactResult.NotFound).contains("couldn't find"))
        val amb = reply(ContactResult.Ambiguous(listOf("Ali Khan (0300 1)", "Ali Raza (0321 2)")))
        assertTrue(amb.contains("Ali Khan") && amb.contains("Ali Raza") && amb.contains("won't guess"))
    }

    @Test fun whatsAppNumberWithUnknownCountryIsRefusedNotGuessed() {
        val e = engine(ContactResolver { ContactResult.Found("Ali", "0300111222") }, cc = "")
        val r = e.handle("whatsapp Ali that hi", "English") as Outcome.Reply
        assertTrue(r.text.contains("country"))
    }

    @Test fun navigationRunsOfflineWithNoConfirmationAndNoApiCall() {
        val api = FakeApi()
        val r = engine(online = false, api = api).handle("go back", "English")
        assertEquals(Outcome.Run(DeviceAction.Navigate(NavKind.BACK)), r)
        assertEquals(0, api.planCalls)
    }

    @Test fun directCallFlagIsAppliedByTheClientOnly() {
        val on = engine(direct = true).handle("call 03001234567", "English") as Outcome.Confirm
        assertEquals(DeviceAction.Dial("03001234567", true), on.pending.action)
        assertTrue(on.pending.action.describe().contains("now"))
        val off = engine(direct = false).handle("call 03001234567", "English") as Outcome.Confirm
        assertEquals(DeviceAction.Dial("03001234567", false), off.pending.action)
        assertNotEquals(on.pending.action, off.pending.action)
        assertTrue(off.pending.needsAuth)
    }

    @Test fun youTubeAndTimerNeedConfirmation() {
        assertTrue(engine().handle("play despacito on youtube", "English") is Outcome.Confirm)
        assertTrue(engine().handle("set a timer for 5 minutes", "English") is Outcome.Confirm)
    }

    @Test fun describeTextsAreHonest() {
        assertTrue(DeviceAction.SendWhatsApp("Ali", "923001234567", "hi").describe().contains("otherwise you press send"))
        assertTrue(DeviceAction.SetAlarm(7, 0, "gym").describe().contains("gym"))
        assertEquals("Start a 10 minute(s) timer.", DeviceAction.SetTimer(600).describe())
    }
}
