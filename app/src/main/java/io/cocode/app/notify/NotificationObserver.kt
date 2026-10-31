package io.cocode.app.notify

import io.cocode.app.runtime.LocalRuntimeController
import io.cocode.app.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Runs for the whole process: turns runtime state changes into CoCode's own error notifications
 * (see [RuntimeErrorWatch]). Nothing is polled while the server runs.
 */
class NotificationObserver(
    private val runtime: LocalRuntimeController,
    private val notifier: RuntimeAlertNotifier,
    private val watch: RuntimeErrorWatch = RuntimeErrorWatch(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        scope.launch {
            runtime.state.collectLatest { state ->
                watch.onState(state).forEach(notifier::postAlert)
                // A user Stop is never an alert; it just lets a later failure alert again.
                if (state is RuntimeState.Stopped || state is RuntimeState.NotInstalled) watch.reset()
            }
        }
    }
}
