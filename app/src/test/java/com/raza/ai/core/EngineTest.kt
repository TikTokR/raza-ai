package com.raza.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeApi(
    var plan: ApiResult<RemotePlan> = ApiResult.Ok(RemotePlan("NONE", emptyList(), null)),
    var reply: ApiResult<String> = ApiResult.Ok("cloud reply"),
) : ApiOps {
    var planCalls = 0
    var assistantCalls = 0
    var lastHistory: List<HistoryItem> = emptyList()
    override fun health(): ApiResult<HealthInfo> = ApiResult.Ok(HealthInfo("ok", "1"))
    override fun assistant(message: String, language: String, history: List<HistoryItem>): ApiResult<String> {
        assistantCalls++; lastHistory = history; return reply
    }
    override fun commandPlan(message: String, language: String): ApiResult<RemotePlan> {
        planCalls++; return plan
    }
}

class EngineTest {
    private var online = true
    private var auth = true
    private val api = FakeApi()
    private val confirmations = ConfirmationManager()
    private val history = ConversationHistory()
    private val info = object : DeviceInfo {
        override fun timeText() = "10:15"
        override fun dateText() = "Sunday, 1 November"
        override fun batteryText(): String? = "80%"
    }
    private val engine = AssistantEngine(api, confirmations, { online }, info, history, { auth })

    private fun act(type: String, vararg p: Pair<String, Any?>) = RemoteAction(type, mapOf(*p))
    private fun plan(vararg a: RemoteAction) = ApiResult.Ok(RemotePlan("X", a.toList(), null))

    // ----- offline behaviour -----
    @Test fun offlineLocalCommandsStillWorkWithoutAnyApiCall() {
        online = false
        assertEquals(Outcome.Reply("It is 10:15.", Source.LOCAL), engine.handle("what time is it", "English"))
        assertEquals(Outcome.Reply("Battery is at 80%.", Source.LOCAL), engine.handle("battery level", "English"))
        assertTrue(engine.handle("open settings", "English") is Outcome.Confirm)
        assertEquals(0, api.planCalls + api.assistantCalls)
    }

    @Test fun offlineChatIsHonestAndNeverPretendsCloudWorked() {
        online = false
        val r = engine.handle("tell me about the moon", "English") as Outcome.Reply
        assertEquals(Source.LOCAL, r.source)
        assertTrue(r.text.contains("offline", ignoreCase = true))
        assertEquals(0, api.planCalls + api.assistantCalls)
    }

    // ----- confirmation before execution -----
    @Test fun sensitiveLocalCommandBecomesConfirmNotExecution() {
        val r = engine.handle("set alarm for 7:30", "English") as Outcome.Confirm
        assertEquals(DeviceAction.SetAlarm(7, 30), r.pending.action)
        assertNotNull(confirmations.current())
    }

    @Test fun callRequiresOwnerAuthWhenDeviceIsSecure() {
        auth = true
        val r = engine.handle("call 03001234567", "English") as Outcome.Confirm
        assertTrue(r.pending.needsAuth)
        auth = false
        val r2 = engine.handle("call 03001234567", "English") as Outcome.Confirm
        assertTrue(!r2.pending.needsAuth)
    }

    @Test fun readOnlyActionsAnswerImmediatelyWithoutConfirmation() {
        val r = engine.handle("what is the date", "English") as Outcome.Reply
        assertEquals("Today is Sunday, 1 November.", r.text)
        assertNull(confirmations.current())
    }

    @Test fun missingBatteryReadingIsReportedHonestly() {
        val noBattery = AssistantEngine(api, confirmations, { true }, object : DeviceInfo {
            override fun timeText() = "t"; override fun dateText() = "d"; override fun batteryText(): String? = null
        }, history, { true })
        val r = noBattery.handle("battery", "English") as Outcome.Reply
        assertTrue(r.text.contains("can't read"))
    }

    @Test fun invalidNumberGivesClarificationNotConfirmation() {
        val r = engine.handle("call abc", "English") as Outcome.Reply
        assertTrue(r.text.contains("valid phone number"))
        assertNull(confirmations.current())
    }

    // ----- backend planning is validated, never trusted -----
    @Test fun backendActionIsRevalidatedAndAlwaysNeedsConfirmation() {
        // Backend claims requiresConfirmation=false via params; the client ignores that and uses its own rules.
        api.plan = plan(act("OPEN_APP", "name" to "Calculator", "requiresConfirmation" to false))
        val r = engine.handle("I would like a calculator please", "English") as Outcome.Confirm
        assertEquals(DeviceAction.OpenApp("Calculator"), r.pending.action)
        assertEquals(0, api.assistantCalls)
    }

