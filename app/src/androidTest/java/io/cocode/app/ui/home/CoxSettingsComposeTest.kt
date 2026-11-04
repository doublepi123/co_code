package io.cocode.app.ui.home

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.cocode.app.R
import io.cocode.app.ui.theme.CoCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoxSettingsComposeTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun settingsButtonsOpenTheirOwnCoxPages() {
        val opened = mutableListOf<CoxSettingsPage>()
        compose.setContent { CoCodeTheme { CoxSettingsSection(enabled = true, onOpen = opened::add) } }
        compose.onNodeWithText(context.getString(R.string.cox_model_center)).performClick()
        compose.onNodeWithText(context.getString(R.string.cox_agent_settings)).performClick()
        compose.runOnIdle {
            assertEquals(listOf("/settings/model-services", "/settings/providers"), opened.map { it.path })
        }
    }

    @Test
    fun unavailableRuntimeCannotOpenSettings() {
        val opened = mutableListOf<CoxSettingsPage>()
        compose.setContent { CoCodeTheme { CoxSettingsSection(enabled = false, onOpen = opened::add) } }
        compose.onNodeWithText(context.getString(R.string.cox_model_center)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.cox_agent_settings)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(emptyList<CoxSettingsPage>(), opened) }
    }

    @Test
    fun oldRuntimeKeepsAgentSettingsAvailableWhileModelCenterNeedsAnUpdate() {
        compose.setContent { CoCodeTheme { CoxSettingsSection(enabled = true, modelCenterAvailable = false, onOpen = {}) } }
        compose.onNodeWithText(context.getString(R.string.cox_model_center)).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.cox_agent_settings)).assertIsEnabled()
    }

}
