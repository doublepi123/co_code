package io.cocode.app.runtime.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeWakeCoordinatorTest {
    @Test
    fun `valid idle releases only after native reconciliation and acknowledges exact frame`() {
        val fixture = Fixture()
        fixture.coordinator.launchStarted(7, NONCE)
        fixture.coordinator.observeService(busy = false, serverRunning = true, transitionRequiresLock = false)
        assertTrue(fixture.held)

        var ack: String? = null
        fixture.line(7, 1, "idle") { value ->
            assertFalse(fixture.held)
            ack = value
        }

        assertEquals("@cox-android-wake/1 $NONCE 1 ack\n", ack)
        assertFalse(fixture.held)
    }

    @Test
    fun `hold is acquired before acknowledgement`() {
        val fixture = Fixture()
        fixture.coordinator.launchStarted(3, NONCE)
        fixture.coordinator.observeService(busy = false, serverRunning = true, transitionRequiresLock = false)
        fixture.line(3, 1, "idle") {}
        assertFalse(fixture.held)

        fixture.line(3, 2, "hold") { assertTrue(fixture.held) }
        assertTrue(fixture.held)
    }

    @Test
    fun `a live Cox launch acquires before a hold acknowledgement even if the service state lags`() {
        val fixture = Fixture()
        fixture.coordinator.launchStarted(7, NONCE)
        fixture.line(7, 1, "idle") {}
        fixture.coordinator.observeService(busy = false, serverRunning = false, transitionRequiresLock = false)
        assertFalse(fixture.held)

        fixture.line(7, 2, "hold") { assertTrue(fixture.held) }
        fixture.coordinator.stdoutClosed(7)
        assertTrue(fixture.held)
        fixture.coordinator.launchStopped(7)
        assertFalse(fixture.held)
    }

    @Test
    fun `native busy state wins over a Cox idle frame and serializes with its acknowledgement`() {
        val fixture = Fixture()
        fixture.coordinator.launchStarted(4, NONCE)
        fixture.coordinator.observeService(busy = false, serverRunning = true, transitionRequiresLock = false)
        fixture.line(4, 1, "idle") {}
        fixture.coordinator.observeService(busy = true, serverRunning = true, transitionRequiresLock = false)
        assertTrue(fixture.held)

        fixture.line(4, 2, "idle") { assertTrue(fixture.held) }
        assertTrue(fixture.held)
        fixture.coordinator.observeService(busy = false, serverRunning = true, transitionRequiresLock = false)
        assertFalse(fixture.held)
    }

    @Test
    fun `invalid frame after idle fails closed and ignores later frames`() {
        val fixture = Fixture()
        fixture.coordinator.launchStarted(5, NONCE)
        fixture.coordinator.observeService(busy = false, serverRunning = true, transitionRequiresLock = false)
        fixture.line(5, 1, "idle") {}
        assertFalse(fixture.held)

        var acknowledgements = 0
        assertTrue(
            fixture.coordinator.consumeStdoutLine(
                5,
                "@cox-android-wake/1 $NONCE 2 awake",
            ) { acknowledgements++ },
        )
        assertTrue(fixture.held)
        fixture.line(5, 3, "idle") { acknowledgements++ }
        assertEquals(0, acknowledgements)
        assertTrue(fixture.held)
    }

    @Test
    fun `stdout EOF fails closed while the server remains running`() {
        val fixture = Fixture()
        fixture.coordinator.launchStarted(6, NONCE)
        fixture.coordinator.observeService(busy = false, serverRunning = true, transitionRequiresLock = false)
        fixture.line(6, 1, "idle") {}
        assertFalse(fixture.held)

        fixture.coordinator.stdoutClosed(6)
        assertTrue(fixture.held)
    }

    @Test
    fun `failed acknowledgement restores a held lock and rejects later idle frames`() {
        val fixture = Fixture()
        fixture.coordinator.launchStarted(8, NONCE)
        fixture.coordinator.observeService(busy = false, serverRunning = true, transitionRequiresLock = false)
        fixture.line(8, 1, "idle") {}
        assertFalse(fixture.held)

        fixture.line(8, 2, "idle") { throw java.io.IOException("closed child stdin") }
        assertTrue(fixture.held)
        fixture.line(8, 3, "idle") {}
        assertTrue(fixture.held)
    }

    @Test
    fun `wrong nonce is consumed rejected and never acknowledged`() {
        val fixture = Fixture()
        fixture.coordinator.launchStarted(9, NONCE)
        fixture.coordinator.observeService(busy = false, serverRunning = true, transitionRequiresLock = false)
        var acknowledgements = 0

        assertTrue(
            fixture.coordinator.consumeStdoutLine(
                9,
                "@cox-android-wake/1 fedcba9876543210fedcba9876543210 1 idle",
            ) { acknowledgements++ },
        )
        assertEquals(0, acknowledgements)
        assertTrue(fixture.held)
    }

    @Test
    fun `stale launch frames cannot change replacement launch or receive acknowledgements`() {
        val fixture = Fixture()
        fixture.coordinator.launchStarted(10, NONCE)
        fixture.coordinator.observeService(busy = false, serverRunning = true, transitionRequiresLock = false)
        fixture.line(10, 1, "idle") {}
        assertFalse(fixture.held)

        val nextNonce = "fedcba9876543210fedcba9876543210"
        fixture.coordinator.launchStarted(11, nextNonce)
        var acknowledgements = 0
        assertTrue(
            fixture.coordinator.consumeStdoutLine(
                10,
                "@cox-android-wake/1 $NONCE 2 idle",
            ) { acknowledgements++ },
        )
        assertEquals(0, acknowledgements)
        assertTrue(fixture.held)
    }

    @Test
    fun `legacy launch without protocol retains wake lock`() {
        val fixture = Fixture()
        fixture.coordinator.launchStarted(12, null)
        fixture.coordinator.observeService(busy = false, serverRunning = true, transitionRequiresLock = false)
        assertTrue(fixture.held)
    }

    @Test
    fun `first sequence must be one and ordinary output remains diagnostic`() {
        val fixture = Fixture()
        fixture.coordinator.launchStarted(13, NONCE)
        fixture.coordinator.observeService(busy = false, serverRunning = true, transitionRequiresLock = false)
        var acknowledgements = 0
        assertFalse(fixture.coordinator.consumeStdoutLine(13, "Cox is listening") { acknowledgements++ })
        fixture.line(13, 2, "idle") { acknowledgements++ }
        assertEquals(0, acknowledgements)
        assertTrue(fixture.held)
    }

    @Test
    fun `nonce is exactly 128 bits encoded as lowercase hex`() {
        repeat(10) {
            assertTrue(Regex("[0-9a-f]{32}").matches(RuntimeWakeCoordinator.newNonce()))
        }
    }

    private class Fixture {
        var held = false
        val coordinator = RuntimeWakeCoordinator { held = it }

        fun line(id: Long, sequence: Int, mode: String, ack: (String) -> Unit) {
            assertTrue(
                coordinator.consumeStdoutLine(
                    id,
                    "@cox-android-wake/1 $NONCE $sequence $mode",
                    ack,
                ),
            )
        }
    }

    private companion object {
        const val NONCE = "0123456789abcdef0123456789abcdef"
    }
}
