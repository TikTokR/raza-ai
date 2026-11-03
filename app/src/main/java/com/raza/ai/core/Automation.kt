package com.raza.ai.core

/** What the accessibility automation looks for on other apps' screens. Pure functions so they can be unit-tested. */
object UiMatchers {
    /** WhatsApp's send button (id ends with ":id/send", or the label is exactly "Send"). */
    fun isWhatsAppSend(viewId: String?, description: CharSequence?): Boolean =
        viewId?.endsWith(":id/send") == true || description?.toString()?.trim().equals("send", ignoreCase = true)

    /** WhatsApp's message box. */
    fun isWhatsAppInput(viewId: String?): Boolean = viewId?.endsWith(":id/entry") == true

    /**
     * True only when the message box already holds the text the user confirmed. This is the guard that stops RAZA
     * pressing Send on some other chat's draft while WhatsApp is still switching screens.
     */
    fun inputHoldsMessage(inputText: CharSequence?, body: String): Boolean {
        val want = body.trim().take(30)
        if (want.isEmpty()) return false
        return inputText?.toString()?.trim()?.startsWith(want) == true
    }

    /** A YouTube search-result row (its accessibility label ends with "play video"). Shorts and ads do not match. */
    fun isYouTubeVideoResult(description: CharSequence?): Boolean =
        description?.toString()?.contains("play video", ignoreCase = true) == true
}

data class Swipe(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durationMs: Long)

object SwipePlan {
    /** Finger movement for a scroll or next/previous video; null for actions Android performs globally. */
    fun forKind(kind: NavKind, width: Int, height: Int): Swipe? {
        if (width <= 0 || height <= 0) return null
        val x = width / 2f
        return when (kind) {
            NavKind.SCROLL_DOWN, NavKind.NEXT_VIDEO -> Swipe(x, height * 0.75f, x, height * 0.25f, 300)
            NavKind.SCROLL_UP, NavKind.PREVIOUS_VIDEO -> Swipe(x, height * 0.25f, x, height * 0.75f, 300)
            NavKind.BACK, NavKind.HOME, NavKind.RECENTS -> null
        }
    }
}
