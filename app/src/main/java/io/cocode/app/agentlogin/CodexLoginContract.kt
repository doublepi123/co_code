package io.cocode.app.agentlogin

import kotlinx.coroutines.flow.StateFlow

/**
 * What the Codex sign-in card should show. Every transition is published on the main-safe
 * [CodexLogin.state] flow; no blocking work happens on the caller's thread.
 */
sealed interface CodexLoginState {
    data object Idle : CodexLoginState

    /** A guest command has been launched (or a previous terminal state is being replaced). */
    data object Starting : CodexLoginState

    /**
     * Codex printed its device-code instructions. [url] is opened in the phone browser and [code]
     * is entered there. [expiresAtMillis] is epoch milliseconds (Codex's default 15 minutes).
     */
    data class WaitingForUser(
        val url: String,
        val code: String,
        val expiresAtMillis: Long,
    ) : CodexLoginState

    /** Codex finished and `codex login status` is being checked. */
    data object Verifying : CodexLoginState

    /** [detail] is the first line of `codex login status`, e.g. "Logged in using ChatGPT". */
    data class SignedIn(val detail: String) : CodexLoginState

    /**
     * [message] is a short, user-readable reason. [detail] is technical output (ANSI-stripped,
     * bounded, secrets redacted) for a "show details" affordance; it is never translated.
     */
    data class Failed(
        val message: String,
        val detail: String?,
    ) : CodexLoginState

    data object Cancelled : CodexLoginState
}

/**
 * One-tap Codex sign-in from inside the app. The app runs `codex login --device-auth` inside the
 * same PRoot guest as the server, so the credentials land in the very `$CODEX_HOME/auth.json`
 * (`/root/.codex/auth.json`) the server and the Codex CLI use.
 */
interface CodexLogin {
    val state: StateFlow<CodexLoginState>

    /** null = unknown / not checked yet; refreshed by [refreshStatus]. */
    val signedIn: StateFlow<Boolean?>

    /** Starts the device-code flow. No-op while another login or status check is running. */
    fun startDeviceLogin()

    /**
     * Secondary path: pipes [key] to `codex login --with-api-key` over stdin. The key is never
     * logged, stored or copied into any state; only the outcome is published.
     */
    fun signInWithApiKey(key: String)

    /** Stops the running guest command (whole PRoot tree) and moves to [CodexLoginState.Cancelled]. */
    fun cancel()

    /** Runs `codex login status` and refreshes [signedIn]. No-op while a login is running. */
    fun refreshStatus()

    /** Back to [CodexLoginState.Idle] from a terminal state; ignored while work is running. */
    fun dismiss()
}
