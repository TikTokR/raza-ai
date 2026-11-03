package com.raza.ai.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTest {
    // ---------- WhatsApp screen matching ----------
    @Test fun whatsAppSendButtonByIdOrLabel() {
        assertTrue(UiMatchers.isWhatsAppSend("com.whatsapp:id/send", null))
        assertTrue(UiMatchers.isWhatsAppSend("com.whatsapp.w4b:id/send", "anything"))
        assertTrue(UiMatchers.isWhatsAppSend(null, "Send"))
        assertTrue(UiMatchers.isWhatsAppSend(null, " send "))
    }

    @Test fun whatsAppVoiceAndOtherButtonsAreNotSend() {
        assertFalse(UiMatchers.isWhatsAppSend("com.whatsapp:id/voice_note_btn", "Voice message"))
        assertFalse(UiMatchers.isWhatsAppSend(null, "Send to..."))
        assertFalse(UiMatchers.isWhatsAppSend(null, null))
    }

    @Test fun whatsAppInputById() {
        assertTrue(UiMatchers.isWhatsAppInput("com.whatsapp:id/entry"))
        assertFalse(UiMatchers.isWhatsAppInput("com.whatsapp:id/send"))
        assertFalse(UiMatchers.isWhatsAppInput(null))
    }

    @Test fun sendIsOnlyPressedWhenTheBoxHoldsTheConfirmedMessage() {
        assertTrue(UiMatchers.inputHoldsMessage("I will be late", "I will be late"))
        assertTrue(UiMatchers.inputHoldsMessage("  I will be late ", "I will be late"))
        // a different chat's old draft must not match
        assertFalse(UiMatchers.inputHoldsMessage("see you tomorrow", "I will be late"))
        assertFalse(UiMatchers.inputHoldsMessage("", "I will be late"))
        assertFalse(UiMatchers.inputHoldsMessage(null, "I will be late"))
        assertFalse(UiMatchers.inputHoldsMessage("anything", "   "))
    }

    @Test fun longMessagesMatchOnTheirFirstThirtyCharacters() {
        val body = "This is a long message that WhatsApp may wrap or trim at the end"
        assertTrue(UiMatchers.inputHoldsMessage(body, body))
        assertTrue(UiMatchers.inputHoldsMessage(body.take(45), body))
        assertFalse(UiMatchers.inputHoldsMessage("This is a different long message that differs early", body))
    }

    // ---------- YouTube ----------
    @Test fun youTubeVideoResultsOnly() {
        assertTrue(UiMatchers.isYouTubeVideoResult("Despacito - 4 minutes, 41 seconds - Go to channel - Luis Fonsi - 8.2B views - 8 years ago - play video"))
        assertFalse(UiMatchers.isYouTubeVideoResult("Despacito - 0 minutes, 58 seconds - play Short"))
        assertFalse(UiMatchers.isYouTubeVideoResult("Search"))
        assertFalse(UiMatchers.isYouTubeVideoResult(null))
    }

    // ---------- swipe geometry ----------
    @Test fun nextVideoAndScrollDownSwipeUpPreviousAndScrollUpSwipeDown() {
        val up = SwipePlan.forKind(NavKind.NEXT_VIDEO, 1080, 2400)!!
        assertEquals(540f, up.x1, 0.01f)
        assertEquals(up.x1, up.x2, 0.01f)
        assertTrue("finger moves up", up.y2 < up.y1)
        assertEquals(up, SwipePlan.forKind(NavKind.SCROLL_DOWN, 1080, 2400))
        val down = SwipePlan.forKind(NavKind.PREVIOUS_VIDEO, 1080, 2400)!!
        assertTrue("finger moves down", down.y2 > down.y1)
        assertEquals(down, SwipePlan.forKind(NavKind.SCROLL_UP, 1080, 2400))
    }

    @Test fun swipeStaysInsideTheScreenAndHasSensibleDuration() {
        for (k in listOf(NavKind.NEXT_VIDEO, NavKind.PREVIOUS_VIDEO)) {
            val s = SwipePlan.forKind(k, 720, 1600)!!
            assertTrue(s.y1 in 0f..1600f && s.y2 in 0f..1600f && s.x1 in 0f..720f)
            assertTrue(s.durationMs in 100..800)
        }
    }

    @Test fun globalActionsHaveNoSwipeAndBadScreenSizesAreRejected() {
        assertNull(SwipePlan.forKind(NavKind.BACK, 1080, 2400))
        assertNull(SwipePlan.forKind(NavKind.HOME, 1080, 2400))
        assertNull(SwipePlan.forKind(NavKind.RECENTS, 1080, 2400))
        assertNull(SwipePlan.forKind(NavKind.NEXT_VIDEO, 0, 2400))
        assertNotNull(SwipePlan.forKind(NavKind.SCROLL_UP, 1, 1))
    }
}
