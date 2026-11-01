package io.cocode.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Four 100 px rows with a 1 px divider between them: tops 0, 101, 202, 303.
 */
class ServerOrderGeometryTest {
    private val tops = listOf(0f, 101f, 202f, 303f)
    private val heights = listOf(100f, 100f, 100f, 100f)

    private fun target(
        from: Int,
        offset: Float,
    ) = ServerOrderGeometry.targetIndex(from, offset, tops, heights)

    @Test
    fun `a small drag stays in place`() {
        assertEquals(0, target(0, 0f))
        assertEquals(0, target(0, 50f))
        assertEquals(2, target(2, -50f))
    }

    @Test
    fun `crossing the next row's middle moves one place`() {
        assertEquals(1, target(0, 102f))
        assertEquals(2, target(0, 203f))
        assertEquals(3, target(0, 304f))
        assertEquals(2, target(3, -102f))
        assertEquals(0, target(3, -304f))
    }

    @Test
    fun `the dragged row can't leave the list`() {
        assertEquals(0f, ServerOrderGeometry.clampOffset(0, -40f, tops, heights))
        assertEquals(303f, ServerOrderGeometry.clampOffset(0, 1_000f, tops, heights))
        assertEquals(-303f, ServerOrderGeometry.clampOffset(3, -1_000f, tops, heights))
        assertEquals(0f, ServerOrderGeometry.clampOffset(3, 40f, tops, heights))
    }

    @Test
    fun `a row held against either end reaches the end slot`() {
        val down = ServerOrderGeometry.clampOffset(0, 1_000f, tops, heights)
        assertEquals(3, target(0, down))
        val up = ServerOrderGeometry.clampOffset(3, -1_000f, tops, heights)
        assertEquals(0, target(3, up))
    }

    @Test
    fun `rows step aside by one row and gap`() {
        // Row 0 dragged to slot 2: rows 1 and 2 step up, row 3 stays.
        val down = (0..3).map { ServerOrderGeometry.shift(it, 0, 2, tops, heights) }
        assertEquals(listOf(0f, -101f, -101f, 0f), down)
        // Row 3 dragged to slot 1: rows 1 and 2 step down, row 0 stays.
        val up = (0..3).map { ServerOrderGeometry.shift(it, 3, 1, tops, heights) }
        assertEquals(listOf(0f, 101f, 101f, 0f), up)
        // No move: nobody steps aside.
        assertEquals(listOf(0f, 0f, 0f, 0f), (0..3).map { ServerOrderGeometry.shift(it, 1, 1, tops, heights) })
    }

    @Test
    fun `the moved row settles exactly into its new slot`() {
        assertEquals(101f, ServerOrderGeometry.settleOffset(0, 1, tops, heights))
        assertEquals(303f, ServerOrderGeometry.settleOffset(0, 3, tops, heights))
        assertEquals(-202f, ServerOrderGeometry.settleOffset(2, 0, tops, heights))
        assertEquals(0f, ServerOrderGeometry.settleOffset(2, 2, tops, heights))
    }

    @Test
    fun `rows of different heights settle flush`() {
        val t = listOf(0f, 81f, 202f)
        val h = listOf(80f, 120f, 60f)
        // Short row 0 to the end: its bottom lines up with the last row's bottom.
        assertEquals(202f + 60f - 80f, ServerOrderGeometry.settleOffset(0, 2, t, h))
        // Last row to the top: its top lines up with the first row's top.
        assertEquals(-202f, ServerOrderGeometry.settleOffset(2, 0, t, h))
    }

    @Test
    fun `unknown rows are left alone`() {
        assertEquals(7, ServerOrderGeometry.targetIndex(7, 500f, tops, heights))
        assertEquals(0f, ServerOrderGeometry.clampOffset(7, 500f, tops, heights))
        assertEquals(0f, ServerOrderGeometry.settleOffset(7, 0, tops, heights))
    }
}
