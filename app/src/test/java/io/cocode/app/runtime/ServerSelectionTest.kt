package io.cocode.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerSelectionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `the file store round-trips selected and active servers`() {
        val root = temporaryFolder.newFolder("runtime")
        val store = FileServerSelectionStore(root)
        store.save(ServerSelection(setOf(ServerKind.T3, ServerKind.OpenChamber), ServerKind.OpenChamber))

        val loaded = FileServerSelectionStore(root).load()

        assertEquals(setOf(ServerKind.T3, ServerKind.OpenChamber), loaded.selected)
        assertEquals(ServerKind.OpenChamber, loaded.active)
    }

    @Test
    fun `an empty persisted selection falls back to installed servers then T3`() {
        assertEquals(setOf(ServerKind.OpenChamber), sanitizeServerSelection(emptySet(), setOf(ServerKind.OpenChamber)))
        assertEquals(setOf(ServerKind.T3), sanitizeServerSelection(emptySet()))
    }

    @Test
    fun `a non-empty selection is kept as requested`() {
        assertEquals(
            setOf(ServerKind.T3, ServerKind.OpenChamber),
            sanitizeServerSelection(setOf(ServerKind.T3, ServerKind.OpenChamber)),
        )
    }

    @Test
    fun `OpenChamber selection requires the OpenCode agent without making it a user choice`() {
        val known = setOf("codex", "opencode")

        assertEquals(setOf("opencode"), requiredAgentsForServers(setOf(ServerKind.OpenChamber), emptySet(), known))
        assertEquals(setOf("opencode"), requiredAgentsForServers(setOf(ServerKind.T3), setOf(ServerKind.OpenChamber), known))
        assertTrue(requiredAgentsForServers(setOf(ServerKind.T3), emptySet(), known).isEmpty())
    }

    @Test
    fun `the default server state is T3 with nothing installed`() {
        assertEquals(ServersState(setOf(ServerKind.T3), emptyMap(), ServerKind.T3), ServersState())
    }
}
