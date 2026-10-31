package io.cocode.app.runtime.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleServerCleanupTest {
    @Test
    fun `port wait returns as soon as the port frees`() {
        var checks = 0
        var slept = 0L
        val free =
            StaleServerCleanup.awaitPortFree(
                port = 3773,
                timeoutMillis = 5_000L,
                isPortFree = { checks++ >= 2 },
                sleep = { slept += it },
                now = { slept },
            )

        assertTrue(free)
        assertEquals(3, checks)
    }

    @Test
    fun `port wait gives up after the timeout`() {
        var clock = 0L
        val free =
            StaleServerCleanup.awaitPortFree(
                port = 3773,
                timeoutMillis = 1_000L,
                isPortFree = { false },
                sleep = { clock += it },
                now = { clock },
            )

        assertFalse(free)
        assertTrue(clock >= 1_000L)
    }
}
