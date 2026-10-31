package io.cocode.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.cocode.app.R
import io.cocode.app.ui.theme.CoCodeTheme
import io.cocode.app.update.AppReleaseUpdate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The CoCode update dialog shows the versions and notes, and each button reports its own action. */
@RunWith(AndroidJUnit4::class)
class AppUpdateDialogComposeTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val update =
        AppReleaseUpdate(
            version = "0.2.0",
            pageUrl = "https://github.com/example/co_code/releases/tag/v0.2.0",
            notes = "## Changes\n- **Faster** setup",
        )

    private var later = 0
    private var view = 0

    private fun show() {
        compose.setContent {
            CoCodeTheme {
                AppUpdateDialog(
                    update = update,
                    currentVersion = "0.1.0",
                    onLater = { later++ },
                    onViewRelease = { view++ },
                )
            }
        }
    }

    @Test
    fun showsVersionAndNotes() {
        show()

        compose.onNodeWithText(context.getString(R.string.app_update_title, "0.2.0")).assertIsDisplayed()
        compose.onNodeWithText("0.1.0  →  0.2.0").assertIsDisplayed()
        compose.onNodeWithText("Changes\n• Faster setup").assertIsDisplayed()
    }

    @Test
    fun laterAndViewReleaseReportSeparately() {
        show()

        compose.onNodeWithText(context.getString(R.string.app_update_later)).performClick()
        compose.onNodeWithText(context.getString(R.string.app_update_view)).performClick()
        compose.waitForIdle()

        assertEquals(1, later)
        assertEquals(1, view)
    }
}
