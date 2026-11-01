package io.cocode.app.agentlogin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class CodexLoginControllerTest {
    private val unconfined = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val realDispatcher = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The real 0.166.0 output shape, colored, including the harmless PATH-alias warning. */
    private val realOutput =
        listOf(
            "\u001B[33mWARNING: proceeding, even though we could not create PATH aliases\u001B[0m",
            "Follow these steps to sign in with ChatGPT using device code authorization:",
            "",
            "1. Open this link in your browser and sign in to your account",
            "   \u001B[36mhttps://auth.openai.com/codex/device\u001B[0m",
            "",
            "2. Enter this one-time code (expires in 15 minutes)",
            "   \u001B[1mABCD-EFGH\u001B[0m",
        )

    @Test
    fun `parses colored device output into WaitingForUser`() =
        runBlocking {
            val runner = FakeRunner { command ->
                assertEquals(CodexLoginCommand.DeviceAuth, command)
                FakeHandle(realOutput, exit = 0, stayOpen = true)
            }
            val controller = controller(runner, realDispatcher)
            controller.startDeviceLogin()

            val waiting =
                withTimeout(5_000) { controller.state.first { it is CodexLoginState.WaitingForUser } }
                    as CodexLoginState.WaitingForUser
            assertEquals("https://auth.openai.com/codex/device", waiting.url)
            assertEquals("ABCD-EFGH", waiting.code)
            val now = System.currentTimeMillis()
            assertTrue(
                "expiry should be about 15 minutes out, was ${waiting.expiresAtMillis - now}ms",
                waiting.expiresAtMillis in (now + 14 * 60_000L)..(now + 15 * 60_000L + 5_000L),
            )

            controller.cancel()
            withTimeout(5_000) { controller.state.first { it is CodexLoginState.Cancelled } }
            awaitTerminated(runner.handles.single())
        }

    @Test
    fun `successful device login verifies and reports SignedIn`() {
        val runner = FakeRunner { command ->
            when (command) {
                CodexLoginCommand.DeviceAuth -> FakeHandle(realOutput, exit = 0)
                CodexLoginCommand.Status -> FakeHandle(listOf("Logged in using ChatGPT"), exit = 0)
                CodexLoginCommand.WithApiKey -> error("unexpected API-key command")
            }
        }
        val controller = controller(runner, unconfined)

        controller.startDeviceLogin()

        assertEquals(CodexLoginState.SignedIn("Logged in using ChatGPT"), controller.state.value)
        assertEquals(true, controller.signedIn.value)
        assertEquals(listOf(CodexLoginCommand.DeviceAuth, CodexLoginCommand.Status), runner.commands)
    }

    @Test
    fun `failed device login reports a bounded tail`() {
        val runner = FakeRunner { command ->
            when (command) {
                CodexLoginCommand.DeviceAuth ->
                    FakeHandle(
                        listOf("\u001B[31mERROR: device auth failed\u001B[0m", "device auth timed out after 15 minutes"),
                        exit = 1,
                    )
                else -> error("status must not run after a failed login")
            }
        }
        val controller = controller(runner, unconfined)

        controller.startDeviceLogin()

        val failed = controller.state.value as CodexLoginState.Failed
        assertTrue(failed.message.contains("failed"))
        assertTrue(failed.detail!!.contains("ERROR: device auth failed"))
        assertFalse(failed.detail!!.contains("\u001B"))
        assertEquals(null, controller.signedIn.value)
    }

    @Test
    fun `cancel terminates the guest command and yields Cancelled`() =
        runBlocking {
            val runner = FakeRunner { FakeHandle(realOutput, exit = 0, stayOpen = true) }
            val controller = controller(runner, realDispatcher)
            controller.startDeviceLogin()
            withTimeout(5_000) { controller.state.first { it is CodexLoginState.WaitingForUser } }

            controller.cancel()

            assertEquals(CodexLoginState.Cancelled, controller.state.value)
            awaitTerminated(runner.handles.first())
        }

    @Test
    fun `api key submitted during the device flow replaces it and signs in`() =
        runBlocking {
            val secret = "sk-test-replace-456"
            val seen = java.util.Collections.synchronizedList(mutableListOf<CodexLoginState>())
            val runner = FakeRunner { command ->
                when (command) {
                    CodexLoginCommand.DeviceAuth -> FakeHandle(realOutput, exit = 0, stayOpen = true)
                    CodexLoginCommand.WithApiKey -> FakeHandle(listOf("Saved API key"), exit = 0)
                    CodexLoginCommand.Status -> FakeHandle(listOf("Logged in using an API key"), exit = 0)
                }
            }
            val controller = controller(runner, realDispatcher)
            val collector = realDispatcher.launch { controller.state.collect { seen += it } }
            controller.startDeviceLogin()
            withTimeout(5_000) { controller.state.first { it is CodexLoginState.WaitingForUser } }

            controller.signInWithApiKey(secret)

            withTimeout(5_000) { controller.state.first { it is CodexLoginState.SignedIn } }
            collector.cancel()
            awaitTerminated(runner.handles.first())
            assertEquals(
                listOf(CodexLoginCommand.DeviceAuth, CodexLoginCommand.WithApiKey, CodexLoginCommand.Status),
                runner.commands,
            )
            assertEquals("$secret\n", runner.handles[1].stdin)
            assertFalse("the dialog must not close in between", seen.contains(CodexLoginState.Cancelled))
            assertFalse(seen.any { it.toString().contains(secret) })
        }

    @Test
    fun `api key is written to stdin and never appears in any state`() {
        val secret = "sk-test-secret-123"
        val runner = FakeRunner { command ->
            when (command) {
                CodexLoginCommand.WithApiKey -> FakeHandle(listOf("Saved API key"), exit = 0)
                CodexLoginCommand.Status -> FakeHandle(listOf("Logged in using an API key"), exit = 0)
                CodexLoginCommand.DeviceAuth -> error("unexpected device command")
            }
        }
        val controller = controller(runner, unconfined)

        controller.signInWithApiKey(secret)

        assertEquals("$secret\n", runner.handles.first().stdin)
        assertEquals(CodexLoginState.SignedIn("Logged in using an API key"), controller.state.value)
        assertEquals(true, controller.signedIn.value)
        assertFalse(controller.state.value.toString().contains(secret))
    }

    @Test
    fun `api key failure redacts the key from the detail and state`() {
        val secret = "sk-should-never-leak"
        val runner = FakeRunner { command ->
            when (command) {
                CodexLoginCommand.WithApiKey ->
                    FakeHandle(listOf("invalid credential $secret"), exit = 1)
                else -> error("unexpected command")
            }
        }
        val controller = controller(runner, unconfined)

        controller.signInWithApiKey(secret)

        val failed = controller.state.value as CodexLoginState.Failed
        assertEquals("Could not save the API key.", failed.message)
        assertTrue(failed.detail!!.contains("***"))
        assertFalse(failed.detail!!.contains(secret))
        assertFalse(controller.state.value.toString().contains(secret))
    }

    @Test
    fun `double start is a no-op while a login is running`() =
        runBlocking<Unit> {
            val runner = FakeRunner { FakeHandle(realOutput, exit = 0, stayOpen = true) }
            val controller = controller(runner, realDispatcher)
            controller.startDeviceLogin()
            withTimeout(5_000) { controller.state.first { it is CodexLoginState.WaitingForUser } }

            controller.startDeviceLogin()

            assertEquals(1, runner.commands.size)
            assertTrue(controller.state.value is CodexLoginState.WaitingForUser)
            controller.cancel()
            withTimeout(5_000) { controller.state.first { it is CodexLoginState.Cancelled } }
        }

    @Test
    fun `preconditions block the login before any guest command`() {
        val runner = FakeRunner { error("must not start") }
        val controller = controller(runner, unconfined) { "Codex is not installed in the on-device runtime." }

        controller.startDeviceLogin()

        assertEquals(
            CodexLoginState.Failed("Codex is not installed in the on-device runtime.", null),
            controller.state.value,
        )
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun `a status check killed mid-run is retried and never reported as signed out`() {
        var calls = 0
        val runner =
            FakeRunner {
                calls++
                // First run: killed by the server's start-up cleanup (no output, signal exit).
                if (calls == 1) FakeHandle(emptyList(), exit = 137) else FakeHandle(listOf("Logged in using ChatGPT"), exit = 0)
            }
        val controller = controller(runner, unconfined)

        controller.refreshStatus()

        assertEquals(2, calls)
        assertEquals(true, controller.signedIn.value)
    }

    @Test
    fun `a status check that keeps failing leaves the sign-in unknown`() {
        val runner = FakeRunner { FakeHandle(listOf("error: something broke"), exit = 1) }
        val controller = controller(runner, unconfined)

        controller.refreshStatus()

        assertEquals(null, controller.signedIn.value)
        assertEquals(CodexLoginState.Idle, controller.state.value)
        assertTrue(runner.commands.size > 1)
    }

    @Test
    fun `refreshStatus reports not signed in without changing a clean Idle`() {
        val runner = FakeRunner { FakeHandle(listOf("Not logged in"), exit = 1) }
        val controller = controller(runner, unconfined)

        controller.refreshStatus()

        assertEquals(false, controller.signedIn.value)
        assertEquals(CodexLoginState.Idle, controller.state.value)
    }

    @Test
    fun `dismiss only leaves terminal states`() {
        val runner = FakeRunner { FakeHandle(realOutput, exit = 0) }
        val controller = controller(runner, unconfined)
        controller.startDeviceLogin()
        controller.dismiss()
        assertEquals(CodexLoginState.Idle, controller.state.value)
    }

    @Test
    fun `ansi stripping removes colors, cursor codes and hyperlinks`() {
        assertEquals("red", AnsiText.strip("\u001B[1;31mred\u001B[0m"))
        assertEquals(
            "https://auth.openai.com/codex/device",
            AnsiText.strip("\u001B]8;;https://x\u0007https://auth.openai.com/codex/device\u001B]8;;\u0007"),
        )
        assertEquals("plain", AnsiText.strip("plain\r"))
    }

    private fun controller(
        runner: GuestCommandRunner,
        scope: CoroutineScope,
        prerequisites: () -> String? = { null },
    ): CodexLoginController =
        CodexLoginController(runner = runner, prerequisites = prerequisites, scope = scope, statusRetryDelayMillis = 1L)

    /** Cancel terminates on the IO scope; wait for it instead of sampling once. */
    private suspend fun awaitTerminated(handle: FakeHandle) {
        withTimeout(5_000) {
            while (!handle.terminated) delay(10)
        }
    }

    private class FakeRunner(
        private val respond: (CodexLoginCommand) -> FakeHandle,
    ) : GuestCommandRunner {
        val commands = mutableListOf<CodexLoginCommand>()
        val handles = mutableListOf<FakeHandle>()

        override fun start(command: CodexLoginCommand): GuestCommandHandle {
            commands += command
            return respond(command).also { handles += it }
        }
    }

    private class FakeHandle(
        lines: List<String> = emptyList(),
        private val exit: Int = 0,
        private val stayOpen: Boolean = false,
    ) : GuestCommandHandle {
        private val output = LinkedBlockingQueue<GuestOutput>()
        private val stdinBuilder = StringBuilder()

        @Volatile private var closed = false

        @Volatile var terminated = false
            private set

        val stdin: String get() = synchronized(stdinBuilder) { stdinBuilder.toString() }
        val stdinClosed: Boolean get() = closed

        init {
            lines.forEach { output.put(GuestOutput.Line(it)) }
            if (!stayOpen) output.put(GuestOutput.EndOfOutput)
        }

        override fun readOutput(timeoutMillis: Long): GuestOutput {
            if (terminated) return GuestOutput.EndOfOutput
            return output.poll(timeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
                ?: GuestOutput.TimedOut
        }

        override fun writeStdin(text: String) {
            synchronized(stdinBuilder) { stdinBuilder.append(text) }
        }

        override fun closeStdin() {
            closed = true
        }

        override fun waitFor(timeoutMillis: Long): Boolean = true

        override fun exitCode(): Int? = if (stayOpen && !terminated) null else exit

        override fun terminate() {
            terminated = true
        }

        override fun isAlive(): Boolean = stayOpen && !terminated
    }
}
