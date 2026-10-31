package io.cocode.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.cocode.app.runtime.LocalRuntimeController
import io.cocode.app.runtime.RemoteServerStore
import io.cocode.app.runtime.SharedStorageController
import io.cocode.app.runtime.WebSessionAuthenticator
import io.cocode.app.ui.CoCodeApp
import io.cocode.app.ui.ScreenAwakeController
import io.cocode.app.ui.keepScreenOnWhileLocalRuns
import org.koin.android.ext.android.get

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val runtime = get<LocalRuntimeController>()
        val screenAwake = get<ScreenAwakeController>()
        // Opt-in: holds this window's screen on only while the local server runs and the app is in
        // front. Released on stop, failure, background, a remote page, and when the activity goes.
        keepScreenOnWhileLocalRuns(
            window = window,
            enabled = screenAwake.enabled,
            runtime = runtime.state,
            viewedTargetId = screenAwake.viewedTargetId,
        )
        setContent {
            CoCodeApp(
                runtime = runtime,
                remotes = get<RemoteServerStore>(),
                auth = get<WebSessionAuthenticator>(),
                storage = get<SharedStorageController>(),
            )
        }
    }
}
