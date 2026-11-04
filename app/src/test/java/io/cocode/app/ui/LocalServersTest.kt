package io.cocode.app.ui

import io.cocode.app.runtime.ServerKind.T3
import io.cocode.app.runtime.ServersState
import io.cocode.app.ui.web.WebViewSlot
import org.junit.Assert.*
import org.junit.Test

class LocalServersTest {
    @Test
    fun `Cox and original local identities are accepted`() {
        assertEquals(T3, localKindOf("local-t3"))
        assertEquals(T3, localKindOf("local"))
        assertNull(localKindOf("local-openchamber"))
        assertNull(localKindOf(null))
        assertEquals("local-t3", migratedTargetId("local"))
    }

    @Test
    fun `only Cox owns the local WebView slot`() {
        assertEquals(WebViewSlot.Local, WebViewSlot.of("local-t3"))
        assertEquals(WebViewSlot.Remote, WebViewSlot.of("local-openchamber"))
        assertEquals(WebViewSlot.Remote, WebViewSlot.of("r1"))
    }

    @Test
    fun `launch chooses Cox and never restores a retired local server`() {
        assertEquals(T3, ServersState(installed = mapOf(T3 to "1")).openableKind())
        assertEquals(T3, localKindToOpen(null, ServersState()))
        assertEquals(ResumeTarget.Local(), resumeTarget("local-openchamber", emptySet()))
        assertEquals(ResumeTarget.Local(T3), resumeTarget("local", emptySet()))
        assertEquals(ResumeTarget.Remote("r1"), resumeTarget("r1", setOf("r1")))
    }

    @Test
    fun `Cox alone qualifies as a local page for screen awake`() {
        assertFalse(ScreenAwakePolicy.remoteInView("local-t3"))
        assertFalse(ScreenAwakePolicy.remoteInView("local"))
        assertTrue(ScreenAwakePolicy.remoteInView("local-openchamber"))
        assertTrue(ScreenAwakePolicy.remoteInView("r1"))
    }
}
