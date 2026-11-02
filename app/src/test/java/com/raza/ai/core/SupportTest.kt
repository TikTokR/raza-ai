package com.raza.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportTest {

    // ---- Script / Languages ----
    @Test
    fun urduTextIsDetectedAndEnglishIsNot() {
        assertTrue(Script.isUrdu("آپ کیسے ہیں؟"))
        assertFalse(Script.isUrdu("How are you?"))
        assertFalse(Script.isUrdu(""))
        assertFalse(Script.isUrdu("12345 !!!"))
    }

    @Test
    fun mixedTextFollowsTheMajorityScript() {
        assertTrue(Script.isUrdu("آپ کا battery 80 فیصد ہے"))
        assertFalse(Script.isUrdu("Your battery is 80 فیصد percent today"))
    }

    @Test
    fun speechTagAndNormalize() {
        assertEquals("ur-PK", Languages.speechTag(Languages.URDU))
        assertEquals("en-US", Languages.speechTag(Languages.ENGLISH))
        assertEquals("en-US", Languages.speechTag(Languages.BOTH))
        assertEquals(Languages.BOTH, Languages.normalize(null))
        assertEquals(Languages.BOTH, Languages.normalize("Klingon"))
        assertEquals(Languages.URDU, Languages.normalize("Urdu"))
    }

    // ---- AppMatcher ----
    private val apps = listOf("Calculator", "YouTube", "YouTube Music", "WhatsApp", "Settings", "Camera")

    @Test
    fun exactBeatsPrefixAndPrefixBeatsContains() {
        assertEquals(1, AppMatcher.best("youtube", apps))
        assertEquals(2, AppMatcher.best("youtube music", apps))
        assertEquals(3, AppMatcher.best("whats", apps))
    }

    @Test
    fun spacingCaseAndSuffixAreIgnored() {
        assertEquals(1, AppMatcher.best("You Tube", apps))
        assertEquals(0, AppMatcher.best("the calculator app", apps))
    }

    @Test
    fun oneLetterMistakeStillMatchesButNonsenseDoesNot() {
        assertEquals(3, AppMatcher.best("whatsap", apps))
        assertNull(AppMatcher.best("zzzzqq", apps))
        assertNull(AppMatcher.best("", apps))
        assertNull(AppMatcher.best("camera", emptyList()))
    }

    @Test
    fun tieGoesToShorterLabel() {
        assertEquals(1, AppMatcher.best("music", listOf("Music Player Pro Edition", "Music Player")))
    }

    // ---- VoiceScore ----
    @Test
    fun maleVoiceBeatsFemaleAndNeutral() {
        val male = VoiceScore.score("en-us-x-male-local", requiresNetwork = false, preferOffline = true)
        val female = VoiceScore.score("en-us-x-female-local", requiresNetwork = false, preferOffline = true)
        val neutral = VoiceScore.score("en-us-x-abc-local", requiresNetwork = false, preferOffline = true)
        assertTrue(male > neutral)
        assertTrue(neutral > female)
    }

    @Test
    fun wordsContainingManAreNotTreatedAsMaleMarkers() {
        val german = VoiceScore.score("de-de-x-german-local", requiresNetwork = false, preferOffline = true)
        val plain = VoiceScore.score("de-de-x-abc-local", requiresNetwork = false, preferOffline = true)
        assertEquals(plain, german)
    }

    @Test
    fun offlinePreferenceFavoursLocalVoices() {
        val local = VoiceScore.score("v1", requiresNetwork = false, preferOffline = true)
        val net = VoiceScore.score("v1", requiresNetwork = true, preferOffline = true)
        assertTrue(local > net)
        val netOnline = VoiceScore.score("v1", requiresNetwork = true, preferOffline = false)
        val localOnline = VoiceScore.score("v1", requiresNetwork = false, preferOffline = false)
        assertTrue(netOnline > localOnline)
    }

    // ---- ConnectionResolver ----
    private val healthy = ApiResult.Ok(HealthInfo("ok", "1.0.0"))

    @Test
    fun notConfiguredWinsOverEverything() {
        assertEquals(ConnectionState.NotConfigured, ConnectionResolver.resolve(false, true, healthy))
        assertEquals(ConnectionState.NotConfigured, ConnectionResolver.resolve(false, false, null))
    }

    @Test
    fun noNetworkIsOfflineEvenIfAnOldHealthCheckSucceeded() {
        assertEquals(ConnectionState.Offline, ConnectionResolver.resolve(true, false, healthy))
    }

    @Test
    fun neverOnlineWithoutASuccessfulHealthCheck() {
        assertEquals(ConnectionState.Checking, ConnectionResolver.resolve(true, true, null))
        val failed = ConnectionResolver.resolve(true, true, ApiResult.Err(ApiError.Timeout))
        assertTrue(failed is ConnectionState.Unreachable)
        assertTrue(failed.label.contains("too long"))
        val odd = ConnectionResolver.resolve(true, true, ApiResult.Ok(HealthInfo("degraded", "1")))
        assertTrue(odd is ConnectionState.Unreachable)
    }

    @Test
    fun healthyBackendIsOnlineWithVersion() {
        val s = ConnectionResolver.resolve(true, true, healthy)
        assertEquals(ConnectionState.Online("1.0.0"), s)
        assertTrue(s.label.contains("1.0.0"))
    }
}
