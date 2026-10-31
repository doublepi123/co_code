package io.cocode.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.cocode.app.R
import io.cocode.app.runtime.AgentsState
import io.cocode.app.runtime.RuntimeInfo
import io.cocode.app.runtime.RuntimeState
import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.ServerKind.OpenChamber
import io.cocode.app.runtime.ServerKind.T3
import io.cocode.app.runtime.ServersState
import io.cocode.app.runtime.manifest.AgentSpec
import io.cocode.app.ui.theme.CoCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The server part of [LocalRuntimeCard]: pick T3 Code / OpenChamber / both, never send an empty set,
 * removing an installed server asks first, switching the active server asks first and Cancel does
 * nothing, and OpenCode stays checked while OpenChamber needs it.
 */
@RunWith(AndroidJUnit4::class)
class ServerPickerComposeTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun s(id: Int) = context.getString(id)

    private val specs =
        listOf(
            AgentSpec("codex", "@openai/codex", "codex", "Codex"),
            AgentSpec("claude", "@anthropic-ai/claude-code", "claude", "Claude Code"),
            AgentSpec("opencode", "opencode-ai", "opencode", "OpenCode"),
        )

    private val sentServers = mutableListOf<Set<ServerKind>>()
    private val selected = mutableListOf<ServerKind>()
    private val sentAgents = mutableListOf<Set<String>>()

    private fun show(
        state: RuntimeState,
        servers: ServersState,
        agents: AgentsState,
        info: RuntimeInfo? = null,
    ) {
        compose.setContent {
            var currentServers by mutableStateOf(servers)
            var currentAgents by mutableStateOf(agents)
            CoCodeTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LocalRuntimeCard(
                        state = state,
                        info = info,
                        logLines = emptyList(),
                        onSetUp = {},
                        onStart = {},
                        onStop = {},
                        onOpen = {},
                        onRetry = {},
                        onRepair = {},
                        onUninstall = {},
                        agents = currentAgents,
                        onAgentsChange = {
                            sentAgents += it
                            currentAgents = currentAgents.copy(selected = it)
                        },
                        servers = currentServers,
                        onServersChange = {
                            sentServers += it
                            currentServers = currentServers.copy(selected = it)
                        },
                        onSelectServer = { selected += it },
                    )
                }
            }
        }
    }

    /** The checkbox row of [name] (the segmented selector has the same text but isn't a checkbox). */
    private fun checkRow(name: String): SemanticsNodeInteraction =
        compose.onNode(hasText(name) and isToggleable() and !hasAnyAncestor(isDialog())).performScrollTo()

    private fun click(name: String) {
        checkRow(name).performClick()
        compose.waitForIdle()
    }

    private fun dialogButton(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))

    private fun info(agents: Map<String, String>) =
        RuntimeInfo(
            abi = "x86_64",
            alpineVersion = "3.21.0",
            t3Version = "0.1.0",
            agents = agents,
            nodeVersion = "22.0.0",
            diskUsageBytes = null,
            freeSpaceBytes = null,
        )

    @Test
    fun beforeSetup_bothCanBePicked_emptyIsNeverSentAndBlocksSetUp() {
        show(RuntimeState.NotInstalled, ServersState(selected = setOf(T3)), AgentsState(specs, setOf("codex")))

        click("OpenChamber")
        assertEquals(listOf(setOf(T3, OpenChamber)), sentServers)
        // OpenChamber needs OpenCode: it shows checked and locked, without being sent as the user's pick.
        checkRow("OpenCode").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText(s(R.string.agents_required_by_openchamber)).performScrollTo().assertIsDisplayed()

        click("T3 Code")
        click("OpenChamber")
        assertEquals("an empty server set must never be sent", listOf(setOf(T3, OpenChamber), setOf(OpenChamber)), sentServers)
        compose.onNodeWithText(s(R.string.servers_none_selected)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(s(R.string.set_up)) and hasClickAction()).performScrollTo().assertIsNotEnabled()
        assertEquals(emptyList<Set<String>>(), sentAgents)
    }

    @Test
    fun afterSetup_switchingAsksFirstAndCancelDoesNothing() {
        val installed = mapOf("opencode" to "1.0.0")
        // Stopped, not Running: the running body's uptime ticker would keep the test clock busy.
        show(
            RuntimeState.Stopped,
            ServersState(setOf(T3, OpenChamber), mapOf(T3 to "0.1.0", OpenChamber to "1.4.0"), active = T3),
            AgentsState(specs, setOf("opencode"), installed),
            info(installed),
        )
        val title = context.getString(R.string.servers_switch_title, "OpenChamber")

        // The segmented selector's OpenChamber button (not the checkbox row).
        compose.onAllNodesWithText("OpenChamber").filterToOne(hasClickAction() and !isToggleable()).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.servers_switch_body, "T3 Code", "OpenChamber")) and hasAnyAncestor(isDialog()))
            .assertIsDisplayed()

        dialogButton(s(R.string.cancel)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(title).assertDoesNotExist()
        assertEquals(emptyList<ServerKind>(), selected)

        compose.onAllNodesWithText("OpenChamber").filterToOne(hasClickAction() and !isToggleable()).performClick()
        compose.waitForIdle()
        dialogButton(s(R.string.servers_switch_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(OpenChamber), selected)
    }

    @Test
    fun afterSetup_removingAnInstalledServerAsksAndAddingDoesNot() {
        val installed = mapOf("codex" to "0.50.0")
        show(
            RuntimeState.Stopped,
            ServersState(setOf(T3), mapOf(T3 to "0.1.0"), active = T3),
            AgentsState(specs, setOf("codex"), installed),
            info(installed),
        )
        val apply = compose.onNode(hasText(s(R.string.agents_apply)) and hasClickAction() and !hasAnyAncestor(isDialog()))

        // Adding OpenChamber next to T3 Code: no dialog.
        click("OpenChamber")
        apply.performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(listOf(setOf(T3, OpenChamber)), sentServers)
    }

    @Test
    fun afterSetup_uncheckingTheOnlyServerBlocksApplyAndRemovalNeedsConfirm() {
        val installed = mapOf("opencode" to "1.0.0")
        show(
            RuntimeState.Stopped,
            ServersState(setOf(T3, OpenChamber), mapOf(T3 to "0.1.0", OpenChamber to "1.4.0"), active = OpenChamber),
            AgentsState(specs, setOf("opencode"), installed, required = setOf("opencode")),
            info(installed),
        )
        val apply = compose.onNode(hasText(s(R.string.agents_apply)) and hasClickAction() and !hasAnyAncestor(isDialog()))

        click("T3 Code")
        click("OpenChamber")
        compose.onNodeWithText(s(R.string.servers_none_selected)).performScrollTo().assertIsDisplayed()
        apply.performScrollTo().assertIsNotEnabled()

        click("OpenChamber")
        apply.performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.resources.getQuantityString(R.plurals.servers_remove_title, 1, "T3 Code")).assertIsDisplayed()
        assertEquals("nothing is removed before the confirmation", emptyList<Set<ServerKind>>(), sentServers)

        dialogButton(s(R.string.cancel)).performClick()
        compose.waitForIdle()
        assertEquals(emptyList<Set<ServerKind>>(), sentServers)

        apply.performScrollTo().performClick()
        compose.waitForIdle()
        dialogButton(s(R.string.agents_remove_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(setOf(OpenChamber)), sentServers)
        // OpenCode is still required by OpenChamber and can't be unchecked.
        checkRow("OpenCode").assertIsOn().assertIsNotEnabled()
    }
}
