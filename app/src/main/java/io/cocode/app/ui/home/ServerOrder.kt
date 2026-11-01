package io.cocode.app.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Where the rows of a vertical list go while one of them is moved. Positions are the rows' laid-out
 * tops and heights in px (translations not included), in the order shown. Pure, so it is unit tested.
 */
internal object ServerOrderGeometry {
    /**
     * The index the moved row would land on: past every row whose middle its own middle has crossed.
     * [offset] is how far the moved row is dragged from its own slot.
     */
    fun targetIndex(
        from: Int,
        offset: Float,
        tops: List<Float>,
        heights: List<Float>,
    ): Int {
        if (from !in tops.indices) return from
        val center = tops[from] + heights[from] / 2f + offset
        var to = from
        // Inclusive, so a row held against the end of the list (clamped) reaches the last slot.
        for (i in from + 1..tops.lastIndex) if (center >= tops[i] + heights[i] / 2f) to = i
        for (i in from - 1 downTo 0) if (center <= tops[i] + heights[i] / 2f) to = i
        return to
    }

    /** Keeps the moved row inside the list: from the first row's top to the last row's bottom. */
    fun clampOffset(
        from: Int,
        offset: Float,
        tops: List<Float>,
        heights: List<Float>,
    ): Float {
        if (from !in tops.indices) return 0f
        val min = tops.first() - tops[from]
        val max = tops.last() + heights.last() - (tops[from] + heights[from])
        return offset.coerceIn(min, max)
    }

    /**
     * How far row [index] steps aside while row [from] heads for [to]: by the moved row's height
     * plus the gap after it, up when the moved row goes down past it, down when it goes up past it.
     */
    fun shift(
        index: Int,
        from: Int,
        to: Int,
        tops: List<Float>,
        heights: List<Float>,
    ): Float {
        if (index == from || from !in tops.indices) return 0f
        val step = heights[from] + gap(tops, heights)
        return when {
            from < to && index in from + 1..to -> -step
            to < from && index in to until from -> step
            else -> 0f
        }
    }

    /** The offset at which the moved row sits exactly in slot [to]. */
    fun settleOffset(
        from: Int,
        to: Int,
        tops: List<Float>,
        heights: List<Float>,
    ): Float {
        if (from !in tops.indices || to !in tops.indices || to == from) return 0f
        return if (to > from) {
            tops[to] + heights[to] - heights[from] - tops[from]
        } else {
            tops[to] - tops[from]
        }
    }

    /** The space between two rows (the divider), taken from the first pair. */
    private fun gap(
        tops: List<Float>,
        heights: List<Float>,
    ): Float = if (tops.size < 2) 0f else (tops[1] - tops[0] - heights[0]).coerceAtLeast(0f)
}

/**
 * Reorder state for the saved-server list. A row is lifted by touch-and-hold, follows the finger,
 * the others step aside, and on release it settles into its slot; only then is the move reported,
 * once. Menu and accessibility moves animate the same way. While a move is reported and the new
 * order has not come back yet, [shownOrder] keeps the new order on screen so nothing flickers.
 */
