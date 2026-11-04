package io.cocode.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Cox retains its historical app-private password and log paths. */
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

}
