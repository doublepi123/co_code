package io.cocode.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDraftTest {
    @Test
    fun `before setup unchecking the only agent leaves an empty draft that is not saved`() {
        val draft = AgentDraft.of(saved = setOf("opencode"), installed = emptySet(), beforeInstall = true)

        val (empty, save) = draft.toggle("opencode")

        assertTrue(empty.isEmpty)
        assertNull("an empty selection must never reach the controller", save)
        assertFalse(empty.canSetUp)
        assertFalse(empty.canApply)
        assertNull(empty.applySelection())
    }

    @Test
    fun `before setup picking another agent after an empty draft saves just that agent`() {
        val empty = AgentDraft.of(setOf("opencode"), emptySet(), beforeInstall = true).toggle("opencode").first

        val (next, save) = empty.toggle("codex")

        assertEquals(setOf("codex"), save)
        assertEquals(setOf("codex"), next.checked)
        assertTrue(next.canSetUp)
    }

    @Test
    fun `before setup every non-empty toggle is saved right away`() {
        val draft = AgentDraft.of(setOf("opencode"), emptySet(), beforeInstall = true)

        val (next, save) = draft.toggle("codex")

        assertEquals(setOf("opencode", "codex"), save)
        assertFalse("Apply is only for an installed environment", next.canApply)
    }

    @Test
    fun `after setup adding an agent keeps every installed agent checked`() {
        val draft = AgentDraft.of(saved = setOf("codex"), installed = setOf("codex"), beforeInstall = false)

        val (next, save) = draft.toggle("opencode")

        assertNull("toggles are staged after setup", save)
        assertEquals(setOf("codex", "opencode"), next.applySelection())
        assertEquals(setOf("opencode"), next.additions)
        assertTrue(next.removals.isEmpty())
    }

    @Test
    fun `after setup the baseline is what is installed, not the saved selection`() {
        // A failed change saved {opencode} while {codex} is still what is installed.
        val draft = AgentDraft.of(saved = setOf("opencode"), installed = setOf("codex"), beforeInstall = false)

        assertEquals(setOf("codex"), draft.checked)
        assertFalse(draft.canApply)
        val added = draft.toggle("opencode").first
        assertEquals(setOf("codex", "opencode"), added.applySelection())
    }

    @Test
    fun `after setup unchecking an installed agent is an explicit removal`() {
        val draft = AgentDraft.of(setOf("codex", "opencode"), setOf("codex", "opencode"), beforeInstall = false)

        val next = draft.toggle("opencode").first

        assertEquals(setOf("opencode"), next.removals)
        assertEquals(setOf("codex"), next.applySelection())
    }

    @Test
    fun `after setup an empty draft cannot be applied`() {
        val draft = AgentDraft.of(setOf("opencode"), setOf("opencode"), beforeInstall = false)

        val empty = draft.toggle("opencode").first

        assertTrue(empty.isEmpty)
        assertFalse(empty.canApply)
        assertNull(empty.applySelection())
        // Then checking Codex makes it a replacement: Codex in, OpenCode out.
        val swapped = empty.toggle("codex").first
        assertEquals(setOf("codex"), swapped.applySelection())
        assertEquals(setOf("opencode"), swapped.removals)
    }

    @Test
    fun `after setup undoing a change offers nothing to apply`() {
        val draft = AgentDraft.of(setOf("codex"), setOf("codex"), beforeInstall = false)

        val back = draft.toggle("opencode").first.toggle("opencode").first

        assertFalse(back.canApply)
        assertNull(back.applySelection())
    }

    @Test
    fun `installed environment without recorded agents falls back to the saved selection`() {
        val draft = AgentDraft.of(setOf("opencode"), installed = emptySet(), beforeInstall = false)

        assertEquals(setOf("opencode"), draft.checked)
        assertFalse(draft.canApply)
    }

    @Test
    fun `adding to an installed agent never asks for a removal`() {
        val draft = AgentDraft.of(setOf("codex"), setOf("codex"), beforeInstall = false)

        val added = draft.toggle("opencode").first

        assertFalse("a pure addition is not dangerous", added.needsRemovalConfirm)
        assertEquals(setOf("codex"), added.kept)
        assertEquals(setOf("codex", "opencode"), added.applySelection())
    }

    @Test
    fun `replacing an installed agent asks and lists removed, added and kept agents`() {
        val draft = AgentDraft.of(setOf("codex", "claude"), setOf("codex", "claude"), beforeInstall = false)

        val swapped = draft.toggle("codex").first.toggle("opencode").first

        assertTrue(swapped.needsRemovalConfirm)
        assertEquals(setOf("codex"), swapped.removals)
        assertEquals(setOf("opencode"), swapped.additions)
        assertEquals(setOf("claude"), swapped.kept)
    }

    @Test
    fun `an empty draft never asks, it simply cannot be applied`() {
        val empty = AgentDraft.of(setOf("codex"), setOf("codex"), beforeInstall = false).toggle("codex").first

        assertFalse(empty.needsRemovalConfirm)
    }

    @Test
    fun `before setup nothing is ever a removal`() {
        val draft = AgentDraft.of(setOf("codex", "opencode"), emptySet(), beforeInstall = true)

        val next = draft.toggle("codex").first

        assertFalse(next.needsRemovalConfirm)
        assertTrue(next.removals.isEmpty())
    }

    @Test
    fun `a required agent is checked, cannot be unchecked and is never a removal`() {
        val draft = AgentDraft.of(setOf("codex"), setOf("codex", "opencode"), beforeInstall = false, locked = setOf("opencode"))

        val (same, save) = draft.toggle("opencode")

        assertEquals(draft, same)
        assertNull(save)
        assertTrue("opencode" in draft.checked)
        assertFalse(draft.toggle("codex").first.removals.contains("opencode"))
    }

    @Test
    fun `a required agent the user never picked is not sent as their choice`() {
        // A required dependency is locked; the user picked only Codex before setup.
        val draft = AgentDraft.of(setOf("codex"), emptySet(), beforeInstall = true, locked = setOf("opencode"))

        assertEquals(setOf("codex", "opencode"), draft.checked)
        val (_, save) = draft.toggle("claude")
        assertEquals(setOf("codex", "claude"), save)
    }

    @Test
    fun `only the required agent checked still sends a non-empty selection`() {
        val draft = AgentDraft.of(setOf("codex"), emptySet(), beforeInstall = true, locked = setOf("opencode"))

        val (onlyRequired, save) = draft.toggle("codex")

        assertFalse(onlyRequired.isEmpty)
        assertEquals(setOf("opencode"), save)
    }
}
