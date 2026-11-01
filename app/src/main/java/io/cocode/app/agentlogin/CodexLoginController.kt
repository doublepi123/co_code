package io.cocode.app.agentlogin

import io.cocode.app.agentlogin.CodexLoginState.Cancelled
import io.cocode.app.agentlogin.CodexLoginState.Failed
import io.cocode.app.agentlogin.CodexLoginState.Idle
import io.cocode.app.agentlogin.CodexLoginState.SignedIn
import io.cocode.app.agentlogin.CodexLoginState.Starting
import io.cocode.app.agentlogin.CodexLoginState.Verifying
import io.cocode.app.agentlogin.CodexLoginState.WaitingForUser
import io.cocode.app.runtime.RuntimeInfo
import io.cocode.app.runtime.RuntimeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Device-code Codex sign-in, driven entirely from the app.
 *
 * One guest command runs at a time. Work is dispatched off the caller's thread (IO by default);
 * the published state machine is [Idle] → [Starting] → [WaitingForUser] → [Verifying] →
 * [SignedIn]/[Failed], with [Cancelled] at any point. The API-key path is a secondary flow that
 * pipes the key over stdin and never exposes it.
 *
 * @param prerequisites returns null when the runtime and Codex CLI are installed, otherwise a
 *   user-readable reason the flow cannot start. Production reads `LocalRuntimeController`.
 */
