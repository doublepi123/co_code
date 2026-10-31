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

    private fun openChamber(
        status: String = "ok",
        serverId: String? = "server-1",
        ready: Boolean = true,
        running: Boolean = true,
        error: String? = null,
    ) = HealthReport(
        kind = ServerKind.OpenChamber,
        status = status,
        serverId = serverId,
        openCodeReady = ready,
        openCodeRunning = running,
        lastOpenCodeError = error,
    )

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
    fun `openchamber has its own exact listen marker and never accepts the t3 line`() {
        val line = "OpenChamber server listening on 127.0.0.1:3000"
        assertEquals(line, Readiness.listenMarker(3000, ServerKind.OpenChamber))
        assertTrue(Readiness.childListening(listOf(line), 3000, ServerKind.OpenChamber))
        assertFalse(Readiness.childListening(listOf(line), 3000))
        assertFalse(Readiness.childListening(listOf("Listening on http://127.0.0.1:3000"), 3000, ServerKind.OpenChamber))
        assertFalse(Readiness.childListening(listOf(line), 3773, ServerKind.OpenChamber))
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

    @Test
    fun `health body parses the openchamber health document`() {
        val parsed =
            HealthProbe.parse(
                """{"status":"ok","openchamberVersion":"2.5.3","serverId":"srv-1","isOpenCodeReady":true,"openCodeRunning":true}""",
                ServerKind.OpenChamber,
            )!!

        assertEquals(ServerKind.OpenChamber, parsed.kind)
        assertEquals("ok", parsed.status)
        assertEquals("srv-1", parsed.serverId)
        assertEquals("srv-1", parsed.identity)
        assertEquals("2.5.3", parsed.serverVersion)
        assertTrue(parsed.serverOk)
        assertTrue(parsed.ready)
        assertFalse(parsed.openCodeFailed)
    }

    @Test
    fun `openchamber is not ready until opencode is ready`() {
        assertEquals(Verdict.Waiting, Readiness.verdict(openChamber(ready = false)))
        assertFalse(openChamber(ready = false).ready)
    }

    @Test
    fun `a missing openchamber server id is not a usable identity`() {
        assertFalse(openChamber(serverId = null).serverOk)
        assertFalse(openChamber(status = "error").serverOk)
    }

    @Test
    fun `a reported opencode failure fails readiness clearly instead of waiting`() {
        val verdict = Readiness.verdict(openChamber(ready = false, running = false, error = "opencode exited"))
        val failed = verdict as Verdict.Failed

        assertTrue(failed.reason.contains("OpenChamber"))
        assertTrue(failed.reason.contains("opencode exited"))
    }

    @Test
    fun `a non-json body is not a health report`() {
        assertNull(HealthProbe.parse("<html>not t3</html>"))
        assertFalse(HealthProbe.parse("""{"serverVersion":"0.1.6-pw.5"}""")!!.serverOk)
        assertNull(HealthProbe.parse("<html>not openchamber</html>", ServerKind.OpenChamber))
    }

    @Test
    fun `health paths are explicit per kind`() {
        assertEquals("/.well-known/t3/environment", HealthProbe.healthPath(ServerKind.T3))
        assertEquals("/health", HealthProbe.healthPath(ServerKind.OpenChamber))
    }
}
