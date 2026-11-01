package io.cocode.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.cocode.app.R
import io.cocode.app.agentlogin.CodexLoginState
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
 * Codex sign-in on the Home card with a fake login: the row offers Sign in, the device-code dialog
 * opens the page through the injected opener, copies the code, sends a typed API key, and Cancel
 * cancels. No guest command runs.
 */
@RunWith(AndroidJUnit4::class)
class CodexSignInComposeTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun s(id: Int) = context.getString(id)

    private val opened = mutableListOf<String>()
    private val apiKeys = mutableListOf<String>()
    private var starts = 0
    private var cancels = 0
    private var dismisses = 0
    private var refreshes = 0
    private var clipboardText: String? = null

    private val clipboard =
        object : ClipboardManager {
            override fun getText(): AnnotatedString? = clipboardText?.let(::AnnotatedString)

            override fun setText(annotatedString: AnnotatedString) {
                clipboardText = annotatedString.text
            }
        }

    private val waiting =
        CodexLoginState.WaitingForUser(
            url = "https://auth.openai.com/codex/device",
            code = "ABCD-EFGH",
            expiresAtMillis = System.currentTimeMillis() + 15 * 60_000L,
        )

    private var loginState by mutableStateOf<CodexLoginState>(CodexLoginState.Idle)
    private var signedIn by mutableStateOf<Boolean?>(false)

    private fun show() {
        val actions =
            CodexSignInActions(
                start = {
                    starts++
                    loginState = waiting
                },
                signInWithApiKey = {
                    apiKeys += it
                    loginState = CodexLoginState.Verifying
                },
                cancel = {
                    cancels++
                    loginState = CodexLoginState.Cancelled
                },
                dismiss = {
                    dismisses++
                    loginState = CodexLoginState.Idle
                },
                openUrl = { url ->
                    opened += url
                    true
                },
                // The dialog is its own window with its own clipboard local, so copy is injected.
                copyCode = { clipboardText = it },
            )
        val installed = mapOf("codex" to "0.166.0")
        compose.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                CoCodeTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        LocalRuntimeCard(
                            state = RuntimeState.Stopped,
                            info =
                                RuntimeInfo(
                                    abi = "x86_64",
                                    alpineVersion = "3.21.0",
                                    t3Version = "0.1.0",
                                    agents = installed,
                                    nodeVersion = "22.0.0",
                                    diskUsageBytes = null,
                                    freeSpaceBytes = null,
                                ),
                            logLines = emptyList(),
                            onSetUp = {},
                            onStart = {},
                            onStop = {},
                            onOpen = {},
                            onRetry = {},
                            onRepair = {},
                            onUninstall = {},
                            agents =
                                AgentsState(
                                    listOf(AgentSpec("codex", "@openai/codex", "codex", "Codex")),
                                    selected = setOf("codex"),
                                    installed = installed,
                                ),
                            codexLogin =
                                CodexSignInUi(
                                    state = loginState,
                                    signedIn = signedIn,
                                    actions = actions,
                                    refreshStatus = { refreshes++ },
                                ),
                        )
                    }
                }
            }
        }
    }

    private fun inDialog(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))

    private fun signInFromRow() {
        compose.onNode(hasText(s(R.string.codex_sign_in)) and hasClickAction()).performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    fun waitingForUser_opensThePageAndCopiesTheCode() {
        show()
        signInFromRow()
        assertEquals(1, starts)

        inDialog(s(R.string.codex_step_open)).assertIsDisplayed()
        inDialog("ABCD-EFGH").assertIsDisplayed()
        // The countdown line, whatever second it is on: the fixed words of "Expires in %1$s".
        val fixedWords = context.getString(R.string.codex_expires_in, "\u0000").split("\u0000").map { it.trim() }.first { it.isNotEmpty() }
        compose.onNode(hasText(fixedWords, substring = true) and hasAnyAncestor(isDialog())).assertExists()

        inDialog(s(R.string.codex_open_page)).performClick()
        compose.onNodeWithContentDescription(s(R.string.codex_copy_code)).performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertEquals(listOf("https://auth.openai.com/codex/device"), opened)
        assertEquals("ABCD-EFGH", clipboardText)
    }

    @Test
    fun apiKeyPath_sendsTheTypedKey() {
        show()
        signInFromRow()

        inDialog(s(R.string.codex_use_api_key)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performScrollTo().performTextInput("sk-test-123")
        inDialog(s(R.string.codex_api_key_submit)).performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals(listOf("sk-test-123"), apiKeys)
        inDialog(s(R.string.codex_verifying)).assertIsDisplayed()
    }

    @Test
    fun cancel_callsCancelAndClosesTheDialog() {
        show()
        signInFromRow()

        inDialog(s(R.string.cancel)).performClick()
        compose.waitForIdle()

        assertEquals(1, cancels)
        // Cancelled is dismissed by the dialog itself.
        assertEquals(1, dismisses)
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun hidingAWaitingFlow_leavesContinueOnTheRow() {
        show()
        signInFromRow()

        inDialog(s(R.string.codex_hide)).performClick()
        compose.waitForIdle()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertEquals(0, cancels)

        compose.onNodeWithText(s(R.string.codex_continue_sign_in)).performScrollTo().performClick()
        compose.waitForIdle()
        inDialog("ABCD-EFGH").assertIsDisplayed()
    }

    @Test
    fun signedIn_showsStatusAndNoDialogFromABackgroundCheck() {
        signedIn = true
        loginState = CodexLoginState.SignedIn("Logged in using ChatGPT")
        show()

        compose.onNodeWithText(s(R.string.codex_signed_in)).performScrollTo().assertIsDisplayed()
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    @Test
    fun unknownStatus_isCheckedOnceWhenTheCardShows() {
        signedIn = null
        show()
        compose.waitForIdle()

        assertEquals(1, refreshes)
    }
}
