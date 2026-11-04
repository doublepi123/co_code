package io.cocode.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.cocode.app.R
import io.cocode.app.runtime.InstallStep
import io.cocode.app.runtime.MessageKey
import io.cocode.app.runtime.RuntimeInfo
import io.cocode.app.runtime.RuntimeState
import io.cocode.app.runtime.RuntimeUpdate
import io.cocode.app.runtime.RuntimeUpdateState
import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.ServersState
import io.cocode.app.runtime.VersionChange
import io.cocode.app.ui.theme.CoCodeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Adding agents while a T3 Code update is on offer: the progress is generic maintenance. It must
 * not say T3 Code is being updated or list the offer's versions (ARM64 E2E regression).
 */
@RunWith(AndroidJUnit4::class)
class MaintenanceProgressComposeTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun addingAgentsNeverClaimsACoxUpdate() {
        val offer =
            RuntimeUpdate(
                t3 = VersionChange(current = "0.1.6-pw.5", target = "0.1.6-pw.6"),
                agents = emptyList(),
            )
        compose.setContent {
            CoCodeTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    LocalRuntimeCard(
                        state =
                            RuntimeState.Installing(
                                step = InstallStep.InstallServer,
                                progress = null,
                                detail = null,
                                messageKey = MessageKey.ProgressInstallingServer,
                                updating = true,
                            ),
                        info =
                            RuntimeInfo(
                                abi = "arm64-v8a",
                                alpineVersion = "3.21.0",
                                t3Version = "0.1.6-pw.5",
                                agents = mapOf("opencode" to "1.0.0"),
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
                        updates = RuntimeUpdateState(available = offer),
                        servers =
                            ServersState(
                                selected = setOf(ServerKind.T3),
                                installed = mapOf(ServerKind.T3 to "0.1.6-pw.5"),
                                active = ServerKind.T3,
                            ),
                    )
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.updating_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.step_install_server)).performScrollTo().assertIsDisplayed()
        // The unrelated T3 Code offer is not shown as this operation's target.
        compose.onNodeWithText("0.1.6-pw.6", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Updating T3 Code", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Install T3 Code", substring = true).assertDoesNotExist()
    }
}
