package io.cocode.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.cocode.app.R
import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.ServerTarget
import io.cocode.app.runtime.withServerMoved
import io.cocode.app.ui.theme.CoCodeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reordering the saved servers: Move up / Move down in a row's menu and as accessibility actions
 * report one move and the rows are drawn in the new order; the first row has no Move up, the last
 * no Move down, a single server has neither; a plain tap still opens the server; holding a row and
 * dragging it reports one move on release and doesn't open it.
 */
@RunWith(AndroidJUnit4::class)
class RemoteServerOrderComposeTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun s(id: Int) = context.getString(id)

    private fun server(name: String) =
        ServerTarget.Remote(name.lowercase(), name, "https://${name.lowercase()}.example.com", null, kind = ServerKind.T3)

    private val moves = mutableListOf<Pair<String, Int>>()
    private val opened = mutableListOf<String>()
    private lateinit var saved: MutableStateFlow<List<ServerTarget.Remote>>

    private val names = listOf("Alpha", "Bravo", "Charlie")

    /** The long-press timeout the rows see, read from the composition. */
    private var longPressTimeoutMs = 0L

    private companion object {
        const val HOLD_MARGIN_MS = 300L
    }

    /** Shows the section over a fake store: [onMove] applies the move as the real store does. */
    private fun show(vararg shown: String) {
        saved = MutableStateFlow(shown.map(::server))
        compose.setContent {
            val servers by saved.collectAsState()
            longPressTimeoutMs = LocalViewConfiguration.current.longPressTimeoutMillis
            CoCodeTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    RemoteServersSection(
                        servers = servers,
                        onOpen = { opened += it.id },
                        onAdd = {},
                        onEdit = {},
                        onRemove = {},
                        onMove = { server, to ->
                            moves += server.id to to
                            saved.value = saved.value.withServerMoved(server.id, to)
                        },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun rowNode(name: String) = compose.onNode(hasClickAction() and hasText(name))

    /** Names in the order the rows are drawn, top to bottom. */
    private fun drawnOrder(): List<String> =
        names.filter { name -> saved.value.any { it.displayName == name } }
            .sortedBy { rowNode(it).fetchSemanticsNode().boundsInRoot.top }

    private fun openMenu(index: Int) {
        compose.onAllNodesWithContentDescription(s(R.string.more_options))[index].performClick()
        compose.waitForIdle()
    }

    private fun closeMenu() {
        compose.onNodeWithText(s(R.string.edit)).performClick()
        compose.waitForIdle()
    }

    private fun count(label: Int) = compose.onAllNodesWithText(s(label)).fetchSemanticsNodes().size

    private fun rowActions(name: String): List<String> =
        rowNode(name).fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    @Test
    fun moveDownFromTheMenuReordersTheRows() {
        show("Alpha", "Bravo", "Charlie")
        openMenu(0)
        compose.onNodeWithText(s(R.string.move_down)).performClick()
        compose.waitForIdle()

        assertEquals(listOf("alpha" to 1), moves)
        assertEquals(listOf("Bravo", "Alpha", "Charlie"), drawnOrder())
        assertEquals(listOf("bravo", "alpha", "charlie"), saved.value.map { it.id })
    }

    @Test
    fun moveUpFromTheMenuReordersTheRows() {
        show("Alpha", "Bravo", "Charlie")
        openMenu(2)
        compose.onNodeWithText(s(R.string.move_up)).performClick()
        compose.waitForIdle()

        assertEquals(listOf("charlie" to 1), moves)
        assertEquals(listOf("Alpha", "Charlie", "Bravo"), drawnOrder())
    }

    @Test
    fun twoMovesInARowEachLandOnce() {
        show("Alpha", "Bravo", "Charlie")
        openMenu(0)
        compose.onNodeWithText(s(R.string.move_down)).performClick()
        compose.waitForIdle()
        // Alpha is now the middle row.
        openMenu(1)
        compose.onNodeWithText(s(R.string.move_down)).performClick()
        compose.waitForIdle()

        assertEquals(listOf("alpha" to 1, "alpha" to 2), moves)
        assertEquals(listOf("Bravo", "Charlie", "Alpha"), drawnOrder())
    }

    @Test
    fun theEndsOfferNoMoveOffTheList() {
        show("Alpha", "Bravo", "Charlie")
        openMenu(0)
        assertEquals(0, count(R.string.move_up))
        assertEquals(1, count(R.string.move_down))
        closeMenu()

        openMenu(2)
        assertEquals(1, count(R.string.move_up))
        assertEquals(0, count(R.string.move_down))
        closeMenu()

        openMenu(1)
        assertEquals(1, count(R.string.move_up))
        assertEquals(1, count(R.string.move_down))
        assertTrue(moves.isEmpty())
    }

    @Test
    fun aSingleServerCannotBeMoved() {
        show("Alpha")
        assertTrue(rowActions("Alpha").isEmpty())
        openMenu(0)
        assertEquals(0, count(R.string.move_up))
        assertEquals(0, count(R.string.move_down))
    }

    @Test
    fun accessibilityActionsMoveTheRow() {
        show("Alpha", "Bravo", "Charlie")
        assertEquals(listOf(s(R.string.move_down)), rowActions("Alpha"))
        assertEquals(listOf(s(R.string.move_up), s(R.string.move_down)), rowActions("Bravo"))
        assertEquals(listOf(s(R.string.move_up)), rowActions("Charlie"))

        val down =
            rowNode("Bravo").fetchSemanticsNode().config[SemanticsActions.CustomActions]
                .first { it.label == s(R.string.move_down) }
        compose.runOnUiThread { down.action() }
        compose.waitForIdle()

        assertEquals(listOf("bravo" to 2), moves)
        assertEquals(listOf("Alpha", "Charlie", "Bravo"), drawnOrder())
    }

    @Test
    fun aTapOpensTheServerAndMovesNothing() {
        show("Alpha", "Bravo", "Charlie")
        rowNode("Bravo").performClick()
        compose.waitForIdle()
        assertEquals(listOf("bravo"), opened)
        assertTrue(moves.isEmpty())
    }

    /**
     * Puts a finger on [name]'s row and keeps it still past the real long-press timeout (the one
     * the row reads, from [LocalViewConfiguration]) plus a margin. Each step is its own injection
     * with the UI settled in between, so a slow device can't merge the hold into the moves.
     */
    private fun holdRow(name: String) {
        rowNode(name).performTouchInput { down(center) }
        compose.waitForIdle()
        val hold = longPressTimeoutMs + HOLD_MARGIN_MS
        // The hold's timeout runs on the test clock: move it on explicitly (an advance with no
        // event would only take effect at the next event), and stamp the next event to match.
        compose.mainClock.advanceTimeBy(hold)
        rowNode(name).performTouchInput { advanceEventTime(hold) }
        compose.waitForIdle()
    }

    /** Moves the held finger by [dy] in [steps] small moves, as a finger would. */
    private fun dragHeldBy(
        name: String,
        dy: Float,
        steps: Int = 12,
    ) {
        repeat(steps) {
            rowNode(name).performTouchInput { moveBy(Offset(0f, dy / steps)) }
            compose.waitForIdle()
        }
    }

    private fun release(name: String) {
        rowNode(name).performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun holdAndDragMovesTheRowOnceOnRelease() {
        show("Alpha", "Bravo", "Charlie")
        val alpha = rowNode("Alpha").fetchSemanticsNode().boundsInRoot
        val charlie = rowNode("Charlie").fetchSemanticsNode().boundsInRoot
        val distance = charlie.center.y - alpha.center.y + 8f

        holdRow("Alpha")
        dragHeldBy("Alpha", distance)
        assertTrue("nothing is saved while the finger is down", moves.isEmpty())
        release("Alpha")

        assertEquals(listOf("alpha" to 2), moves)
        assertTrue("letting go must not also open the server", opened.isEmpty())
        assertEquals(listOf("Bravo", "Charlie", "Alpha"), drawnOrder())
    }

    @Test
    fun holdingStillThenLettingGoDoesNothing() {
        show("Alpha", "Bravo", "Charlie")
        holdRow("Bravo")
        release("Bravo")

        assertTrue(moves.isEmpty())
        assertTrue("a hold is not a tap", opened.isEmpty())
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), drawnOrder())
    }

    @Test
    fun aQuickSwipeIsNotADrag() {
        show("Alpha", "Bravo", "Charlie")
        val alpha = rowNode("Alpha").fetchSemanticsNode().boundsInRoot
        val charlie = rowNode("Charlie").fetchSemanticsNode().boundsInRoot
        rowNode("Alpha").performTouchInput {
            down(center)
            // Moves past touch slop right away, before the hold time: a scroll, not a move.
            repeat(10) { moveBy(Offset(0f, (charlie.center.y - alpha.center.y) / 10f)) }
            up()
        }
        compose.waitForIdle()

        assertTrue(moves.isEmpty())
        assertTrue(opened.isEmpty())
        assertEquals(listOf("Alpha", "Bravo", "Charlie"), drawnOrder())
    }
}
