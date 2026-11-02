package com.raza.ai.core

import java.util.Locale

enum class Reply { YES, NO, UNCLEAR }

/** Understands spoken or typed yes/no in English and Urdu. "No" always wins when both appear. */
object ConfirmationReply {
    private val NO_WORDS = setOf(
        "no", "nope", "nah", "cancel", "stop", "dont", "don't", "abort", "negative",
        "nahi", "nahin", "nai", "نہیں", "نہی", "منسوخ", "رکو", "روکو",
    )
    private val YES_PHRASES = setOf(
        "yes", "yeah", "yep", "yup", "confirm", "ok", "okay", "go ahead", "do it", "sure", "proceed",
        "haan", "han", "ji", "ji haan", "ji han", "theek hai", "ہاں", "جی", "جی ہاں", "ٹھیک ہے", "کرو", "کر دو",
    )
    private val YES_FIRST = setOf("yes", "yeah", "yep", "haan", "han", "ہاں", "جی")

    fun parse(raw: String): Reply {
        val tokens = tokens(raw)
        if (tokens.isEmpty()) return Reply.UNCLEAR
        if (tokens.any { it in NO_WORDS }) return Reply.NO
        val joined = tokens.joinToString(" ")
        if (joined in YES_PHRASES) return Reply.YES
        if (tokens.size <= 4 && tokens.first() in YES_FIRST) return Reply.YES
        return Reply.UNCLEAR
    }

    private fun tokens(raw: String): List<String> =
        raw.lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit() || it == '\'' || it.isWhitespace()) it else ' ' }
            .joinToString("")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
}

/**
 * Holds at most one proposed action. A sensitive action runs ONLY after confirm() returns
 * Execute for the current proposal id. Proposals expire, are single-use, and a new
 * proposal discards the old one so a stale button can never run the wrong action.
 */
class ConfirmationManager(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }

    data class Pending(
        val id: Long,
        val action: DeviceAction,
        val needsAuth: Boolean,
        val authenticated: Boolean,
        val createdAt: Long,
    )

    sealed class Decision {
        data class Execute(val action: DeviceAction) : Decision()
        data class NeedsAuth(val pending: Pending) : Decision()
        data object Rejected : Decision()
        data object Expired : Decision()
        data object NothingPending : Decision()
        data object Unclear : Decision()
    }

    private var pending: Pending? = null
    private var nextId = 1L

    @Synchronized
    fun propose(action: DeviceAction, authAvailable: Boolean): Pending {
        val p = Pending(
            id = nextId++,
            action = action,
            needsAuth = action.needsOwnerAuth && authAvailable,
            authenticated = false,
            createdAt = clock(),
        )
        pending = p
        return p
    }

    @Synchronized
    fun current(): Pending? {
        val p = pending ?: return null
        if (clock() - p.createdAt > timeoutMs) {
            pending = null
            return null
        }
        return p
    }

    @Synchronized
    fun markAuthenticated(id: Long): Boolean {
        val p = current() ?: return false
        if (p.id != id) return false
        pending = p.copy(authenticated = true)
        return true
    }

    @Synchronized
    fun confirm(id: Long): Decision {
        val p = pending ?: return Decision.NothingPending
        if (clock() - p.createdAt > timeoutMs) {
            pending = null
            return Decision.Expired
        }
        if (p.id != id) return Decision.NothingPending
        if (p.needsAuth && !p.authenticated) return Decision.NeedsAuth(p)
        pending = null
        return Decision.Execute(p.action)
    }

    @Synchronized
    fun reject(): Decision {
        if (pending == null) return Decision.NothingPending
        pending = null
        return Decision.Rejected
    }

    /** Voice/text answer to the current proposal. */
    @Synchronized
    fun respond(text: String): Decision {
        val p = current() ?: return if (pending != null) {
            pending = null
            Decision.Expired
        } else Decision.NothingPending
        return when (ConfirmationReply.parse(text)) {
            Reply.NO -> reject()
            Reply.YES -> confirm(p.id)
            Reply.UNCLEAR -> Decision.Unclear
        }
    }
}
