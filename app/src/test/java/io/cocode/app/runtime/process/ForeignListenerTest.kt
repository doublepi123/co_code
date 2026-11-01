package io.cocode.app.runtime.process

import io.cocode.app.runtime.RuntimePaths
import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.install.InstalledRuntime
import io.cocode.app.runtime.install.RuntimeMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket

/**
 * Port identity. A foreign process may hold the T3 Code port. Trust order in readiness is: our
 * child's own output (listen line) first, then the environment document. These tests prove a
 * foreign answer is never trusted and the password is never sent.
 */
class ForeignListenerTest {
    @get:Rule val folder = TemporaryFolder()

    private val port = 3773

    private fun runtime() =
        InstalledRuntime(
            metadata =
                RuntimeMetadata(
                    runtimeVersion = "r",
                    alpineVersion = "3.24.1",
                    abi = "arm64-v8a",
                    port = port,
                    t3Version = "0.1.6-pw.5",
                    agents = mapOf("opencode" to "2.2.5"),
                    installedAt = 1L,
                ),
            rootfs = File(folder.root, "rootfs"),
        )

    /** A stand-in for our PRoot child: fixed output, then an exit code. */
    private class FakeChild(
        private val output: String,
        private val exitCode: Int,
        private val lifetimeMillis: Long = 0L,
    ) : Process() {
        private val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        private val startedAt = System.nanoTime()

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = ByteArrayInputStream(output.toByteArray())

        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

        override fun waitFor(): Int {
            while (isAlive) Thread.sleep(5)
            return exitValue()
        }

        override fun exitValue(): Int {
            if (isAlive) throw IllegalThreadStateException("still running")
            return exitCode
        }

        override fun destroy() {
            alive.set(false)
        }

        override fun isAlive(): Boolean {
            val elapsed = (System.nanoTime() - startedAt) / 1_000_000
            if (elapsed >= lifetimeMillis) alive.set(false)
            return alive.get()
        }
    }

    /** Health probe that records every call; a foreign server answering counts as a call. */
    private class RecordingHealth(
        private val report: HealthReport?,
    ) : HealthProbe {
        var calls = 0
        val ports = mutableListOf<Int>()

        override fun read(port: Int): HealthReport? {
            calls++
            ports += port
            return report
        }
    }

    private fun foreignOk() = HealthReport(environmentId = "foreign", serverVersion = "0.1.6-pw.5")

    private fun openChamberOk() =
        HealthReport(
            kind = ServerKind.OpenChamber,
            status = "ok",
            serverId = "srv-1",
            openCodeReady = true,
            openCodeRunning = true,
        )

    private fun server(
        child: Process,
        health: HealthProbe,
        spawned: MutableList<Boolean>,
        precheckFree: Boolean = true,
        maxLogBytes: Long = 1_048_576L,
        spawnOverride: (() -> Process)? = null,
        capturedEnv: MutableList<Map<String, String>> = mutableListOf(),
        kind: ServerKind = ServerKind.T3,
        precheckPorts: MutableList<Int> = mutableListOf(),
    ) = ProotRuntimeServer(
        paths = RuntimePaths(folder.root),
        kind = kind,
        suite = {
            EmbeddedCommandSuite.Paths(
                home = folder.root,
                tmp = folder.root,
                nativeLibraryDirectory = folder.root,
                proot = File(folder.root, "libcocode_android_proot.so"),
                loader = folder.root,
                loader32 = folder.root,
            )
        },
        health = health,
        scanner = ProcessScanner(folder.newFolder("proc"), myUid = 1, myPid = 2L),
        cleanBeforeStart = { port, _ ->
            precheckPorts += port
            precheckFree
        },
        readyTimeoutMillis = 2_000L,
        pollMillis = 1L,
        maxLogBytes = maxLogBytes,
        sleep = { Thread.sleep(1) },
        spawn = { _, env, _ ->
            capturedEnv += env
            spawned += true
            spawnOverride?.invoke() ?: child
        },
    )

    @Test
    fun `port taken after the precheck - our child exits with EADDRINUSE and no password is sent`() {
        ServerSocket(0).use { foreign ->
            val child = FakeChild(output = "Error: listen EADDRINUSE: address already in use 127.0.0.1:$port\n", exitCode = 1)
            val health = RecordingHealth(foreignOk())
            val spawned = mutableListOf<Boolean>()

            val error =
                runCatching {
                    server(child, health, spawned).start(runtime(), "ui-secret", {}, { false })
                }.exceptionOrNull()

            assertTrue("expected PortInUseException, got $error", error is PortInUseException)
            assertEquals("Port 3773 is in use by another app", error?.message)
            assertEquals(1, spawned.size)
            assertTrue("foreign environment must never be trusted without our listen line", health.calls == 0)
            assertTrue(foreign.isBound)
        }
    }

    @Test
    fun `a foreign server answering is not trusted when our child printed no listen line`() {
        val child = FakeChild(output = "", exitCode = 0, lifetimeMillis = 10_000L)
        val health = RecordingHealth(foreignOk())
        val spawned = mutableListOf<Boolean>()

        val error =
            runCatching {
                server(child, health, spawned).start(runtime(), "ui-secret", {}, { false })
            }.exceptionOrNull()

        assertTrue("start must fail, got $error", error != null)
        assertEquals("environment document must not be consulted before our listen line", 0, health.calls)
    }

