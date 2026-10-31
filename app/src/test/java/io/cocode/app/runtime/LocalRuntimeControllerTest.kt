package io.cocode.app.runtime

import io.cocode.app.runtime.install.InstallReporter
import io.cocode.app.runtime.install.InstalledRuntime
import io.cocode.app.runtime.install.PackageVersionSource
import io.cocode.app.runtime.install.PackageVersions
import io.cocode.app.runtime.install.RuntimeInstaller
import io.cocode.app.runtime.install.RuntimeMetadata
import io.cocode.app.runtime.manifest.AgentSpec
import io.cocode.app.runtime.process.HealthProbe
import io.cocode.app.runtime.process.HealthReport
import io.cocode.app.runtime.process.T3Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private val agents =
    listOf(
        AgentSpec("codex", "@openai/codex", "codex", "Codex"),
        AgentSpec("claude", "@anthropic-ai/claude-code", "claude", "Claude Code"),
        AgentSpec("opencode", "@opencode/cli", "opencode", "OpenCode"),
    )

private val pins = PackageVersions(t3 = "0.1.6-pw.5", agents = mapOf("opencode" to "2.2.5"))

private fun runtimeWith(
    root: File,
    versions: PackageVersions = pins,
) = InstalledRuntime(
    metadata =
        RuntimeMetadata(
            runtimeVersion = "r",
            alpineVersion = "3.24.1",
            abi = "arm64-v8a",
            port = 3773,
            t3Version = versions.t3,
            agents = versions.agents,
            installedAt = 1L,
        ),
    rootfs = File(root, "rootfs"),
)

