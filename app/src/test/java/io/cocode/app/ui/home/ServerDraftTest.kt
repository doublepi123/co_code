package io.cocode.app.ui.home

import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.ServerKind.OpenChamber
import io.cocode.app.runtime.ServerKind.T3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerDraftTest {
    @Test
    fun `before setup both servers can be picked and an empty pick is never saved`() {
        val draft = ServerDraft.of(saved = setOf(T3), installed = emptySet<ServerKind>(), beforeInstall = true)

        val (both, saveBoth) = draft.toggle(OpenChamber)
        assertEquals(setOf(T3, OpenChamber), saveBoth)

        val (onlyOc, _) = both.toggle(T3)
        val (empty, saveEmpty) = onlyOc.toggle(OpenChamber)
        assertTrue(empty.isEmpty)
        assertNull("an empty server set must never reach the controller", saveEmpty)
        assertFalse(empty.canSetUp)
    }

    @Test
    fun `adding OpenChamber next to T3 Code applies without a removal`() {
        val draft = ServerDraft.of(setOf(T3), setOf(T3), beforeInstall = false)

        val added = draft.toggle(OpenChamber).first

        assertFalse(added.needsRemovalConfirm)
        assertEquals(setOf(T3, OpenChamber), added.applySelection())
    }

    @Test
    fun `unchecking an installed server is a removal of only that server`() {
        val draft = ServerDraft.of(setOf(T3, OpenChamber), setOf(T3, OpenChamber), beforeInstall = false)

        val next = draft.toggle(T3).first

        assertTrue(next.needsRemovalConfirm)
        assertEquals(setOf(T3), next.removals)
        assertEquals(setOf(OpenChamber), next.applySelection())
    }

    @Test
    fun `an empty server draft after setup cannot be applied`() {
        val empty = ServerDraft.of(setOf(T3), setOf(T3), beforeInstall = false).toggle(T3).first

        assertFalse(empty.canApply)
        assertNull(empty.applySelection())
        assertFalse(empty.needsRemovalConfirm)
    }
}
