package io.cocode.app.ui

import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.ServerKind.OpenChamber
import io.cocode.app.runtime.ServerKind.T3
import io.cocode.app.runtime.ServersState
import io.cocode.app.ui.web.WebViewSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalServersTest {
    @Test
    fun `every local id maps to its own kind and the legacy id is T3 Code`() {
        assertEquals(T3, localKindOf("local-t3"))
        assertEquals(OpenChamber, localKindOf("local-openchamber"))
        assertEquals(T3, localKindOf(LEGACY_LOCAL_TARGET_ID))
        assertNull(localKindOf("6f1c2b1e-remote"))
        assertNull(localKindOf(null))
        assertEquals("local-t3", migratedTargetId("local"))
        assertEquals("r1", migratedTargetId("r1"))
    }

    @Test
    fun `both local servers share the local WebView slot, remotes never do`() {
        assertEquals(WebViewSlot.Local, WebViewSlot.of(T3.localTargetId))
        assertEquals(WebViewSlot.Local, WebViewSlot.of(OpenChamber.localTargetId))
        assertEquals(WebViewSlot.Remote, WebViewSlot.of("local-something-else"))
        assertEquals(WebViewSlot.Remote, WebViewSlot.of("r1"))
    }

    @Test
    fun `the active server is opened, never one that is not installed`() {
        assertEquals(OpenChamber, ServersState(installed = mapOf(OpenChamber to "1", T3 to "1"), active = OpenChamber).openableKind())
        // The active one went away: the installed one stands in.
        assertEquals(OpenChamber, ServersState(installed = mapOf(OpenChamber to "1"), active = T3).openableKind())
        // Nothing reported yet: the active one; the runtime state decides.
        assertEquals(T3, ServersState().openableKind())
    }

    @Test
    fun `a launch reopens the remembered local server only while it is installed`() {
        val both = ServersState(installed = mapOf(T3 to "1", OpenChamber to "1"), active = T3)
        assertEquals(OpenChamber, localKindToOpen(OpenChamber, both))
        assertEquals(T3, localKindToOpen(null, both))

        val onlyT3 = ServersState(installed = mapOf(T3 to "1"), active = T3)
        assertEquals(T3, localKindToOpen(OpenChamber, onlyT3))

        // Before anything is reported, a remembered non-active server is not switched to.
        assertEquals(T3, localKindToOpen(OpenChamber, ServersState(active = T3)))
    }

    @Test
    fun `resume keeps which local server was open`() {
        assertEquals(ResumeTarget.Local(OpenChamber), resumeTarget("local-openchamber", emptySet()))
        assertEquals(ResumeTarget.Local(T3), resumeTarget("local", emptySet()))
        assertEquals(ResumeTarget.Local(), resumeTarget(null, setOf("r1")))
        assertEquals(ResumeTarget.Remote("r1"), resumeTarget("r1", setOf("r1")))
    }

    @Test
    fun `any local page keeps the screen awake rule, a remote does not`() {
        assertFalse(ScreenAwakePolicy.remoteInView("local-openchamber"))
        assertFalse(ScreenAwakePolicy.remoteInView("local"))
        assertTrue(ScreenAwakePolicy.remoteInView("r1"))
    }
}