    @Test fun backendInvalidActionsAreDroppedThenConversationFallsBack() {
        api.plan = plan(
            act("DIAL", "number" to "not a number"),
            act("SET_ALARM", "hour" to 99, "minute" to 0),
            act("FORMAT_PHONE"),
            act("VOLUME", "direction" to "SIDEWAYS"),
        )
        val r = engine.handle("do something odd", "English")
        assertEquals(Outcome.Reply("cloud reply", Source.CLOUD), r)
        assertNull(confirmations.current())
    }

    @Test fun onlyFirstValidBackendActionIsProposed() {
        api.plan = plan(act("VOLUME", "direction" to "UP"), act("DIAL", "number" to "03001234567"))
        val r = engine.handle("make it louder and call mom", "English") as Outcome.Confirm
        assertEquals(DeviceAction.Volume(VolumeDirection.UP), r.pending.action)
    }

    @Test fun backendReadOnlyActionAnswersLocally() {
        api.plan = plan(act("GET_TIME"))
        assertEquals(Outcome.Reply("It is 10:15.", Source.LOCAL), engine.handle("could you possibly tell me the hour", "English"))
    }

    @Test fun backendClarificationIsShown() {
        api.plan = ApiResult.Ok(RemotePlan("DIAL", emptyList(), "Please give a valid phone number."))
        val r = engine.handle("ring my friend", "English") as Outcome.Reply
        assertEquals("Please give a valid phone number.", r.text)
        assertEquals(0, api.assistantCalls)
    }

    // ----- conversation + history -----
    @Test fun cloudConversationRecordsHistoryAndSendsItNextTime() {
        engine.handle("tell me a story", "English")
        assertEquals(listOf(HistoryItem("user", "tell me a story"), HistoryItem("assistant", "cloud reply")), history.snapshot())
        engine.handle("and another", "English")
        assertEquals(2, api.lastHistory.size)
    }

    @Test fun historyIsBoundedAndStartsWithUserTurn() {
        val h = ConversationHistory(maxItems = 4)
        for (i in 1..5) h.addExchange("u$i", "a$i")
        val s = h.snapshot()
        assertEquals(4, s.size)
        assertEquals("u4", s.first().content)
        assertEquals("user", s.first().role)
        h.clear()
        assertTrue(h.snapshot().isEmpty())
    }

    // ----- API failures surface as failures -----
    @Test fun apiErrorsAreReportedNotHidden() {
        api.plan = ApiResult.Err(ApiError.Timeout)
        assertEquals(Outcome.Failure(ApiError.Timeout.userMessage), engine.handle("tell me a joke", "English"))

        api.plan = ApiResult.Ok(RemotePlan("NONE", emptyList(), null))
        api.reply = ApiResult.Err(ApiError.ProviderUnavailable)
        assertEquals(Outcome.Failure(ApiError.ProviderUnavailable.userMessage), engine.handle("tell me a joke", "English"))
        assertTrue("failed turns must not enter history", history.snapshot().isEmpty())
    }

    @Test fun notConfiguredIsExplained() {
        api.plan = ApiResult.Err(ApiError.NotConfigured)
        val f = engine.handle("tell me a joke", "English") as Outcome.Failure
        assertTrue(f.text.contains("Settings"))
    }

    @Test fun blankInputIsAFailureWithNoApiCall() {
        assertTrue(engine.handle("   ", "English") is Outcome.Failure)
        assertEquals(0, api.planCalls)
    }

    // ----- PlanMapper edge cases -----
    @Test fun planMapperRejectsMalformedParams() {
        assertNull(PlanMapper.map(RemoteAction("DIAL", emptyMap())))
        assertNull(PlanMapper.map(RemoteAction("SET_ALARM", mapOf("hour" to "7", "minute" to 0))))
        assertNull(PlanMapper.map(RemoteAction("OPEN_APP", mapOf("name" to "x".repeat(80)))))
        assertNull(PlanMapper.map(RemoteAction("OPEN_SETTINGS", mapOf("screen" to "ROOT"))))
        assertEquals(DeviceAction.SetAlarm(7, 5), PlanMapper.map(RemoteAction("SET_ALARM", mapOf("hour" to 7, "minute" to 5))))
        assertEquals(DeviceAction.Media(MediaCommand.NEXT), PlanMapper.map(RemoteAction("MEDIA", mapOf("command" to "NEXT"))))
    }
}
