package io.cocode.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.cocode.app.R
import io.cocode.app.runtime.AgentsState
import io.cocode.app.runtime.RuntimeInfo
import io.cocode.app.runtime.RuntimeState
import io.cocode.app.runtime.manifest.AgentSpec
import io.cocode.app.ui.theme.CoCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The agent picker on the real [LocalRuntimeCard]: an empty draft is allowed on screen but never sent,
 * Set up / Apply are disabled meanwhile, adding keeps installed agents, and removing asks first.
 */
@RunWith(AndroidJUnit4::class)
class AgentPickerComposeTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun s(id: Int) = context.getString(id)

    private val specs =
        listOf(
            AgentSpec("codex", "@openai/codex", "codex", "Codex"),
            AgentSpec("claude", "@anthropic-ai/claude-code", "claude", "Claude Code"),
            AgentSpec("opencode", "opencode-ai", "opencode", "OpenCode"),
        )

    /** Every selection the card sent to the controller, in order. */
    private val sent = mutableListOf<Set<String>>()

    private fun show(
        state: RuntimeState,
        agents: AgentsState,
        info: RuntimeInfo? = null,
    ) {
        compose.setContent {
            var current by mutableStateOf(agents)
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
                        agents = current,
                        onAgentsChange = {
                            sent += it
                            // Like the controller: the saved selection follows; installed does not.
                            current = current.copy(selected = it)
                        },
                    )
                }
            }
        }
    }

    private fun row(name: String) = compose.onNodeWithText(name).performScrollTo()

    private fun click(name: String) {
        row(name).performClick()
        compose.waitForIdle()
    }

    private fun dialogText(text: String) = compose.onNode(hasText(text, substring = true) and hasAnyAncestor(isDialog()))

    private fun confirmButton() =
        compose.onNode(hasText(s(R.string.agents_remove_confirm)) and hasClickAction() and hasAnyAncestor(isDialog()))

    private fun cancelButton() = compose.onNode(hasText(s(R.string.cancel)) and hasClickAction() and hasAnyAncestor(isDialog()))

    private fun applyButton() = compose.onNode(hasText(s(R.string.agents_apply)) and hasClickAction() and !hasAnyAncestor(isDialog()))

    @Test
    fun beforeSetup_uncheckingOpenCodeLeavesAnEmptyDraftAndBlocksSetUp() {
        show(RuntimeState.NotInstalled, AgentsState(specs, selected = setOf("opencode")))

        click("OpenCode")

        assertEquals("an empty selection must not reach the controller", emptyList<Set<String>>(), sent)
        compose.onNodeWithText(s(R.string.agents_none_selected)).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(s(R.string.set_up)) and hasClickAction()).performScrollTo().assertIsNotEnabled()

        click("Codex")

        assertEquals(listOf(setOf("codex")), sent)
        compose.onNode(hasText(s(R.string.set_up)) and hasClickAction()).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(s(R.string.agents_none_selected)).assertDoesNotExist()
    }

    @Test
    fun afterSetup_addingAnAgentKeepsTheInstalledOne() {
        val installed = mapOf("codex" to "0.50.0")
        show(
            RuntimeState.Stopped,
            AgentsState(specs, selected = setOf("codex"), installed = installed),
            info = info(installed),
        )
        row("Codex").assertIsOn()

        click("OpenCode")
        applyButton().performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(listOf(setOf("codex", "opencode")), sent)
    }

    @Test
    fun afterSetup_emptyDraftDisablesApplyAndRemovalAsksFirst() {
        val installed = mapOf("opencode" to "1.0.0")
        show(
            RuntimeState.Stopped,
            AgentsState(specs, selected = setOf("opencode"), installed = installed),
            info = info(installed),
        )

        click("OpenCode")
        row("OpenCode").assertIsOff()
        compose.onNodeWithText(s(R.string.agents_none_selected)).performScrollTo().assertIsDisplayed()
        applyButton().performScrollTo().assertIsNotEnabled()
        assertEquals(emptyList<Set<String>>(), sent)

        click("Codex")
        applyButton().performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        // Unchecking an installed agent is a removal: nothing is sent until the dialog is confirmed.
        assertEquals(emptyList<Set<String>>(), sent)
        confirmButton().performClick()
        compose.waitForIdle()

        assertEquals(listOf(setOf("codex")), sent)
    }

    @Test
    fun afterSetup_codexInstalled_addingOpenCodeAppliesWithoutAnyDialog() {
        val installed = mapOf("codex" to "0.50.0")
        // Stopped, not Running: the running body's uptime ticker would keep the test clock busy.
        show(RuntimeState.Stopped, AgentsState(specs, setOf("codex"), installed), info(installed))

        click("OpenCode")
        compose.onNodeWithText(s(R.string.agents_will_install)).performScrollTo().assertIsDisplayed()
        applyButton().performScrollTo().performClick()
        compose.waitForIdle()

        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals("Codex must stay in the applied set", listOf(setOf("codex", "opencode")), sent)
    }

    @Test
    fun afterSetup_replacingCodexListsTheRemovalAndCancelKeepsEverything() {
        val installed = mapOf("codex" to "0.50.0", "claude" to "2.0.0")
        show(RuntimeState.Stopped, AgentsState(specs, setOf("codex", "claude"), installed), info(installed))

        click("Codex")
        click("OpenCode")
        compose.onNodeWithText(s(R.string.agents_will_remove)).performScrollTo().assertIsDisplayed()
        applyButton().performScrollTo().performClick()
        compose.waitForIdle()

        dialogText(context.getString(R.string.agents_remove_list, "Codex")).assertIsDisplayed()
        dialogText(context.getString(R.string.agents_add_list, "OpenCode")).assertIsDisplayed()
        dialogText(context.getString(R.string.agents_keep_list, "Claude Code")).assertIsDisplayed()

        cancelButton().performClick()
        compose.waitForIdle()

        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals("Cancel must not touch the installation", emptyList<Set<String>>(), sent)
        // The staged change is still there, so the user can review it or undo it.
        row("Codex").assertIsOff()
        applyButton().performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        confirmButton().performClick()
        compose.waitForIdle()

        assertEquals(listOf(setOf("claude", "opencode")), sent)
    }

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
}
