package com.raza.ai.core

import java.util.Locale

/** Turns a phone number into the digits-only international form WhatsApp's wa.me links need. */
object WhatsAppNumber {
    /**
     * "+92 300 1234567", "0092 300...", "0300 1234567" (with [defaultCc] "92"), "3001234567" (with cc).
     * Returns null when the country cannot be known or the length is implausible (8..15 digits).
     */
    fun normalize(raw: String?, defaultCc: String): String? {
        val t = raw?.trim() ?: return null
        if (t.isEmpty() || t.any { !(it.isDigit() || it in "+ -()") }) return null
        val plus = t.startsWith("+")
        val digits = t.filter { it.isDigit() }
        val cc = defaultCc.filter { it.isDigit() }
        val result = when {
            plus -> digits
            digits.startsWith("00") -> digits.drop(2)
            digits.startsWith("0") -> if (cc.isNotEmpty()) cc + digits.drop(1) else return null
            digits.length <= 10 -> if (cc.isNotEmpty()) cc + digits else return null
            else -> digits // 11+ digits without a prefix: assume the country code is already there
        }
        return result.takeIf { it.length in 8..15 && !it.startsWith("0") }
    }
}

/** Calling codes for the most common SIM countries, used only as the default for numbers saved without one. */
object CountryCodes {
    private val TABLE = mapOf(
        "pk" to "92", "in" to "91", "bd" to "880", "af" to "93", "ae" to "971", "sa" to "966", "qa" to "974",
        "kw" to "965", "om" to "968", "bh" to "973", "gb" to "44", "us" to "1", "ca" to "1", "au" to "61",
        "tr" to "90", "de" to "49", "fr" to "33", "my" to "60", "id" to "62", "eg" to "20", "ng" to "234",
    )

    fun fromIso(iso: String?): String = TABLE[iso?.trim()?.lowercase(Locale.ROOT)] ?: ""
}

sealed class ContactResult {
    data class Found(val displayName: String, val number: String) : ContactResult()
    data object NotFound : ContactResult()
    data class Ambiguous(val options: List<String>) : ContactResult()
    data object NoPermission : ContactResult()
}

fun interface ContactResolver {
    fun resolve(name: String): ContactResult
}

/** Chooses the contact a spoken name refers to. It never guesses between different people. */
object ContactMatcher {
    /** [rows] are (display name, phone number as stored). */
    fun pick(query: String, rows: List<Pair<String, String>>): ContactResult {
        val scored = rows.map { (n, num) -> Triple(n, num, AppMatcher.score(query, n)) }.filter { it.third > 0 }
        if (scored.isEmpty()) return ContactResult.NotFound
        val best = scored.maxOf { it.third }
        val top = scored.filter { it.third == best }
        // The same number saved twice is one person.
        val distinct = top.distinctBy { it.second.filter { c -> c.isDigit() }.takeLast(9) }
        if (distinct.size == 1) return ContactResult.Found(distinct[0].first, distinct[0].second)
        return ContactResult.Ambiguous(distinct.take(5).map { "${it.first} (${it.second})" })
    }
}
