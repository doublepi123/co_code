package io.cocode.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Per-kind secrets and paths: T3 keeps its historical files; OpenChamber gets its own. */
class RuntimeSecretsTest {
    @get:Rule val folder = TemporaryFolder()

    private val paths get() = RuntimePaths(folder.root)

    @Test
    fun `t3 keeps the historical password and log file names`() {
        assertEquals("ui-password", paths.uiPasswordFile.name)
        assertEquals("t3.log", paths.serverLog.name)
        assertEquals(paths.uiPasswordFile, paths.uiPasswordFile(ServerKind.T3))
        assertEquals(paths.serverLog, paths.serverLog(ServerKind.T3))
    }

    @Test
    fun `openchamber uses separate password and log files`() {
        assertEquals("openchamber-ui-password", paths.uiPasswordFile(ServerKind.OpenChamber).name)
        assertEquals("openchamber.log", paths.serverLog(ServerKind.OpenChamber).name)
        assertNotEquals(paths.uiPasswordFile(ServerKind.T3), paths.uiPasswordFile(ServerKind.OpenChamber))
        assertNotEquals(paths.serverLog(ServerKind.T3), paths.serverLog(ServerKind.OpenChamber))
    }

    @Test
    fun `passwords are generated once per kind and never reused across kinds`() {
        assertNull(RuntimeSecrets.existingUiPassword(paths, ServerKind.T3))

        val t3 = RuntimeSecrets.uiPassword(paths)
        val oc = RuntimeSecrets.uiPassword(paths, ServerKind.OpenChamber)

        assertNotEquals(t3, oc)
        assertTrue(t3.length >= 8)
        assertEquals(t3, RuntimeSecrets.existingUiPassword(paths, ServerKind.T3))
        assertEquals(oc, RuntimeSecrets.existingUiPassword(paths, ServerKind.OpenChamber))
        assertEquals(t3, RuntimeSecrets.uiPassword(paths, ServerKind.T3))
        assertEquals(oc, RuntimeSecrets.uiPassword(paths, ServerKind.OpenChamber))
    }

    @Test
    fun `an existing t3 password file stays authoritative`() {
        paths.root.mkdirs()
        paths.uiPasswordFile.writeText("legacy-t3-secret\n")

        assertEquals("legacy-t3-secret", RuntimeSecrets.uiPassword(paths))
        assertFalse(paths.uiPasswordFile(ServerKind.OpenChamber).exists())
        assertNull(RuntimeSecrets.existingUiPassword(paths, ServerKind.OpenChamber))
    }
}