@Stable
internal class ServerOrderState(
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
) {
    /** Row that follows the finger (shadow and tint). */
    var draggingId by mutableStateOf<String?>(null)
        private set

    /** Row drawn above the others while it moves (drag or menu move). */
    var raisedId by mutableStateOf<String?>(null)
        private set

    /** The new order between the move and the list coming back with it. */
    var pendingOrder by mutableStateOf<List<String>?>(null)
        private set

    private val offsets = mutableStateMapOf<String, Float>()
    private val jobs = HashMap<String, Job>()
    private val tops = HashMap<String, Float>()
    private val heights = HashMap<String, Float>()

    /** Ids as shown, refreshed on every composition. */
    var order: List<String> = emptyList()
    var onCommit: (id: String, toIndex: Int) -> Unit = { _, _ -> }

    private var from = -1
    private var target = -1
    private var busy = false

    fun offsetOf(id: String): Float = offsets[id] ?: 0f

    fun place(
        id: String,
        top: Float,
        height: Int,
    ) {
        tops[id] = top
        heights[id] = height.toFloat()
    }

    /** Shown order: the saved one, or the one just chosen while it is being saved. */
    fun <T> shownOrder(
        items: List<T>,
        id: (T) -> String,
    ): List<T> {
        val pending = pendingOrder ?: return items
        if (pending.size != items.size || pending.toSet() != items.mapTo(HashSet(), id)) return items
        val byId = items.associateBy(id)
        return pending.map(byId::getValue)
    }

    private var savedIds: Set<String>? = null

    /**
     * Called when the saved list changes. It now holds the new order (or someone else changed it),
     * so the pending order is no longer needed. A server added or removed mid-move ends the move.
     */
    fun onSavedOrder(ids: List<String>) {
        pendingOrder = null
        val set = ids.toSet()
        if (savedIds != null && set != savedIds && (draggingId != null || busy)) reset()
        savedIds = set
    }

    private fun geometry(): Pair<List<Float>, List<Float>>? {
        if (order.any { it !in tops || it !in heights }) return null
        return order.map(tops::getValue) to order.map(heights::getValue)
    }

    fun lift(id: String): Boolean {
        if (busy || draggingId != null || order.size < 2) return false
        val index = order.indexOf(id)
        if (index < 0 || geometry() == null) return false
        from = index
        target = index
        draggingId = id
        raisedId = id
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        return true
    }

    fun dragBy(dy: Float) {
        val id = draggingId ?: return
        val (t, h) = geometry() ?: return
        jobs.remove(id)?.cancel()
        val offset = ServerOrderGeometry.clampOffset(from, offsetOf(id) + dy, t, h)
        offsets[id] = offset
        val next = ServerOrderGeometry.targetIndex(from, offset, t, h)
        if (next != target) {
            target = next
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            stepAside(id, t, h)
        }
    }

    /** Finger up (or the gesture was taken away): settle where the row would land. */
    fun drop() {
        val id = draggingId ?: return
        draggingId = null
        settle(id)
    }

    /** Moves [id] one or more places without a drag (menu, accessibility action). */
    fun move(
        id: String,
        toIndex: Int,
    ) {
        if (busy || draggingId != null) return
        val index = order.indexOf(id)
        if (index < 0 || toIndex !in order.indices || toIndex == index) return
        val (t, h) = geometry() ?: run {
            // Not laid out yet: no animation, just the move.
            onCommit(id, toIndex)
            return
        }
        from = index
        target = toIndex
        raisedId = id
        stepAside(id, t, h)
        settle(id)
    }

    private fun stepAside(
        movedId: String,
        t: List<Float>,
        h: List<Float>,
    ) {
        order.forEachIndexed { index, other ->
            if (other != movedId) animateOffset(other, ServerOrderGeometry.shift(index, from, target, t, h))
        }
    }

    private fun settle(id: String) {
        val (t, h) = geometry() ?: return reset()
        busy = true
        animateOffset(id, ServerOrderGeometry.settleOffset(from, target, t, h))
        val running = jobs.values.toList()
        scope.launch {
            running.joinAll()
            commit(id)
        }
    }

    private fun commit(id: String) {
        val moved = from != target && from in order.indices && target in order.indices
        val to = target
        val pending = if (moved) order.toMutableList().apply { add(to, removeAt(from)) } else null
        // Same snapshot as the new order, so rows go from "translated" to "laid out there" in one frame.
        pendingOrder = pending
        reset()
        if (pending == null) return
        onCommit(id, to)
        // If the move was not saved (nothing comes back), fall back to the saved order.
        scope.launch {
            delay(PENDING_ORDER_TIMEOUT_MS)
            if (pendingOrder === pending) pendingOrder = null
        }
    }

    private companion object {
        const val PENDING_ORDER_TIMEOUT_MS = 1_000L
    }

    private fun reset() {
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        offsets.clear()
        draggingId = null
        raisedId = null
        busy = false
        from = -1
        target = -1
    }

    private fun animateOffset(
        id: String,
        to: Float,
    ) {
        jobs.remove(id)?.cancel()
        val start = offsetOf(id)
        if (start == to) return
        jobs[id] =
            scope.launch {
                animate(start, to, animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) { value, _ ->
                    offsets[id] = value
                }
            }
    }
}

@Composable
internal fun rememberServerOrderState(): ServerOrderState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    return remember(scope, haptics) { ServerOrderState(scope, haptics) }
}

/**
 * Whether a touch-and-hold is over before it became a hold. [change] is the held pointer in the
 * Final pass (null when it went away). Its own down event is never a reason to give up, even though
 * a clickable has consumed it by then; only what happens after the down is.
 */
internal fun holdBroken(
    change: PointerInputChange?,
    downPosition: Offset,
    touchSlop: Float,
): Boolean {
    if (change == null || !change.pressed) return true
    if (!change.previousPressed) return false
    return change.isConsumed || (change.position - downPosition).getDistance() > touchSlop
}

/**
 * Touch-and-hold, then drag, moves the row. A tap still reaches the row (it opens the server) and a
 * swipe still scrolls the page: the hold is given up as soon as the finger moves past touch slop or
 * the page starts scrolling. Once lifted, every move and the release are consumed before the row
 * sees them, so letting go never also opens the server.
 */
internal fun Modifier.reorderOnHold(
    id: String,
    state: ServerOrderState,
): Modifier =
    pointerInput(id, state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Watch in the Final pass, after the page's scroll and the row's click have had their
            // turn. Gives up on a lifted finger, a move past slop, or a move someone else took
            // (the page scrolling). The down itself doesn't count: the row's click consumes it in
            // the Main pass, so its Final pass always arrives consumed.
            var gaveUp = false
            val timedOut =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (!gaveUp) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        gaveUp = holdBroken(change, down.position, viewConfiguration.touchSlop)
                    }
                } == null
            if (!timedOut || gaveUp || !state.lift(id)) return@awaitEachGesture
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    val dy = change.positionChange().y
                    event.changes.forEach { it.consume() }
                    if (dy != 0f) state.dragBy(dy)
                }
            } finally {
                state.drop()
            }
        }
    }