class CodexLoginController(
    private val runner: GuestCommandRunner,
    private val prerequisites: () -> String?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
    private val loginTimeoutMillis: Long = LOGIN_TIMEOUT_MILLIS,
    private val apiKeyTimeoutMillis: Long = API_KEY_TIMEOUT_MILLIS,
    private val statusTimeoutMillis: Long = STATUS_TIMEOUT_MILLIS,
) : CodexLogin {
    private val mutableState = MutableStateFlow<CodexLoginState>(Idle)
    override val state: StateFlow<CodexLoginState> = mutableState.asStateFlow()

    private val mutableSignedIn = MutableStateFlow<Boolean?>(null)
    override val signedIn: StateFlow<Boolean?> = mutableSignedIn.asStateFlow()

    private val lock = Any()
    private var busy = false

    /** Set by [cancel]; suppresses any later publish except [Cancelled]. */
    @Volatile private var cancelRequested = false
    private var active: GuestCommandHandle? = null

    /** A key submitted while the device flow ran; started once that flow has stopped. Never logged. */
    private var pendingApiKey: String? = null

    override fun startDeviceLogin() {
        if (!begin()) return
        publish(Starting)
        dispatch { runDeviceLogin() }
    }

    override fun signInWithApiKey(key: String) {
        val secret = key.trim()
        if (secret.isEmpty()) {
            // Never clobber a running login with an input validation failure.
            synchronized(lock) { if (busy) return }
            mutableState.value = Failed("The API key is empty", null)
            return
        }
        // The key form is offered while the device code is shown: the key then replaces that flow.
        var supersede = false
        var running: GuestCommandHandle? = null
        synchronized(lock) {
            when {
                !busy -> {
                    busy = true
                    cancelRequested = false
                }
                mutableState.value == Starting || mutableState.value is WaitingForUser -> {
                    pendingApiKey = secret
                    cancelRequested = true
                    mutableState.value = Starting
                    supersede = true
                    running = active
                }
                else -> return
            }
        }
        if (supersede) {
            val handle = running
            if (handle != null) scope.launch { runCatching { handle.terminate() } }
            return
        }
        publish(Starting)
        dispatch { runApiKeyLogin(secret) }
    }

    override fun cancel() {
        val handle =
            synchronized(lock) {
                if (!busy) return
                pendingApiKey = null
                cancelRequested = true
                active
            }
        publish(Cancelled)
        // Killing waits for process teardown; keep it off the caller's thread.
        if (handle != null) scope.launch { runCatching { handle.terminate() } }
    }

    override fun refreshStatus() {
        if (!begin()) return
        dispatch { runRefreshStatus() }
    }

    override fun dismiss() {
        synchronized(lock) { if (busy) return }
        mutableState.value = Idle
    }

    // --- flow steps -------------------------------------------------------------------------

    private fun runDeviceLogin() {
        if (cancelRequested) {
            publish(Cancelled)
            return
        }
        val blocked = prerequisites()
        if (blocked != null) {
            publish(Failed(blocked, null))
            return
        }
        val handle = launchCommand(CodexLoginCommand.DeviceAuth) ?: return
        if (cancelRequested) {
            handle.terminate()
            publish(Cancelled)
            return
        }
        val parser = CodexLoginParser(clock)
        val tail = ArrayDeque<String>()
        var promptShown = false
        val deadline = clock() + loginTimeoutMillis
        while (true) {
            if (cancelRequested) {
                handle.terminate()
                publish(Cancelled)
                return
            }
            val remaining = deadline - clock()
            if (remaining <= 0L) {
                handle.terminate()
                handle.waitFor(WAIT_FOR_EXIT_MILLIS)
                publish(Failed("Codex sign-in timed out. Start it again to get a fresh code.", tailText(tail)))
                return
            }
            when (val output = handle.readOutput(minOf(remaining, READ_POLL_MILLIS))) {
                is GuestOutput.Line -> {
                    appendTail(tail, output.text)
                    val prompt = parser.accept(output.text)
                    if (prompt != null && !promptShown && !cancelRequested) {
                        promptShown = true
                        publish(WaitingForUser(prompt.url, prompt.code, prompt.expiresAtMillis))
                    }
                }
                GuestOutput.EndOfOutput -> break
                GuestOutput.TimedOut -> Unit
            }
        }
        finishCommand(handle, tail)
    }

    private fun runApiKeyLogin(secret: String) {
        if (cancelRequested) {
            publish(Cancelled)
            return
        }
        val blocked = prerequisites()
        if (blocked != null) {
            publish(Failed(blocked, null))
            return
        }
        val handle = launchCommand(CodexLoginCommand.WithApiKey) ?: return
        if (cancelRequested) {
            handle.terminate()
            publish(Cancelled)
            return
        }
        handle.writeStdin("$secret\n")
        handle.closeStdin()
        val tail = ArrayDeque<String>()
        val deadline = clock() + apiKeyTimeoutMillis
        while (true) {
            if (cancelRequested) {
                handle.terminate()
                publish(Cancelled)
                return
            }
            val remaining = deadline - clock()
            if (remaining <= 0L) {
                handle.terminate()
                handle.waitFor(WAIT_FOR_EXIT_MILLIS)
                publish(Failed("Saving the API key timed out.", redact(tailText(tail), secret)))
                return
            }
            when (val output = handle.readOutput(minOf(remaining, READ_POLL_MILLIS))) {
                is GuestOutput.Line -> appendTail(tail, output.text)
                GuestOutput.EndOfOutput -> break
                GuestOutput.TimedOut -> Unit
            }
        }
        handle.waitFor(WAIT_FOR_EXIT_MILLIS)
        if (cancelRequested) {
            publish(Cancelled)
            return
        }
        val exit = handle.exitCode()
        if (exit != 0) {
            publish(Failed("Could not save the API key.", redact(tailText(tail), secret)))
            return
        }
        verify()
    }

    /** Ends the device-login command, then verifies the session when Codex exited successfully. */
    private fun finishCommand(
        handle: GuestCommandHandle,
        tail: ArrayDeque<String>,
    ) {
        handle.waitFor(WAIT_FOR_EXIT_MILLIS)
        if (cancelRequested) {
            publish(Cancelled)
            return
        }
        val exit = handle.exitCode()
        if (exit != 0) {
            publish(
                Failed(
                    message = if (exit == null) "Codex sign-in ended unexpectedly." else "Codex sign-in failed (exit $exit).",
                    detail = tailText(tail),
                ),
            )
            return
        }
        verify()
    }

    private fun verify() {
        publish(Verifying)
        val status = runStatus() ?: return
        if (cancelRequested) {
            publish(Cancelled)
            return
        }
        if (status.exitCode == 0) {
            mutableSignedIn.value = true
            publish(SignedIn(status.firstLine ?: "Signed in"))
        } else {
            mutableSignedIn.value = false
            publish(Failed("Codex did not report a signed-in session.", status.detail))
        }
    }

    private fun runRefreshStatus() {
        if (cancelRequested) {
            publish(Cancelled)
            return
        }
        if (prerequisites() != null) return
        val status = runStatus() ?: return
        if (cancelRequested) {
            publish(Cancelled)
            return
        }
        val signedIn = status.exitCode == 0
        mutableSignedIn.value = signedIn
        if (signedIn) {
            publish(SignedIn(status.firstLine ?: "Signed in"))
        } else if (mutableState.value is SignedIn) {
            publish(Idle)
        }
    }

    // --- guest commands ---------------------------------------------------------------------

    private fun launchCommand(command: CodexLoginCommand): GuestCommandHandle? =
        try {
            runner.start(command).also { handle -> synchronized(lock) { active = handle } }
        } catch (error: GuestCommandUnavailableException) {
            publish(Failed(error.message ?: "Codex sign-in is unavailable.", error.cause?.message))
            null
        } catch (error: Throwable) {
            publish(
                Failed(
                    "Codex sign-in could not start.",
                    error.message?.takeIf(String::isNotBlank),
                ),
            )
            null
        }

    private data class StatusResult(
        val exitCode: Int,
        val firstLine: String?,
        val detail: String?,
    )

    /** Runs `codex login status`; returns null when it could not be run at all. */
    private fun runStatus(): StatusResult? {
        val handle = launchCommand(CodexLoginCommand.Status) ?: return null
        val lines = mutableListOf<String>()
        val deadline = clock() + statusTimeoutMillis
        while (true) {
            if (cancelRequested) {
                handle.terminate()
                publish(Cancelled)
                return null
            }
            val remaining = deadline - clock()
            if (remaining <= 0L) {
                handle.terminate()
                break
            }
            when (val output = handle.readOutput(minOf(remaining, READ_POLL_MILLIS))) {
                is GuestOutput.Line -> lines += AnsiText.strip(output.text).trim()
                GuestOutput.EndOfOutput -> break
                GuestOutput.TimedOut -> Unit
            }
        }
        handle.waitFor(WAIT_FOR_EXIT_MILLIS)
        val exit = handle.exitCode() ?: -1
        synchronized(lock) { active = null }
        return StatusResult(
            exitCode = exit,
            firstLine = lines.firstOrNull(String::isNotBlank),
            detail = lines.filter(String::isNotBlank).takeLast(STATUS_DETAIL_LINES).joinToString("\n").takeIf(String::isNotBlank),
        )
    }

    // --- helpers (no secrets, only stripped and bounded text) -------------------------------

    private fun begin(): Boolean =
        synchronized(lock) {
            if (busy) {
                false
            } else {
                busy = true
                cancelRequested = false
                true
            }
        }

    private fun dispatch(block: () -> Unit) {
        scope.launch {
            try {
                block()
            } finally {
                val next =
                    synchronized(lock) {
                        active = null
                        cancelRequested = false
                        val key = pendingApiKey
                        pendingApiKey = null
                        if (key == null) busy = false
                        key
                    }
                // A key typed during the device flow runs once that flow has stopped.
                if (next != null) {
                    publish(Starting)
                    dispatch { runApiKeyLogin(next) }
                }
            }
        }
    }

    private fun publish(next: CodexLoginState) {
        synchronized(lock) {
            // While an API key replaces the device flow, that flow's Cancelled must not close the dialog.
            if (cancelRequested && (next != Cancelled || pendingApiKey != null)) return
            mutableState.value = next
        }
    }

    private fun appendTail(
        tail: ArrayDeque<String>,
        rawLine: String,
    ) {
        val line = AnsiText.strip(rawLine).trim().take(TAIL_LINE_CHARS)
        if (line.isEmpty()) return
        tail.addLast(line)
        while (tail.size > TAIL_LINES) tail.removeFirst()
    }

    private fun tailText(tail: ArrayDeque<String>): String? =
        tail.joinToString("\n").take(TAIL_TOTAL_CHARS).takeIf(String::isNotBlank)

    private fun redact(
        text: String?,
        secret: String,
    ): String? = text?.replace(secret, "***")

    companion object {
        /** Codex's device code is valid for 15 minutes; leave a minute for the status check. */
        const val LOGIN_TIMEOUT_MILLIS: Long = 16 * 60_000L
        const val API_KEY_TIMEOUT_MILLIS: Long = 2 * 60_000L
        const val STATUS_TIMEOUT_MILLIS: Long = 30_000L

        private const val READ_POLL_MILLIS: Long = 200L
        private const val WAIT_FOR_EXIT_MILLIS: Long = 5_000L
        private const val TAIL_LINES = 20
        private const val TAIL_LINE_CHARS = 500
        private const val TAIL_TOTAL_CHARS = 2_000
        private const val STATUS_DETAIL_LINES = 10
    }
}

/**
 * Preconditions for starting Codex sign-in. Reads only the runtime's installed info and agent map;
 * production passes `LocalRuntimeController`'s current flows.
 */
internal object CodexLoginPreconditions {
    const val CODEX_AGENT_ID = "codex"
    const val RUNTIME_NOT_INSTALLED = "The on-device runtime is not installed yet. Set it up first."
    const val RUNTIME_NOT_READY = "The on-device runtime is still starting. Try again in a moment."
    const val CODEX_NOT_INSTALLED =
        "Codex is not installed in the on-device runtime. Add Codex to the agent selection and install it first."

    fun failure(
        state: RuntimeState,
        info: RuntimeInfo?,
        installedAgents: Map<String, String>,
    ): String? {
        if (info == null) {
            return if (state is RuntimeState.NotInstalled) RUNTIME_NOT_INSTALLED else RUNTIME_NOT_READY
        }
        if (!installedAgents.containsKey(CODEX_AGENT_ID)) return CODEX_NOT_INSTALLED
        return null
    }
}
