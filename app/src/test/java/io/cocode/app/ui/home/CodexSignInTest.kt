package io.cocode.app.ui.home

import io.cocode.app.agentlogin.CodexLoginState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSignInTest {
    @Test
    fun `countdown shows minutes and zero padded seconds`() {
        val now = 1_000_000L
        assertEquals("15:00", formatCountdown(now + 15 * 60_000L, now))
        assertEquals("14:59", formatCountdown(now + 14 * 60_000L + 59_000L, now))
        assertEquals("0:05", formatCountdown(now + 5_000L, now))
    }

    @Test
    fun `a partial second still counts as a second, expiry never goes negative`() {
        val now = 1_000_000L
        assertEquals("0:01", formatCountdown(now + 1L, now))
        assertEquals("0:00", formatCountdown(now, now))
        assertEquals("0:00", formatCountdown(now - 90_000L, now))
    }

    @Test
    fun `only starting, waiting and verifying count as a running flow`() {
        assertTrue(CodexLoginState.Starting.isRunning)
        assertTrue(CodexLoginState.WaitingForUser("https://auth.openai.com/codex/device", "ABCD-EFGH", 0L).isRunning)
        assertTrue(CodexLoginState.Verifying.isRunning)
        assertFalse(CodexLoginState.Idle.isRunning)
        assertFalse(CodexLoginState.SignedIn("Logged in using ChatGPT").isRunning)
        assertFalse(CodexLoginState.Failed("x", null).isRunning)
        assertFalse(CodexLoginState.Cancelled.isRunning)
        assertFalse(CodexLoginState.Idle.isActive)
        assertTrue(CodexLoginState.Cancelled.isActive)
    }
}
