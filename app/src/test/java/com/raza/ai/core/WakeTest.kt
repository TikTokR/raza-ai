package com.raza.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeTest {
    // ----- phrase matching -----
    @Test fun matchesPhraseAnywhereCaseAndPunctuationInsensitive() {
        assertTrue(WakeMatcher.matches("Hey, Raza!", "hey raza"))
        assertTrue(WakeMatcher.matches("ok so hey raza what time is it", "hey raza"))
    }

    @Test fun toleratesOneLetterRecognitionError() {
        assertTrue(WakeMatcher.matches("hey rasa", "hey raza"))
        assertTrue(WakeMatcher.matches("hey raaza", "hey raza"))
    }

    @Test fun doesNotMatchUnrelatedOrPartial() {
        assertFalse(WakeMatcher.matches("hello world", "hey raza"))
        assertFalse(WakeMatcher.matches("hey", "hey raza"))
        assertFalse(WakeMatcher.matches("raza hey", "hey raza"))
        assertFalse(WakeMatcher.matches("hey rozen", "hey raza"))
    }

    @Test fun urduPhrases() {
        assertTrue(WakeMatcher.matches("ہیلو رضا کیا حال ہے", "ہیلو رضا"))
        assertTrue(WakeMatcher.matchesAny("اے رضا", WakePhraseValidator.DEFAULTS))
        assertFalse(WakeMatcher.matchesAny("سلام دوست", WakePhraseValidator.DEFAULTS))
    }

    @Test fun customPhraseWorksAndOldOneNoLongerDoes() {
        assertTrue(WakeMatcher.matches("computer wake up", "computer wake up"))
        assertFalse(WakeMatcher.matches("hey raza", "computer wake up"))
    }

    @Test fun commandAfterPhrase() {
        assertEquals("open settings", WakeMatcher.commandAfter("Hey Raza, open settings", "hey raza"))
        assertNull(WakeMatcher.commandAfter("hey raza", "hey raza"))
        assertNull(WakeMatcher.commandAfter("good morning", "hey raza"))
    }

    // ----- phrase validation -----
    @Test fun validatorAcceptsReasonablePhrases() {
        assertEquals(WakePhraseValidator.Result.Ok("hey raza"), WakePhraseValidator.validate("  hey   raza "))
        assertTrue(WakePhraseValidator.validate("jarvis") is WakePhraseValidator.Result.Ok)
        assertTrue(WakePhraseValidator.validate("ہیلو رضا") is WakePhraseValidator.Result.Ok)
    }

    @Test fun validatorRejectsFalseTriggerProne() {
        assertTrue(WakePhraseValidator.validate("hi") is WakePhraseValidator.Result.Rejected)
        assertTrue(WakePhraseValidator.validate("ok") is WakePhraseValidator.Result.Rejected)
        assertTrue(WakePhraseValidator.validate("a b") is WakePhraseValidator.Result.Rejected)
        assertTrue(WakePhraseValidator.validate("") is WakePhraseValidator.Result.Rejected)
        assertTrue(WakePhraseValidator.validate("one two three four five six") is WakePhraseValidator.Result.Rejected)
        assertTrue(WakePhraseValidator.validate("x".repeat(41)) is WakePhraseValidator.Result.Rejected)
    }

    // ----- state machine -----
    @Test fun happyPathLifecycle() {
        val m = WakeStateMachine()
        assertEquals(WakeState.OFF, m.state)
        assertTrue(m.start(permissionsGranted = true))
        assertEquals(WakeState.STARTING, m.state)
        assertTrue(m.onReady())
        assertEquals(WakeState.LISTENING, m.state)
        assertTrue(m.onWakeDetected())
        assertEquals(WakeState.COMMAND_LISTENING, m.state)
        assertTrue(m.onCommandFinished())
        assertEquals(WakeState.LISTENING, m.state)
        assertTrue(m.pause())
        assertEquals(WakeState.PAUSED, m.state)
        assertTrue(m.resume())
        assertEquals(WakeState.LISTENING, m.state)
        m.stop()
        assertEquals(WakeState.OFF, m.state)
    }

    @Test fun cannotStartWithoutPermission() {
        val m = WakeStateMachine()
        assertFalse(m.start(false))
        assertEquals(WakeState.ERROR, m.state)
        assertTrue(m.error!!.contains("permission", ignoreCase = true))
        assertTrue("can retry after granting", m.start(true))
        assertNull(m.error)
    }

    @Test fun invalidTransitionsAreRejectedAndChangeNothing() {
        val m = WakeStateMachine()
        assertFalse(m.onReady())
        assertFalse(m.onWakeDetected())
        assertFalse(m.pause())
        assertFalse(m.resume())
        assertEquals(WakeState.OFF, m.state)
        m.start(true)
        assertFalse("cannot start twice", m.start(true))
        assertFalse("cannot detect wake before ready", m.onWakeDetected())
        assertEquals(WakeState.STARTING, m.state)
    }

    @Test fun errorOnlyWhenRunningAndStopRecovers() {
        val m = WakeStateMachine()
        assertFalse(m.onError("x"))
        m.start(true); m.onReady()
        assertTrue(m.onError("audio busy"))
        assertEquals(WakeState.ERROR, m.state)
        assertEquals("audio busy", m.error)
        m.stop()
        assertEquals(WakeState.OFF, m.state)
        assertNull(m.error)
    }

    // ----- restart policy -----
    @Test fun backoffGrowsIsCappedAndResets() {
        val b = RestartBackoff(baseMs = 500, maxMs = 5_000)
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 5000L, 5000L), List(6) { b.next() })
        b.reset()
        assertEquals(500L, b.next())
    }

    @Test fun recognizerErrorClassification() {
        assertEquals(RecognizerErrors.Action.RETRY_QUICK, RecognizerErrors.classify(7))
        assertEquals(RecognizerErrors.Action.RETRY_QUICK, RecognizerErrors.classify(6))
        assertEquals(RecognizerErrors.Action.RETRY_BACKOFF, RecognizerErrors.classify(8))
        assertEquals(RecognizerErrors.Action.RETRY_BACKOFF, RecognizerErrors.classify(2))
        assertEquals(RecognizerErrors.Action.FATAL_PERMISSION, RecognizerErrors.classify(9))
        assertEquals(RecognizerErrors.Action.FATAL_LANGUAGE, RecognizerErrors.classify(13))
        assertTrue(RecognizerErrors.message(13).contains("not installed"))
    }

    // ----- microphone status shown to the user -----
    @Test fun micStatusResolution() {
        fun r(perm: Boolean, w: WakeState, inApp: Boolean = false) = MicStatusResolver.resolve(perm, w, inApp)
        assertEquals(MicStatus.PERMISSION_DENIED, r(false, WakeState.LISTENING))
        assertEquals(MicStatus.OFF, r(true, WakeState.OFF))
        assertEquals(MicStatus.WAKE_LISTENING, r(true, WakeState.LISTENING))
        assertEquals(MicStatus.COMMAND_LISTENING, r(true, WakeState.COMMAND_LISTENING))
        assertEquals(MicStatus.COMMAND_LISTENING, r(true, WakeState.OFF, inApp = true))
        assertEquals(MicStatus.PAUSED, r(true, WakeState.PAUSED))
        assertEquals(MicStatus.ERROR, r(true, WakeState.ERROR))
        assertTrue(MicStatusResolver.label(MicStatus.PERMISSION_DENIED).contains("PERMISSION"))
    }
}