/**
 * Controller state machine tests. The watchdog is driven with bounded virtual-time steps, and the
 * controller scopes are cancelled inside each test body (see [controllerTest]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalRuntimeControllerTest {
    @get:Rule val folder = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun cancelScopes() {
        scopes.forEach { it.cancel() }
        scopes.clear()
    }

    private fun controllerTest(body: suspend TestScope.() -> Unit): TestResult =
        runTest {
            try {
                body()
            } finally {
                scopes.forEach { it.cancel() }
            }
        }

    private class FakeInstaller(
        private val root: File,
        var installed: InstalledRuntime? = null,
        var installFails: Boolean = false,
    ) : RuntimeInstaller {
        var failWith: Throwable? = null
        var updateFailWith: Throwable? = null
        var applyFailWith: Throwable? = null
        val installCalls = mutableListOf<Set<String>>()
        val updateCalls = mutableListOf<PackageVersions>()
        val applyCalls = mutableListOf<Set<String>>()
        var discarded = 0
        var removed = 0
        var recovered = 0

        override fun recover(): Boolean {
            recovered++
            return false
        }

        override fun installedRuntime(): InstalledRuntime? = installed

        override suspend fun install(
            report: InstallReporter,
            agents: Set<String>,
        ): InstalledRuntime {
            installCalls += agents
            report.step(InstallStep.DownloadRootfs, 0.5f, "half")
            failWith?.let { throw it }
            if (installFails) error("network down")
            return runtimeWith(root, PackageVersions(t3 = "0.1.6-pw.5", agents = agents.associateWith { "2.2.5" }))
                .also { installed = it }
        }

        override suspend fun update(
            report: InstallReporter,
            versions: PackageVersions,
        ): InstalledRuntime {
            updateCalls += versions
            report.step(InstallStep.CopyEnvironment, null, null, MessageKey.ProgressCopyingEnvironment)
            updateFailWith?.let { throw it }
            return runtimeWith(root, versions).also { installed = it }
        }

        override suspend fun applyAgents(
            report: InstallReporter,
            selected: Set<String>,
        ): InstalledRuntime {
            applyCalls += selected
            applyFailWith?.let { throw it }
            val current = checkNotNull(installed)
            val next =
                current.copy(
                    metadata =
                        current.metadata.copy(
                            agents = selected.associateWith { current.metadata.agents[it] ?: "2.3.0" },
                        ),
                )
            installed = next
            return next
        }

        override fun discardEnvironment() {
            discarded++
            installed = null
        }

        override fun removeAll() {
            removed++
            installed = null
        }
    }

    private class FakeServer(
        var startError: Throwable? = null,
    ) : T3Server {
        val passwords = mutableListOf<String>()
        var startAttempts = 0
        var failNextStarts = 0
        var alive = false
        var stops = 0
        private var launch = 0L

        override fun start(
            runtime: InstalledRuntime,
            uiPassword: String,
            onLog: (String) -> Unit,
            isCancelled: () -> Boolean,
        ): Long {
            startAttempts++
            startError?.let { throw it }
            if (failNextStarts > 0) {
                failNextStarts--
                error("restart did not come up")
            }
            passwords += uiPassword
            alive = true
            return ++launch
        }

        override fun stop() {
            stops++
            alive = false
        }

        override fun isAlive(): Boolean = alive

        override fun readyLaunchId(): Long? = if (alive) launch else null
    }

    private class FakeHealth(
        var ok: Boolean = true,
    ) : HealthProbe {
        override fun read(port: Int): HealthReport? = if (ok) HealthReport(environmentId = "a") else null
    }

    private fun TestScope.controller(
        installer: FakeInstaller,
        server: T3Server,
        health: HealthProbe = FakeHealth(),
        abi: String = "arm64-v8a",
        states: MutableList<RuntimeState>? = null,
        killed: MutableList<Int>? = null,
        requested: MutableList<RuntimeOperation>? = null,
        selection: AgentSelectionStore = InMemoryAgentSelectionStore(),
        policy: RestartPolicy = RestartPolicy(listOf(1_000L, 2_000L), healthyResetMillis = 120_000L),
        watchdogPollMillis: Long = 500L,
        versionSource: PackageVersionSource? = null,
    ): DefaultLocalRuntimeController {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        scopes += scope
        return DefaultLocalRuntimeController(
            paths = RuntimePaths(folder.root),
            abi = abi,
            installer = installer,
            server = server,
            health = health,
            uiPassword = { "ui-secret" },
            manifestAgents = agents,
            selectionStore = selection,
            pinnedT3Version = "0.1.6-pw.5",
            listener = RuntimeStateListener { state -> states?.add(state) },
            foregroundStarter = ForegroundStarter { op -> requested?.add(op) },
            killOwned = { killed?.add(1) },
            ioDispatcher = StandardTestDispatcher(testScheduler),
            scope = scope,
            nowMillis = { testScheduler.currentTime },
            restartPolicy = policy,
            watchdogPollMillis = watchdogPollMillis,
            supportedAbis = setOf("arm64-v8a", "x86_64"),
            versionSource = versionSource,
        )
    }

    private fun TestScope.step(millis: Long = 0L) {
        runCurrent()
        if (millis > 0) advanceTimeBy(millis)
        runCurrent()
    }

    @Test
    fun `fresh device reports NotInstalled after recovery and offers the manifest agents`() =
        controllerTest {
            val controller = controller(FakeInstaller(folder.root), FakeServer())
            controller.initialize()
            step()

            assertEquals(RuntimeState.NotInstalled, controller.state.value)
            assertEquals(setOf("opencode"), controller.agents.value.selected)
            assertEquals(agents.map { it.id }, controller.agents.value.available.map { it.id })
        }

    @Test
    fun `unsupported abi short-circuits`() =
        controllerTest {
            val controller = controller(FakeInstaller(folder.root), FakeServer(), abi = "armeabi-v7a")
            controller.initialize()
            step()
            assertEquals(RuntimeState.UnsupportedAbi, controller.state.value)
        }

    @Test
    fun `install passes the selected agents and reaches Running with t3 and agent versions`() =
        controllerTest {
            val installer = FakeInstaller(folder.root)
            val server = FakeServer()
            val controller = controller(installer, server)
            controller.initialize()
            step()

            controller.perform(RuntimeOperation.Install)
            step()

            assertEquals(listOf(setOf("opencode")), installer.installCalls)
            assertTrue(controller.state.value is RuntimeState.Running)
            assertEquals("ui-secret", server.passwords.single())
            assertEquals("0.1.6-pw.5", controller.info.value?.t3Version)
            assertEquals(mapOf("opencode" to "2.2.5"), controller.info.value?.agents)
            assertEquals(mapOf("opencode" to "2.2.5"), controller.agents.value.installed)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `a selection saved before setup is used by install`() =
        controllerTest {
            val installer = FakeInstaller(folder.root)
            val selection = InMemoryAgentSelectionStore()
            val requested = mutableListOf<RuntimeOperation>()
            val controller = controller(installer, FakeServer(), selection = selection, requested = requested)
            controller.initialize()
            step()

            controller.setAgents(setOf("codex", "opencode"))
            assertEquals(setOf("codex", "opencode"), selection.selected())
            assertTrue("no runtime installed yet: no operation is queued", requested.isEmpty())

            controller.perform(RuntimeOperation.Install)
            step()
            assertEquals(setOf("codex", "opencode"), installer.installCalls.single())
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `an empty agent selection is rejected`() =
        controllerTest {
            val controller = controller(FakeInstaller(folder.root), FakeServer())
            assertThrows(IllegalArgumentException::class.java) { controller.setAgents(emptySet()) }
            controller.initialize()
            step()
            assertEquals(setOf("opencode"), controller.agents.value.selected)
        }

    @Test
    fun `changing the selection after install queues ApplyAgents`() =
        controllerTest {
            val installer = FakeInstaller(folder.root, installed = runtimeWith(folder.root))
            val requested = mutableListOf<RuntimeOperation>()
            val selection = InMemoryAgentSelectionStore()
            val controller = controller(installer, FakeServer(), selection = selection, requested = requested)
            controller.initialize()
            step()

            controller.setAgents(setOf("codex"))
            assertEquals(listOf(RuntimeOperation.ApplyAgents), requested)
            assertEquals(setOf("codex"), selection.selected())

            controller.perform(RuntimeOperation.ApplyAgents)
            step()

            assertEquals(listOf(setOf("codex")), installer.applyCalls)
            assertEquals(mapOf("codex" to "2.3.0"), controller.agents.value.installed)
            assertEquals(RuntimeState.Stopped, controller.state.value)
        }

    @Test
    fun `ApplyAgents restarts a running server and keeps it running`() =
        controllerTest {
            val installer = FakeInstaller(folder.root, installed = runtimeWith(folder.root))
            val server = FakeServer()
            val controller = controller(installer, server)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()
            assertTrue(controller.state.value is RuntimeState.Running)

            controller.setAgents(setOf("codex"))
            controller.perform(RuntimeOperation.ApplyAgents)
            step()

            assertEquals(1, installer.applyCalls.size)
            assertTrue(controller.state.value is RuntimeState.Running)
            assertEquals(2, server.startAttempts)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `a failed ApplyAgents records the failure and keeps the old metadata`() =
        controllerTest {
            val installer = FakeInstaller(folder.root, installed = runtimeWith(folder.root))
            installer.applyFailWith = UserFacingException(MessageKey.AgentInstallFailed, "npm failed", detail = "EAI_AGAIN")
            val controller = controller(installer, FakeServer())
            controller.initialize()
            step()

            controller.setAgents(setOf("codex"))
            controller.perform(RuntimeOperation.ApplyAgents)
            step()

            assertEquals(MessageKey.AgentInstallFailed, controller.updates.value.lastFailure?.messageKey)
            assertEquals("EAI_AGAIN", controller.updates.value.lastFailure?.detail)
            assertEquals(mapOf("opencode" to "2.2.5"), controller.info.value?.agents)
            assertEquals(RuntimeState.Stopped, controller.state.value)
        }

    @Test
    fun `a second Start while Running does not restart the server`() =
        controllerTest {
            val server = FakeServer()
            val controller = controller(FakeInstaller(folder.root, installed = runtimeWith(folder.root)), server)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()
            controller.perform(RuntimeOperation.Start)
            step()

            assertEquals(1, server.startAttempts)
            assertTrue(controller.state.value is RuntimeState.Running)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `stop kills owned processes and ends Stopped`() =
        controllerTest {
            val killed = mutableListOf<Int>()
            val controller = controller(FakeInstaller(folder.root, installed = runtimeWith(folder.root)), FakeServer(), killed = killed)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()
            controller.perform(RuntimeOperation.Stop)
            step()

            assertEquals(RuntimeState.Stopped, controller.state.value)
            assertTrue(killed.isNotEmpty())
        }

    @Test
    fun `repair installs the current selection and never discards the live environment first`() =
        controllerTest {
            val installer = FakeInstaller(folder.root, installed = runtimeWith(folder.root))
            val controller = controller(installer, FakeServer())
            controller.initialize()
            step()

            controller.setAgents(setOf("claude"))
            controller.perform(RuntimeOperation.Repair)
            step()

            assertEquals(setOf("claude"), installer.installCalls.single())
            assertEquals(0, installer.discarded)
        }

    @Test
    fun `install failure is tagged Install so Retry reinstalls`() =
        controllerTest {
            val installer = FakeInstaller(folder.root)
            installer.failWith = UserFacingException(MessageKey.ServerInstallFailed, "bad bundle", detail = "sha")
            val controller = controller(installer, FakeServer())
            controller.perform(RuntimeOperation.Install)
            step()

            val failed = controller.state.value as RuntimeState.Failed
            assertEquals(FailurePhase.Install, failed.phase)
            assertEquals(MessageKey.ServerInstallFailed, failed.messageKey)
            assertEquals("sha", failed.detail)
        }

    @Test
    fun `crash recovery exhausts the budget and ends Failed(Runtime)`() =
        controllerTest {
            val server = FakeServer()
            val health = FakeHealth(ok = true)
            val controller =
                controller(
                    FakeInstaller(folder.root, installed = runtimeWith(folder.root)),
                    server,
                    health = health,
                    policy = RestartPolicy(listOf(1_000L), healthyResetMillis = 120_000L),
                )
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()
            health.ok = false

            repeat(30) { step(500L) }

            assertTrue(controller.state.value is RuntimeState.Failed)
            assertEquals(FailurePhase.Runtime, (controller.state.value as RuntimeState.Failed).phase)
            assertFalse(controller.busy.value)
        }

    @Test
    fun `uninstall returns to NotInstalled and clears the update state`() =
        controllerTest {
            val installer =
                FakeInstaller(
                    folder.root,
                    installed = runtimeWith(folder.root, PackageVersions("0.1.4-pw.1", mapOf("opencode" to "2.2.5"))),
                )
            val controller = controller(installer, FakeServer())
            controller.initialize()
            step()
            assertTrue(controller.updates.value.available != null)

            controller.perform(RuntimeOperation.Uninstall)
            step()

            assertEquals(RuntimeState.NotInstalled, controller.state.value)
            assertEquals(1, installer.removed)
            assertEquals(RuntimeUpdateState(), controller.updates.value)
            assertTrue(controller.agents.value.installed.isEmpty())
        }

    // --- Updates ---

    private class FixedSource(
        var answer: PackageVersions? = null,
        var error: Throwable? = null,
    ) : PackageVersionSource {
        var calls = 0

        override suspend fun latest(pinned: PackageVersions): PackageVersions {
            calls++
            error?.let { throw it }
            return answer ?: pinned
        }
    }

    @Test
    fun `bundled pins newer than the installed runtime are offered without network`() =
        controllerTest {
            val installed =
                FakeInstaller(
                    folder.root,
                    installed = runtimeWith(folder.root, PackageVersions("0.1.4-pw.1", mapOf("opencode" to "2.2.0"))),
                )
            val controller = controller(installed, FakeServer())
            controller.initialize()
            step()

            val available = controller.updates.value.available
            assertEquals(VersionChange("0.1.4-pw.1", "0.1.6-pw.5"), available?.t3)
            // The manifest has no agent version pins: agents move only when npm reports newer ones.
            assertFalse(available!!.agents.any { it.change.changed })
            assertFalse(controller.updates.value.checkFailed)
        }

    @Test
    fun `a registry answer newer than the installed versions is offered`() =
        controllerTest {
            val controller =
                controller(
                    FakeInstaller(folder.root, installed = runtimeWith(folder.root)),
                    FakeServer(),
                    versionSource = FixedSource(PackageVersions("0.1.7-pw.1", mapOf("opencode" to "2.3.0"))),
                )
            controller.initialize()
            step()
            controller.checkForUpdates()
            step()

            val update = controller.updates.value.available
            assertEquals(VersionChange("0.1.6-pw.5", "0.1.7-pw.1"), update?.t3)
            assertEquals(VersionChange("2.2.5", "2.3.0"), update?.agents?.single()?.change)
        }

    @Test
    fun `a failed check sets checkFailed and keeps the bundled target`() =
        controllerTest {
            val controller =
                controller(
                    FakeInstaller(folder.root, installed = runtimeWith(folder.root, PackageVersions("0.1.4-pw.1", emptyMap()))),
                    FakeServer(),
                    versionSource = FixedSource(error = java.io.IOException("offline")),
                )
            controller.initialize()
            step()

            assertTrue(controller.updates.value.checkFailed)
            assertEquals("0.1.6-pw.5", controller.updates.value.available?.t3?.target)
        }

    @Test
    fun `an older installed version is never offered a downgrade`() =
        controllerTest {
            val controller =
                controller(
                    FakeInstaller(folder.root, installed = runtimeWith(folder.root, PackageVersions("0.2.0-pw.1", mapOf("opencode" to "2.2.5")))),
                    FakeServer(),
                )
            controller.initialize()
            step()
            assertEquals(null, controller.updates.value.available)
        }

    @Test
    fun `update restarts the server and passes t3 plus selected agent targets`() =
        controllerTest {
            val installed =
                FakeInstaller(
                    folder.root,
                    installed = runtimeWith(folder.root, PackageVersions("0.1.4-pw.1", mapOf("opencode" to "2.2.5"))),
                )
            val controller = controller(installed, FakeServer())
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()
            assertTrue(controller.state.value is RuntimeState.Running)

            controller.perform(RuntimeOperation.Update)
            step()

            assertEquals(PackageVersions("0.1.6-pw.5", mapOf("opencode" to "2.2.5")), installed.updateCalls.single())
            assertTrue(controller.state.value is RuntimeState.Running)
            assertEquals(null, controller.updates.value.lastFailure)
            assertEquals("0.1.6-pw.5", controller.info.value?.t3Version)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `a failed update keeps the runtime usable and records the reason`() =
        controllerTest {
            val installed =
                FakeInstaller(
                    folder.root,
                    installed = runtimeWith(folder.root, PackageVersions("0.1.4-pw.1", mapOf("opencode" to "2.2.5"))),
                )
            installed.updateFailWith = UserFacingException(MessageKey.UpdateNotEnoughSpace, "no space", detail = "df")
            val controller = controller(installed, FakeServer())
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()

            controller.perform(RuntimeOperation.Update)
            step()

            assertTrue(controller.state.value is RuntimeState.Running)
            assertEquals(MessageKey.UpdateNotEnoughSpace, controller.updates.value.lastFailure?.messageKey)
            assertEquals("df", controller.updates.value.lastFailure?.detail)
            assertEquals("0.1.4-pw.1", controller.info.value?.t3Version)
            assertTrue(controller.updates.value.available != null)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `automatic checks are throttled and manual checks ignore the throttle`() =
        controllerTest {
            val source = FixedSource(answer = pins)
            val controller =
                controller(
                    FakeInstaller(folder.root, installed = runtimeWith(folder.root)),
                    FakeServer(),
                    versionSource = source,
                )
            controller.initialize()
            step()
            assertEquals(1, source.calls)

            controller.checkForUpdates()
            step()
            assertEquals(2, source.calls)
        }
}
