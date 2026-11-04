package io.cocode.app.runtime.process

import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.process.Readiness.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {
    private fun report(
        environmentId: String? = "env-1",
        version: String? = "0.1.6-pw.5",
    ) = HealthReport(environmentId, version)

    @Test
    fun `an environment document is ready`() {
        assertEquals(Verdict.Ready, Readiness.verdict(report()))
    }

    @Test
    fun `no answer or no identity keeps waiting`() {
        assertEquals(Verdict.Waiting, Readiness.verdict(null))
        assertEquals(Verdict.Waiting, Readiness.verdict(report(environmentId = null)))
        assertEquals(Verdict.Waiting, Readiness.verdict(report(environmentId = " ")))
    }

    @Test
    fun `identity is stable when the identity does not change`() {
        assertTrue(Readiness.identityStable(listOf("a", "a", null, "a")))
        assertTrue(Readiness.identityStable(listOf(null, null)))
    }

    @Test
    fun `identity changing between polls means another instance answered`() {
        assertFalse(Readiness.identityStable(listOf("a", "b")))
    }

    @Test
    fun `listen marker is the t3 pino line`() {
        val line = "[12:00:00.000] INFO (#123): Listening on http://127.0.0.1:3773"
        assertEquals("Listening on http://127.0.0.1:3773", Readiness.listenMarker(3773))
        assertTrue(Readiness.childListening(listOf(line), 3773))
        assertFalse(Readiness.childListening(listOf(line), 3774))
    }

    @Test
    fun `health body parses the t3 environment document`() {
        val parsed =
            HealthProbe.parse(
                """{"environmentId":"5b0c8f4e-1a2b-4c3d-8e9f-0123456789ab","serverVersion":"0.1.6-pw.5","capabilities":{}}""",
            )!!

        assertEquals(ServerKind.T3, parsed.kind)
        assertEquals("5b0c8f4e-1a2b-4c3d-8e9f-0123456789ab", parsed.environmentId)
        assertEquals("0.1.6-pw.5", parsed.serverVersion)
        assertTrue(parsed.serverOk)
        assertTrue(parsed.ready)
    }

}
