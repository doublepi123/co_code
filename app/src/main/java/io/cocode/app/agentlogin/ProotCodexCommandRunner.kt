package io.cocode.app.agentlogin

import io.cocode.app.runtime.RuntimePaths
import io.cocode.app.runtime.process.EmbeddedCommandSuite
import io.cocode.app.runtime.process.OwnedProcess
import io.cocode.app.runtime.process.ProcessScanner
import io.cocode.app.runtime.process.terminateProcessTree
import io.cocode.app.runtime.storage.GuestBinds
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs one Codex command inside PRoot, in the same environment as the server.
 *
 * Output is read on a daemon thread into a bounded-by-nature blocking queue, so the controller can
 * poll with its own deadlines and still react to cancel. Termination only ever touches this
 * process handle; PRoot's `--kill-on-exit` tears down the guest tree, so a running server is never
 * signalled (its PRoot tree has the same launcher path and would otherwise look "owned").
 */
class ProotCodexCommandRunner(
    private val paths: RuntimePaths,
    /** Resolves the packaged PRoot launcher; throws when it is missing from the APK. */
    private val suite: () -> EmbeddedCommandSuite.Paths,
    /** The bind set of the server's current start decision (shared storage aware). */
    private val binds: () -> GuestBinds,
    private val scanner: ProcessScanner,
    private val signal: (Long) -> Unit,
    private val spawn: (List<String>, Map<String, String>, File) -> Process = ::spawnProcess,
) : GuestCommandRunner {
    override fun start(command: CodexLoginCommand): GuestCommandHandle {
        val resolved =
            try {
                suite()
            } catch (error: Throwable) {
                throw GuestCommandUnavailableException(PROOT_MISSING_MESSAGE, error)
            }
        paths.root.mkdirs()
        paths.prootTmp.mkdirs()
        val argv = CodexLoginCommands.argv(resolved.proot, paths.rootfs, binds(), command)
        val environment = CodexLoginCommands.environment(resolved, paths.prootTmp)
        val process =
            try {
                spawn(argv, environment, paths.root)
            } catch (error: Throwable) {
                throw GuestCommandUnavailableException("Could not start Codex in the on-device runtime", error)
            }
        // Only a direct child with exactly this command line is matched, never the server's tree.
        val findRoots = { scanner.ownedChildrenWithArgv(argv) }
        return ProcessGuestCommandHandle(process, scanner, signal, findRoots)
    }

    companion object {
        const val PROOT_MISSING_MESSAGE = "The embedded Linux runtime (PRoot) is unavailable"

        fun spawnProcess(
            argv: List<String>,
            environment: Map<String, String>,
            directory: File,
        ): Process =
            ProcessBuilder(argv)
                .directory(directory)
                .redirectErrorStream(true)
                .apply {
                    this.environment().clear()
                    this.environment().putAll(environment)
                }
                .start()
    }
}

private class ProcessGuestCommandHandle(
    private val process: Process,
    private val scanner: ProcessScanner,
    private val signal: (Long) -> Unit,
    /** This command's PRoot launcher(s), recorded with start times so a recycled pid is never hit. */
    private val findRoots: () -> List<OwnedProcess>,
) : GuestCommandHandle {
    private val queue = LinkedBlockingQueue<GuestOutput>()
    private val stdin: BufferedWriter = process.outputStream.bufferedWriter(Charsets.UTF_8)

    @Suppress("unused")
    private val reader =
        Thread(
            {
                try {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { stream ->
                        while (true) {
                            val line = stream.readLine() ?: break
                            queue.put(GuestOutput.Line(line))
                        }
                    }
                } catch (_: Throwable) {
                    // A killed child closes its pipe; the queue still reports EndOfOutput below.
                } finally {
                    queue.offer(GuestOutput.EndOfOutput)
                }
            },
            "cocode-codex-login-reader",
        ).apply {
            isDaemon = true
            start()
        }

    override fun readOutput(timeoutMillis: Long): GuestOutput =
        queue.poll(timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS) ?: GuestOutput.TimedOut

    override fun writeStdin(text: String) {
        runCatching {
            stdin.write(text)
            stdin.flush()
        }
    }

    override fun closeStdin() {
        runCatching { stdin.close() }
    }

    override fun waitFor(timeoutMillis: Long): Boolean =
        runCatching { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }.getOrDefault(!process.isAlive)

    override fun exitCode(): Int? =
        if (process.isAlive) null else runCatching { process.exitValue() }.getOrNull()

    override fun terminate() {
        // PRoot ignores SIGTERM and Android's destroyForcibly() only sends SIGTERM, so the tree of
        // this exact launcher (pid + start time) is SIGKILLed, children first. The server's PRoot
        // tree shares the launcher path and rootfs, which is why roots are never found by pattern.
        val roots = if (process.isAlive) runCatching(findRoots).getOrDefault(emptyList()) else emptyList()
        terminateProcessTree(process, scanner, roots = roots, signal = signal)
        closeStdin()
    }

    override fun isAlive(): Boolean = process.isAlive
}