    @Test
    fun `our child listening and healthy is trusted and returns a launch id`() {
        val child = FakeChild(output = "Listening on http://127.0.0.1:$port\n", exitCode = 0, lifetimeMillis = 10_000L)
        val spawned = mutableListOf<Boolean>()

        val launchId = server(child, RecordingHealth(foreignOk()), spawned).start(runtime(), "ui-secret", {}, { false })

        assertTrue(launchId > 0)
    }

    @Test
    fun `readyLaunchId is null as soon as our child is dead, without any watchdog tick`() {
        // Long enough to outlive start() on a loaded machine; the assertion below is about death.
        val child = FakeChild(output = "Listening on http://127.0.0.1:$port\n", exitCode = 0, lifetimeMillis = 2_000L)
        val srv = server(child, RecordingHealth(foreignOk()), mutableListOf())

        val id = srv.start(runtime(), "ui-secret", {}, { false })
        assertEquals(id, srv.readyLaunchId())

        while (child.isAlive) Thread.sleep(5)

        assertEquals(null, srv.readyLaunchId())
    }

    /**
     * Android throws InterruptedIOException from the child's stream when stop closes it mid-read. The
     * output thread must absorb that: an uncaught exception there crashes the whole app.
     */
    @Test
    fun `output pump survives a stream closed mid-read without an uncaught exception`() {
        val uncaught = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.set(error) }
        try {
            val closing =
                object : Process() {
                    private val stream =
                        object : java.io.InputStream() {
                            private var reads = 0

                            override fun read(): Int {
                                if (reads++ == 0) return 'O'.code
                                throw java.io.InterruptedIOException("read interrupted by close() on another thread")
                            }
                        }

                    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

                    override fun getInputStream(): InputStream = stream

                    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

                    override fun waitFor(): Int = 0

                    override fun exitValue(): Int = 0

                    override fun destroy() {}

                    override fun isAlive(): Boolean = false
                }
            val srv = server(closing, RecordingHealth(null), mutableListOf())
            runCatching { srv.start(runtime(), "ui-secret", {}, { false }) }
            Thread.sleep(200)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }

        assertEquals("no uncaught exception may escape the output thread", null, uncaught.get())
    }

    @Test
    fun `the listen marker must match our port`() {
        assertTrue(Readiness.childListening(listOf("Listening on http://127.0.0.1:3773"), 3773))
        assertTrue(!Readiness.childListening(listOf("Listening on http://127.0.0.1:3774"), 3773))
    }

    @Test
    fun `precheck failure never spawns the child`() {
        val spawned = mutableListOf<Boolean>()
        val error =
            runCatching {
                server(FakeChild("", 0), RecordingHealth(null), spawned, precheckFree = false)
                    .start(runtime(), "ui-secret", {}, { false })
            }.exceptionOrNull()

        assertTrue(error is PortInUseException)
        assertEquals(0, spawned.size)
    }

    @Test
    fun `openchamber uses its own port, marker, environment and policy`() {
        val child =
            FakeChild(
                output = "OpenChamber server listening on 127.0.0.1:${ServerKind.OpenChamber.port}\n",
                exitCode = 0,
                lifetimeMillis = 10_000L,
            )
        val health = RecordingHealth(openChamberOk())
        val envs = mutableListOf<Map<String, String>>()
        val ports = mutableListOf<Int>()

        val launchId =
            server(
                child,
                health,
                mutableListOf(),
                capturedEnv = envs,
                kind = ServerKind.OpenChamber,
                precheckPorts = ports,
            ).start(runtime(), "oc-secret", {}, { false })

        assertTrue(launchId > 0)
        assertEquals("the runtime metadata port must not decide the openchamber port", listOf(3000), ports)
        assertEquals(listOf(3000), health.ports)
        assertEquals("oc-secret", envs.single()["OPENCHAMBER_UI_PASSWORD"])
        assertTrue(envs.single().keys.none { it.startsWith("T3CODE_") })

        val policy = File(folder.root, "rootfs/etc/openchamber/policy.json")
        assertEquals(OpenChamberPolicy.CONTENT, policy.readText())
    }

    @Test
    fun `an openchamber opencode failure fails clearly instead of waiting for the timeout`() {
        val child =
            FakeChild(
                output = "OpenChamber server listening on 127.0.0.1:${ServerKind.OpenChamber.port}\n",
                exitCode = 0,
                lifetimeMillis = 10_000L,
            )
        val failed =
            HealthReport(
                kind = ServerKind.OpenChamber,
                status = "ok",
                serverId = "srv-1",
                openCodeReady = false,
                openCodeRunning = false,
                lastOpenCodeError = "opencode exited with code 1",
            )

        val error =
            runCatching {
                server(child, RecordingHealth(failed), mutableListOf(), kind = ServerKind.OpenChamber)
                    .start(runtime(), "oc-secret", {}, { false })
            }.exceptionOrNull()
        val message = error?.message.orEmpty()

        assertTrue("start must fail, got $error", error != null)
        assertTrue(message.contains("OpenChamber could not start OpenCode"))
        assertTrue(message.contains("opencode exited with code 1"))
    }
}
