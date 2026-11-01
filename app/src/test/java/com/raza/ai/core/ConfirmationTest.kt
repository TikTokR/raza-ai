package com.raza.ai.core

import com.raza.ai.core.ConfirmationManager.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationTest {
    private var now = 1_000L
    private val mgr = ConfirmationManager(clock = { now }, timeoutMs = 30_000)
    private val call = DeviceAction.Dial("0300111")
    private val alarm = DeviceAction.SetAlarm(7, 0)

    @Test fun actionRunsOnlyAfterConfirm() {
        val p = mgr.propose(alarm, authAvailable = true)
        assertNotNull(mgr.current())
        assertEquals(Decision.Execute(alarm), mgr.confirm(p.id))
    }

    @Test fun confirmIsSingleUse() {
        val p = mgr.propose(alarm, true)
        mgr.confirm(p.id)
        assertEquals(Decision.NothingPending, mgr.confirm(p.id))
        assertNull(mgr.current())
    }

    @Test fun rejectClearsAndNeverExecutes() {
        val p = mgr.propose(alarm, true)
        assertEquals(Decision.Rejected, mgr.reject())
        assertEquals(Decision.NothingPending, mgr.confirm(p.id))
    }

    @Test fun staleIdCannotConfirmNewerProposal() {
        val old = mgr.propose(alarm, true)
        val newer = mgr.propose(DeviceAction.Volume(VolumeDirection.UP), true)
        assertEquals(Decision.NothingPending, mgr.confirm(old.id))
        assertEquals(newer.id, mgr.current()?.id)
    }

    @Test fun expiredProposalCannotBeConfirmed() {
        val p = mgr.propose(alarm, true)
        now += 31_000
        assertEquals(Decision.Expired, mgr.confirm(p.id))
        assertNull(mgr.current())
    }

    @Test fun callNeedsOwnerAuthBeforeExecuting() {
        val p = mgr.propose(call, authAvailable = true)
        assertTrue(p.needsAuth)
        assertTrue(mgr.confirm(p.id) is Decision.NeedsAuth)
        assertNotNull("still pending after NeedsAuth", mgr.current())
        assertTrue(mgr.markAuthenticated(p.id))
        assertEquals(Decision.Execute(call), mgr.confirm(p.id))
    }

    @Test fun authCannotBeMarkedForWrongProposal() {
        val p = mgr.propose(call, true)
        assertTrue(!mgr.markAuthenticated(p.id + 99))
        assertTrue(mgr.confirm(p.id) is Decision.NeedsAuth)
    }

    @Test fun noAuthRequiredWhenDeviceHasNoLockScreen() {
        val p = mgr.propose(call, authAvailable = false)
        assertTrue(!p.needsAuth)
        assertEquals(Decision.Execute(call), mgr.confirm(p.id))
    }

    @Test fun nonOwnerActionsSkipAuth() {
        assertTrue(!mgr.propose(alarm, authAvailable = true).needsAuth)
    }

    @Test fun voiceYesNoAndUnclear() {
        mgr.propose(alarm, true)
        assertEquals(Decision.Unclear, mgr.respond("maybe later"))
        assertNotNull("unclear answer keeps the proposal", mgr.current())
        assertEquals(Decision.Execute(alarm), mgr.respond("yes"))

        mgr.propose(alarm, true)
        assertEquals(Decision.Rejected, mgr.respond("no"))
        assertNull(mgr.current())
    }

    @Test fun voiceReplyWithNothingPending() {
        assertEquals(Decision.NothingPending, mgr.respond("yes"))
    }

    @Test fun voiceYesOnCallStillRequiresAuth() {
        mgr.propose(call, true)
        assertTrue(mgr.respond("yes") is Decision.NeedsAuth)
    }

    @Test fun replyParsingEnglishAndUrdu() {
        for (y in listOf("yes", "Yes!", "yeah", "okay", "go ahead", "haan", "جی ہاں", "ہاں", "ٹھیک ہے")) {
            assertEquals("'$y'", Reply.YES, ConfirmationReply.parse(y))
        }
        for (n in listOf("no", "cancel", "nahi", "نہیں", "منسوخ", "stop")) {
            assertEquals("'$n'", Reply.NO, ConfirmationReply.parse(n))
        }
    }

    @Test fun noAlwaysWinsOverYes() {
        assertEquals(Reply.NO, ConfirmationReply.parse("yes no wait don't"))
        assertEquals(Reply.NO, ConfirmationReply.parse("yes cancel"))
    }

    @Test fun ambiguousSpeechIsNotYes() {
        assertEquals(Reply.UNCLEAR, ConfirmationReply.parse(""))
        assertEquals(Reply.UNCLEAR, ConfirmationReply.parse("what was that"))
        assertEquals(Reply.UNCLEAR, ConfirmationReply.parse("the weather is nice today and yes it is"))
    }
}
