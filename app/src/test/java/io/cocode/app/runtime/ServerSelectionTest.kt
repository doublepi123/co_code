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
    fun `the default server state is T3 with nothing installed`() {
        assertEquals(ServersState(setOf(ServerKind.T3), emptyMap(), ServerKind.T3), ServersState())
    }

    @Test
    fun `retired persisted server selection adopts Cox without deleting preferences`() {
        val root = temporaryFolder.newFolder("legacy-runtime")
        java.io.File(root, "server-selection").writeText("selected=openchamber\nactive=openchamber\n")
        val store = FileServerSelectionStore(root)
        assertEquals(ServerSelection(), store.load())
        assertEquals(ServerSelection(), store.storedSelection())
        store.save(store.load())
        assertEquals("selected=t3\nactive=t3\n", java.io.File(root, "server-selection").readText())
        assertEquals(null, ServerKind.fromId("openchamber"))
        assertTrue(requiredAgentsForServers(setOf(ServerKind.T3), emptySet(), setOf("opencode")).isEmpty())
    }
}
