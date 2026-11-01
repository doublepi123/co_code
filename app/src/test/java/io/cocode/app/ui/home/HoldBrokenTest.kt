package io.cocode.app.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a touch-and-hold on a server row gives up. The row is clickable, and a clickable consumes
 * the down in the Main pass, so the hold sees its own down already consumed in the Final pass:
 * that must not end the hold (it did, and dragging never started).
 */
class HoldBrokenTest {
    private val start = Offset(100f, 100f)
    private val slop = 8f

    private fun change(
        position: Offset = start,
        pressed: Boolean = true,
        previousPressed: Boolean = true,
        consumed: Boolean = false,
    ) = PointerInputChange(
        id = PointerId(1),
        uptimeMillis = 10,
        position = position,
        pressed = pressed,
        previousUptimeMillis = 0,
        previousPosition = start,
        previousPressed = previousPressed,
        isInitiallyConsumed = false,
    ).also { if (consumed) it.consume() }

    @Test
    fun `its own down, consumed by the row's click, keeps the hold`() {
        assertFalse(holdBroken(change(previousPressed = false, consumed = true), start, slop))
        assertFalse(holdBroken(change(previousPressed = false), start, slop))
    }

    @Test
    fun `a still finger or a wobble inside slop keeps the hold`() {
        assertFalse(holdBroken(change(), start, slop))
        assertFalse(holdBroken(change(position = start + Offset(3f, 4f)), start, slop))
    }

    @Test
    fun `lifting, leaving, moving past slop or a scroll taking the move ends it`() {
        assertTrue(holdBroken(null, start, slop))
        assertTrue(holdBroken(change(pressed = false), start, slop))
        assertTrue(holdBroken(change(position = start + Offset(0f, 9f)), start, slop))
        assertTrue(holdBroken(change(position = start + Offset(0f, 2f), consumed = true), start, slop))
    }
}
