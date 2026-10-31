package io.cocode.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import io.cocode.app.runtime.SharedStorageState
import io.cocode.app.runtime.SharedStorageStatus
import io.cocode.app.ui.theme.CoCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Turning shared storage on exposes Codex and Claude Code sign-ins and sessions to other apps, so it
 * must never happen from the switch alone: the dialog names the folders and the risk, Turn on stays
 * disabled until the user acknowledges it, and Cancel changes nothing.
 */
@RunWith(AndroidJUnit4::class)
class SharedStorageConsentComposeTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun s(id: Int) = context.getString(id)

    private val changes = mutableListOf<Boolean>()

    private fun show(
        status: SharedStorageStatus,
        needsConsent: Boolean = false,
    ) {
        compose.setContent {
            CoCodeTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SharedStorageCard(
                        state =
                            SharedStorageState(
                                status,
                                hostRoot = "/storage/emulated/0/CoCode",
                                restartRequired = false,
                                needsConsent = needsConsent,
                            ),
                        serverRunning = false,
                        onEnabledChange = { changes += it },
                        onAllowAccess = {},
                        onCheckAgain = {},
                        onRestart = {},
                    )
                }
            }
        }
    }

    private fun inDialog(text: String) = compose.onNode(hasText(text, substring = true) and hasAnyAncestor(isDialog()))

    private fun confirm() = compose.onNode(hasText(s(R.string.storage_on_dialog_confirm)) and hasClickAction() and hasAnyAncestor(isDialog()))

    @Test
    fun turningOnNeedsTheWarningAndAnAcknowledgement() {
        show(SharedStorageStatus.Off)
        // The warning is visible before anything is turned on.
        compose.onNodeWithText(s(R.string.storage_public_warning_off)).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText(s(R.string.storage_title)).performClick()
        compose.waitForIdle()

        assertEquals("the switch alone must not turn it on", emptyList<Boolean>(), changes)
        inDialog(s(R.string.storage_on_dialog_warning)).performScrollTo().assertIsDisplayed()
        inDialog("codex/  →  ~/.codex").assertExists()
        inDialog("claude/  →  ~/.claude").assertExists()
        confirm().assertIsNotEnabled()

        inDialog(s(R.string.storage_on_dialog_ack)).performScrollTo().performClick()
        confirm().assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals(listOf(true), changes)
    }

    @Test
    fun cancelLeavesItOff() {
        show(SharedStorageStatus.Off)

        compose.onNodeWithText(s(R.string.storage_title)).performClick()
        compose.waitForIdle()
        inDialog(s(R.string.storage_on_dialog_ack)).performScrollTo().performClick()
        compose.onNode(hasText(s(R.string.cancel)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()

        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(emptyList<Boolean>(), changes)
    }

    @Test
    fun activeCardKeepsTheWarningAndShowsEveryMappedFolder() {
        show(SharedStorageStatus.Active)

        compose.onNodeWithText(s(R.string.storage_public_warning)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("~/.codex").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("~/.claude").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("~/.config/opencode").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(s(R.string.storage_codex_caption)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun legacyActiveWithoutConsent_showsPartialModeAndNeverListsCodexOrClaude() {
        show(SharedStorageStatus.Active, needsConsent = true)

        compose.onNodeWithText(s(R.string.storage_on_partial)).assertIsDisplayed()
        compose.onNodeWithText(s(R.string.storage_consent_title)).performScrollTo().assertIsDisplayed()
        // Only what is mapped: projects and OpenCode.
        compose.onNodeWithText("~/projects").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("~/.config/opencode").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("~/.codex").assertDoesNotExist()
        compose.onNodeWithText("~/.claude").assertDoesNotExist()
        compose.onNodeWithText(s(R.string.storage_codex_caption)).assertDoesNotExist()
        // No claim that sign-ins are in the folder yet.
        compose.onNodeWithText(s(R.string.storage_public_warning)).assertDoesNotExist()
        compose.onNodeWithText(s(R.string.storage_kept_private_partial)).performScrollTo().assertIsDisplayed()
        assertEquals(emptyList<Boolean>(), changes)
    }

    @Test
    fun legacyUpgrade_cancelDoesNothing_acceptCallsEnableOnce() {
        show(SharedStorageStatus.Active, needsConsent = true)
        val review = compose.onNode(hasText(s(R.string.storage_consent_action)) and hasClickAction())

        review.performScrollTo().performClick()
        compose.waitForIdle()
        inDialog(s(R.string.storage_on_dialog_warning)).performScrollTo().assertIsDisplayed()
        confirm().assertIsNotEnabled()
        compose.onNode(hasText(s(R.string.cancel)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals("cancel must not migrate or enable anything", emptyList<Boolean>(), changes)

        review.performScrollTo().performClick()
        compose.waitForIdle()
        inDialog(s(R.string.storage_on_dialog_ack)).performScrollTo().performClick()
        confirm().assertIsEnabled().performClick()
        compose.waitForIdle()

        assertEquals("only an accepted dialog stores the consent", listOf(true), changes)
    }

    @Test
    fun legacyActiveWithoutConsent_canStillBeTurnedOff() {
        show(SharedStorageStatus.Active, needsConsent = true)

        compose.onNodeWithText(s(R.string.storage_title)).performClick()
        compose.waitForIdle()
        inDialog(s(R.string.storage_off_dialog_body_partial)).assertIsDisplayed()
        compose.onNode(hasText(s(R.string.turn_off)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()

        assertEquals(listOf(false), changes)
    }
}
