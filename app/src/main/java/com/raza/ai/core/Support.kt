package com.raza.ai.core

import java.util.Locale

/** Script detection so Urdu replies get an Urdu voice and English replies an English voice. */
object Script {
    fun isUrdu(text: String): Boolean {
        var letters = 0
        var arabic = 0
        for (c in text) {
            if (!c.isLetter()) continue
            letters++
            val code = c.code
            if (code in 0x0600..0x06FF || code in 0x0750..0x077F || code in 0xFB50..0xFDFF || code in 0xFE70..0xFEFF) arabic++
        }
        return letters > 0 && arabic * 2 > letters
    }
}

object Languages {
    const val ENGLISH = "English"
    const val URDU = "Urdu"
    const val BOTH = "English + Urdu"
    val ALL = listOf(ENGLISH, URDU, BOTH)

    /** Language tag used for speech recognition. One recogniser language at a time. */
    fun speechTag(language: String): String = if (language == URDU) "ur-PK" else "en-US"

    fun normalize(language: String?): String = if (language != null && language in ALL) language else BOTH
}

/** Picks the best installed app for a spoken name ("you tube" -> "YouTube"). */
object AppMatcher {
    private fun clean(s: String): String =
        s.lowercase(Locale.ROOT).trim().removePrefix("the ").removeSuffix(" app").replace(Regex("\\s+"), " ")

    private fun squash(s: String): String = clean(s).replace(" ", "")

    fun score(query: String, label: String): Int {
        val q = clean(query)
        val l = clean(label)
        if (q.isEmpty() || l.isEmpty()) return 0
        return when {
            l == q -> 100
            squash(l) == squash(q) -> 95
            l.startsWith(q) -> 80
            l.split(" ").any { it.startsWith(q) } -> 60
            l.contains(q) -> 40
            q.length >= 4 && l.length >= 4 && WakeMatcher.levenshtein(squash(q), squash(l)) <= 1 -> 30
            else -> 0
        }
    }

    /** Index of the best label, or null if nothing matches. Ties go to the shorter label. */
    fun best(query: String, labels: List<String>): Int? {
        var bestIdx = -1
        var bestScore = 0
        for ((i, label) in labels.withIndex()) {
            val s = score(query, label)
            if (s > bestScore || (s == bestScore && s > 0 && label.length < labels[bestIdx].length)) {
                bestScore = s
                bestIdx = i
            }
        }
        return if (bestIdx >= 0) bestIdx else null
    }
}

/**
 * Heuristic for choosing a masculine voice. Android's TextToSpeech does not expose a reliable
 * gender flag, so this only works when the engine puts it in the voice name. The result depends
 * on which voices the phone has installed.
 */
object VoiceScore {
    fun score(name: String, requiresNetwork: Boolean, preferOffline: Boolean): Int {
        val n = name.lowercase(Locale.ROOT)
        var s = 0
        if (n.contains("female") || n.contains("woman")) s -= 100
        else if (n.contains("male")) s += 20
        if (preferOffline && !requiresNetwork) s += 5
        if (!preferOffline && requiresNetwork) s += 2
        return s
    }
}

sealed class ConnectionState(val label: String) {
    data object NotConfigured : ConnectionState("CLOUD AI: NOT SET UP")
    data object Offline : ConnectionState("OFFLINE: on-device commands only")
    data object Checking : ConnectionState("CLOUD AI: CHECKING...")
    data class Online(val version: String) : ConnectionState("ONLINE: backend v$version")
    data class Unreachable(val reason: String) : ConnectionState("CLOUD AI UNREACHABLE: $reason")
}

object ConnectionResolver {
    /** health == null means "not checked yet". Never reports Online without a successful health check. */
    fun resolve(configured: Boolean, networkUp: Boolean, health: ApiResult<HealthInfo>?): ConnectionState = when {
        !configured -> ConnectionState.NotConfigured
        !networkUp -> ConnectionState.Offline
        health == null -> ConnectionState.Checking
        health is ApiResult.Ok ->
            if (health.value.status == "ok") ConnectionState.Online(health.value.version)
            else ConnectionState.Unreachable("backend reports status \"${health.value.status}\"")
        health is ApiResult.Err -> ConnectionState.Unreachable(health.error.userMessage)
        else -> ConnectionState.Checking
    }
}
