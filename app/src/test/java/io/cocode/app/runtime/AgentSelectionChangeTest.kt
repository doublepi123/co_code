package io.cocode.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reusable agent diff is independent of the server choice. Adding an agent must never schedule
 * a removal, and removing one must never schedule an install.
 */
class AgentSelectionChangeTest {
    @Test
    fun `adding an agent only produces an addition`() {
        val change = agentSelectionChange(selected = setOf("codex", "opencode"), installed = setOf("codex"))

        assertEquals(setOf("opencode"), change.additions)
        assertTrue("an existing agent must not be removed when another is added", change.removals.isEmpty())
        assertFalse(change.isEmpty)
    }

    @Test
    fun `removing an agent only produces a removal`() {
        val change = agentSelectionChange(selected = setOf("codex"), installed = setOf("codex", "opencode"))

        assertTrue("a remaining agent must not be reinstalled", change.additions.isEmpty())
        assertEquals(setOf("opencode"), change.removals)
        assertFalse(change.isEmpty)
    }

    @Test
    fun `swapping agents reports both sides`() {
        val change = agentSelectionChange(selected = setOf("codex", "claude"), installed = setOf("opencode"))

        assertEquals(setOf("codex", "claude"), change.additions)
        assertEquals(setOf("opencode"), change.removals)
    }

    @Test
    fun `an unchanged selection is empty`() {
        val change =
            agentSelectionChange(
                selected = setOf("codex", "opencode"),
                installed = setOf("codex", "opencode"),
            )

        assertTrue(change.isEmpty)
    }

    @Test
    fun `a first install is all additions`() {
        val change = agentSelectionChange(selected = setOf("opencode"), installed = emptySet())

        assertEquals(setOf("opencode"), change.additions)
        assertTrue(change.removals.isEmpty())
    }
}
