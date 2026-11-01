package com.raza.ai.core

object Validators {
    private val PHONE = Regex("^\\+?[0-9][0-9\\s\\-()]{5,18}[0-9]$")
    private val PHONE_JUNK = Regex("[\\s\\-()]")

    /** Returns digits (with optional leading +) or null if this is not a plausible phone number. */
    fun normalizePhone(raw: String?): String? {
        val t = raw?.trim() ?: return null
        if (!PHONE.matches(t)) return null
        return t.replace(PHONE_JUNK, "")
    }

    fun validTime(hour: Int, minute: Int): Boolean = hour in 0..23 && minute in 0..59

    fun cleanAppName(raw: String?): String? {
        val t = raw?.trim() ?: return null
        return if (t.length in 1..60 && t.none { it.isISOControl() }) t else null
    }

    const val MAX_SMS_BODY = 500
}

/** Validates and normalises the user-entered backend address. HTTPS only. */
object UrlValidator {
    /** "https://host[:port][/path]" -> base without trailing slash or "/api/v1". Null if unacceptable. */
    fun normalizeBase(raw: String?): String? {
        var t = raw?.trim() ?: return null
        if (t.isEmpty() || t.any { it.isWhitespace() || it.isISOControl() }) return null
        t = t.trimEnd('/')
        if (t.endsWith("/api/v1")) t = t.removeSuffix("/api/v1").trimEnd('/')
        if (!t.startsWith("https://", ignoreCase = true)) return null
        val rest = t.substring(8)
        val authority = rest.substringBefore('/')
        if (authority.isEmpty() || authority.contains('@')) return null
        val host = authority.substringBefore(':')
        if (host.isEmpty() || !host.contains('.') && host != "localhost") return null
        if (authority.contains(':')) {
            val port = authority.substringAfter(':').toIntOrNull() ?: return null
            if (port !in 1..65535) return null
        }
        return t
    }
}
