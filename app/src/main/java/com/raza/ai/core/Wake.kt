package com.raza.ai.core

import java.util.Locale

/** Matches the wake phrase inside a recognised transcript (English + Urdu, tolerant of 1-letter errors). */
object WakeMatcher {
    fun tokens(s: String): List<String> =
        s.lowercase(Locale.ROOT)
            .map { c ->
                when {
                    c in 'ً'..'ٟ' || c == 'ٰ' -> ' ' // Arabic diacritics
                    c == 'ي' -> 'ی'
                    c == 'ك' -> 'ک'
                    c.isLetterOrDigit() -> c
                    else -> ' '
                }
            }
            .joinToString("")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        return a.length >= 4 && b.length >= 4 && levenshtein(a, b) <= 1
    }

    fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /** Index just after the matched phrase in the transcript's tokens, or -1 if not present. */
    private fun findEnd(transcript: List<String>, phrase: List<String>): Int {
        if (phrase.isEmpty() || transcript.size < phrase.size) return -1
        for (start in 0..transcript.size - phrase.size) {
            if (phrase.indices.all { similar(transcript[start + it], phrase[it]) }) return start + phrase.size
        }
        return -1
    }

    fun matches(transcript: String, wakePhrase: String): Boolean =
        findEnd(tokens(transcript), tokens(wakePhrase)) >= 0

    fun matchesAny(transcript: String, phrases: List<String>): Boolean = phrases.any { matches(transcript, it) }

    /** Words spoken after the wake phrase ("hey raza open settings" -> "open settings"), or null. */
    fun commandAfter(transcript: String, wakePhrase: String): String? {
        val t = tokens(transcript)
        val end = findEnd(t, tokens(wakePhrase))
        if (end < 0) return null
        return t.drop(end).joinToString(" ").ifBlank { null }
    }
}

object WakePhraseValidator {
    sealed class Result {
        data class Ok(val phrase: String) : Result()
        data class Rejected(val reason: String) : Result()
    }

    fun validate(raw: String): Result {
        val phrase = raw.trim().replace(Regex("\\s+"), " ")
        val tokens = WakeMatcher.tokens(phrase)
        val letters = tokens.sumOf { it.length }
        return when {
            phrase.length > 40 -> Result.Rejected("Wake phrase must be at most 40 characters.")
            tokens.size > 5 -> Result.Rejected("Use at most 5 words.")
            tokens.size < 2 && letters < 5 ->
                Result.Rejected("Too short. Use two words (like \"hey raza\") or one word of 5+ letters to avoid false triggers.")
            letters < 4 -> Result.Rejected("Too short. Use at least 4 letters.")
            else -> Result.Ok(phrase)
        }
    }

    val DEFAULTS = listOf("hey raza", "ہیلو رضا", "اے رضا")
}

enum class WakeState { OFF, STARTING, LISTENING, COMMAND_LISTENING, PAUSED, ERROR }

/** Pure state machine for the wake listener. Invalid transitions return false and change nothing. */
class WakeStateMachine {
    var state: WakeState = WakeState.OFF
        private set
    var error: String? = null
        private set

    fun start(permissionsGranted: Boolean): Boolean {
        if (state != WakeState.OFF && state != WakeState.ERROR) return false
        if (!permissionsGranted) {
            state = WakeState.ERROR
            error = "Microphone permission is required."
            return false
        }
        error = null
        state = WakeState.STARTING
        return true
    }

    fun onReady(): Boolean = move(setOf(WakeState.STARTING), WakeState.LISTENING)
    fun pause(): Boolean = move(setOf(WakeState.LISTENING, WakeState.COMMAND_LISTENING, WakeState.STARTING), WakeState.PAUSED)
    fun resume(): Boolean = move(setOf(WakeState.PAUSED), WakeState.LISTENING)
    fun onWakeDetected(): Boolean = move(setOf(WakeState.LISTENING), WakeState.COMMAND_LISTENING)
    fun onCommandFinished(): Boolean = move(setOf(WakeState.COMMAND_LISTENING), WakeState.LISTENING)

    fun onError(message: String): Boolean {
        if (state == WakeState.OFF) return false
        error = message
        state = WakeState.ERROR
        return true
    }

    fun stop() {
        state = WakeState.OFF
        error = null
    }

    private fun move(from: Set<WakeState>, to: WakeState): Boolean {
        if (state !in from) return false
        state = to
        return true
    }
}

/** Exponential restart delay so a failing recogniser never spins the CPU or battery. */
class RestartBackoff(private val baseMs: Long = 500L, private val maxMs: Long = 30_000L) {
    private var failures = 0
    fun next(): Long {
        val delay = minOf(maxMs, baseMs shl minOf(failures, 10))
        failures++
        return delay
    }

    fun reset() {
        failures = 0
    }

    val failureCount: Int get() = failures
}

/** Maps android.speech.SpeechRecognizer error codes (as ints, to stay JVM-testable) to a policy. */
object RecognizerErrors {
    enum class Action { RETRY_QUICK, RETRY_BACKOFF, FATAL_PERMISSION, FATAL_LANGUAGE }

    fun classify(code: Int): Action = when (code) {
        6, 7 -> Action.RETRY_QUICK // speech timeout / no match: normal silence
        9 -> Action.FATAL_PERMISSION
        12, 13 -> Action.FATAL_LANGUAGE
        else -> Action.RETRY_BACKOFF // network, audio, server, client, busy, too many requests...
    }

    fun message(code: Int): String = when (code) {
        1 -> "Network timeout while recognising speech."
        2 -> "Network error while recognising speech."
        3 -> "Audio recording problem. Is another app using the microphone?"
        4 -> "The speech service had a problem."
        5 -> "Speech recogniser client error."
        6 -> "I didn't hear anything."
        7 -> "I couldn't understand that. Please try again."
        8 -> "The speech recogniser is busy."
        9 -> "Microphone permission is missing."
        10 -> "Too many speech requests. Slowing down."
        11 -> "The speech service disconnected."
        12 -> "That language is not supported by the speech service."
        13 -> "That language's speech data is not installed."
        else -> "Speech recognition error ($code)."
    }
}

enum class MicStatus { PERMISSION_DENIED, OFF, WAKE_LISTENING, COMMAND_LISTENING, PAUSED, ERROR }

object MicStatusResolver {
    fun resolve(permissionGranted: Boolean, wake: WakeState, inAppListening: Boolean): MicStatus = when {
        !permissionGranted -> MicStatus.PERMISSION_DENIED
        inAppListening -> MicStatus.COMMAND_LISTENING
        wake == WakeState.COMMAND_LISTENING -> MicStatus.COMMAND_LISTENING
        wake == WakeState.LISTENING -> MicStatus.WAKE_LISTENING
        wake == WakeState.STARTING -> MicStatus.WAKE_LISTENING
        wake == WakeState.PAUSED -> MicStatus.PAUSED
        wake == WakeState.ERROR -> MicStatus.ERROR
        else -> MicStatus.OFF
    }

    fun label(s: MicStatus): String = when (s) {
        MicStatus.PERMISSION_DENIED -> "MIC: PERMISSION NEEDED"
        MicStatus.OFF -> "MIC: OFF"
        MicStatus.WAKE_LISTENING -> "MIC: LISTENING FOR WAKE PHRASE"
        MicStatus.COMMAND_LISTENING -> "MIC: LISTENING TO YOU"
        MicStatus.PAUSED -> "MIC: PAUSED"
        MicStatus.ERROR -> "MIC: ERROR"
    }
}
