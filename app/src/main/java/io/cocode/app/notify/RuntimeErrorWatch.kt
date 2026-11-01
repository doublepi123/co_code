package io.cocode.app.notify

import io.cocode.app.runtime.FailurePhase
import io.cocode.app.runtime.RuntimeState

/** A CoCode-level error to show as a notification, with a stable key for dedupe. */
data class RuntimeAlert(
    /** Stable key: the same key is not shown twice until it has cleared. */
    val key: String,
    val title: String,
    val body: String,
)

/**
 * Turns controller state transitions into runtime alerts.
 *
 * - Failed(Install) → "Setup failed", Failed(Start) → "Cox didn't start",
 *   Failed(Runtime) → "Cox stopped". A user-initiated Stop is never an alert: Stop goes to
 *   Stopped/NotInstalled and never to Failed.
 */
class RuntimeErrorWatch(
    /**
     * Localised titles, given by the notifier from resources. The default keeps the English
     * technical text, for logic tests that do not need a Context.
     */
    private val titles: Titles = Titles.English,
) {
    /** The alert titles, keyed by what went wrong. */
    data class Titles(
        val setupFailed: String,
        val startFailed: String,
        val stopped: String,
    ) {
        companion object {
            val English = Titles("Setup failed", "Cox didn't start", "Cox stopped")
        }
    }

    private var shownKeys = mutableSetOf<String>()

    /** Alerts to show for a state change. Empty when the state is not an error or was already shown. */
    fun onState(state: RuntimeState): List<RuntimeAlert> {
        val alert =
            (state as? RuntimeState.Failed)?.let { failed ->
                when (failed.phase) {
                    FailurePhase.Install ->
                        RuntimeAlert("install:${failed.reason}", titles.setupFailed, failed.reason)
                    FailurePhase.Start ->
                        RuntimeAlert("start:${failed.reason}", titles.startFailed, failed.reason)
                    FailurePhase.Runtime ->
                        RuntimeAlert("runtime:${failed.reason}", titles.stopped, failed.reason)
                }
            }
        return listOfNotNull(alert).filter { shownKeys.add(it.key) }
    }

    /** Forget everything, e.g. when the user starts the runtime again. */
    fun reset() {
        shownKeys.clear()
    }
}
