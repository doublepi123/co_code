package io.cocode.app.agentlogin

/** The three guest commands the Codex sign-in flow runs. */
enum class CodexLoginCommand(
    /** Arguments after the `codex` binary. */
    val arguments: List<String>,
) {
    DeviceAuth(listOf("login", "--device-auth")),
    WithApiKey(listOf("login", "--with-api-key")),
    Status(listOf("login", "status")),
}

/** One item of merged stdout/stderr from a guest command. */
sealed interface GuestOutput {
    data class Line(val text: String) : GuestOutput

    /** The child closed its output (it has exited or is exiting). */
    data object EndOfOutput : GuestOutput

    /** No line arrived within the poll timeout; nothing was lost. */
    data object TimedOut : GuestOutput
}

/**
 * A running guest command. All calls are safe from any thread; [readOutput] blocks only for its
 * timeout, so a caller can enforce overall deadlines and cancellation itself.
 */
interface GuestCommandHandle {
    fun readOutput(timeoutMillis: Long): GuestOutput

    /** Writes [text] as-is to the child's stdin (UTF-8) and flushes. */
    fun writeStdin(text: String)

    fun closeStdin()

    /** Waits up to [timeoutMillis]; true when the child has exited. */
    fun waitFor(timeoutMillis: Long): Boolean

    /** Exit status once the child has exited, null while it still runs. */
    fun exitCode(): Int?

    /** Stops the command: the direct child plus PRoot's kill-on-exit teardown of its tracees. */
    fun terminate()

    fun isAlive(): Boolean
}

/** Launches one guest command inside the app's PRoot environment. */
interface GuestCommandRunner {
    /** @throws GuestCommandUnavailableException when PRoot or the guest binary cannot be started. */
    fun start(command: CodexLoginCommand): GuestCommandHandle
}

/** The runtime pieces needed to launch a guest command are missing; the message is user-readable. */
class GuestCommandUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
