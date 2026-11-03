package com.raza.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningTest {
    private fun status(
        mic: Boolean = true,
        notif: Boolean = true,
        wants: Boolean = true,
        state: WakeState = WakeState.OFF,
        error: String? = null,
        reason: String? = null,
    ) = ListeningStatusResolver.resolve(mic, notif, wants, state, error, "hey raza", reason)

    @Test fun onlyARunningServiceCountsAsListening() {
        for (s in WakeState.entries) {
            val st = status(state = s)
            val shouldBeOn = s == WakeState.LISTENING || s == WakeState.COMMAND_LISTENING
            assertEquals("state $s", shouldBeOn, st.micIsOn)
        }
    }

    @Test fun switchLeftOnButServiceDeadIsReportedAsStoppedNotListening() {
        val st = status(wants = true, state = WakeState.OFF)
        assertFalse(st.micIsOn)
        assertEquals(Severity.BAD, st.severity)
        assertTrue(st.label.contains("STOPPED"))
        assertTrue(st.canStart && st.canRestart)
        assertFalse(st.label.startsWith("LISTENING"))
    }

    @Test fun deadServiceShowsAndroidsRealReasonWhenKnown() {
        val st = status(wants = true, state = WakeState.OFF, reason = "RAZA was force-stopped (from Settings, a battery manager or a cleaner app). (11/3/26)")
        assertTrue(st.detail.contains("force-stopped"))
        val unknown = status(wants = true, state = WakeState.OFF, reason = null)
        assertTrue(unknown.detail.contains("may have ended"))
    }

    @Test fun offWhenUserDidNotAskForListening() {
        val st = status(wants = false, state = WakeState.OFF)
        assertEquals(Severity.OFF, st.severity)
        assertEquals("OFF", st.label)
        assertTrue(st.canStart)
        assertFalse(st.canStop)
        assertFalse(st.canRestart)
    }

    @Test fun listeningNamesThePhraseAndWarnsWhenNotificationsAreBlocked() {
        val ok = status(state = WakeState.LISTENING)
        assertEquals(Severity.OK, ok.severity)
        assertTrue(ok.label.contains("hey raza"))
        assertTrue(ok.canStop && ok.canRestart && !ok.canStart)
        val blocked = status(state = WakeState.LISTENING, notif = false)
        assertEquals(Severity.WARN, blocked.severity)
        assertTrue(blocked.micIsOn)
        assertTrue(blocked.detail.contains("notifications are blocked"))
    }

    @Test fun missingMicPermissionWinsOverEverything() {
        for (s in WakeState.entries) {
            val st = status(mic = false, state = s)
            assertFalse("state $s", st.micIsOn)
            assertTrue(st.label.contains("PERMISSION"))
            assertEquals(Severity.BAD, st.severity)
        }
    }

    @Test fun startingAndPausedAreNotListening() {
        assertFalse(status(state = WakeState.STARTING).micIsOn)
        val p = status(state = WakeState.PAUSED)
        assertFalse(p.micIsOn)
        assertTrue(p.label.contains("MICROPHONE OFF"))
    }

    @Test fun errorShowsTheErrorAndAllowsRestart() {
        val st = status(state = WakeState.ERROR, error = "Audio recording problem.")
        assertTrue(st.label.contains("Audio recording problem."))
        assertFalse(st.micIsOn)
        assertTrue(st.canRestart && st.canStart)
    }

    @Test fun controlsAreConsistentForEveryState() {
        for (wants in listOf(true, false)) for (s in WakeState.entries) {
            val st = status(wants = wants, state = s)
            if (st.micIsOn) assertTrue("running state must be stoppable", st.canStop)
            if (st.micIsOn) assertFalse("cannot start what is running", st.canStart)
        }
    }

    // ---------- exit reasons ----------
    @Test fun exitReasonsAreExplainedInPlainLanguage() {
        assertTrue(ExitReasons.describe(10).contains("force-stopped"))
        assertTrue(ExitReasons.describe(3).contains("memory"))
        assertTrue(ExitReasons.describe(9).contains("resources"))
        assertTrue(ExitReasons.describe(16).contains("updated"))
        assertTrue(ExitReasons.describe(14).contains("froze"))
        assertTrue(ExitReasons.describe(4).contains("crashed"))
    }

    @Test fun everyDocumentedReasonHasTextAndUnknownIsLabelled() {
        for (r in 1..16) assertTrue("reason $r", ExitReasons.describe(r).isNotBlank())
        assertEquals("Unknown reason (99).", ExitReasons.describe(99))
    }
}
