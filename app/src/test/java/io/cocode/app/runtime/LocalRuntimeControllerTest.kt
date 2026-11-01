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
import io.cocode.app.runtime.process.RuntimeServer
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
): InstalledRuntime {
    val rootfs = File(root, "rootfs")
    File(rootfs, "opt/t3/bin.mjs").apply {
        parentFile?.mkdirs()
        writeText("")
    }
    File(rootfs, "opt/t3/client/index.html").apply {
        parentFile?.mkdirs()
        writeText("")
    }
    val servers =
        versions.servers.takeIf { it.isNotEmpty() }
            ?: mapOf(ServerKind.T3 to versions.t3)
    if (ServerKind.OpenChamber in servers) {
        File(rootfs, "usr/local/lib/node_modules/@openchamber/web/bin/cli.js").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        File(rootfs, "usr/local/lib/node_modules/@openchamber/web/dist/index.html").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        File(rootfs, "usr/local/lib/node_modules/@openchamber/web/node_modules/node-pty").mkdirs()
    }
    return InstalledRuntime(
        metadata =
            RuntimeMetadata(
                runtimeVersion = "r",
                alpineVersion = "3.24.1",
                abi = "arm64-v8a",
                port = 3773,
                t3Version = servers[ServerKind.T3] ?: versions.t3,
                servers = servers.mapKeys { (kind, _) -> kind.id },
                agents = versions.agents,
                installedAt = 1L,
            ),
        rootfs = rootfs,
    )
}

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
        /** Hook run inside applyAgents, used to interleave a Stop with the maintenance operation. */
        var onApply: (() -> Unit)? = null
        val installCalls = mutableListOf<Set<String>>()
        val installServerCalls = mutableListOf<Set<ServerKind>>()
        val updateCalls = mutableListOf<PackageVersions>()
        val applyCalls = mutableListOf<Set<String>>()
        val applyServerCalls = mutableListOf<Set<ServerKind>>()
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
            servers: Set<ServerKind>,
        ): InstalledRuntime {
            installCalls += agents
            installServerCalls += servers
            report.step(InstallStep.DownloadRootfs, 0.5f, "half")
            failWith?.let { throw it }
            if (installFails) error("network down")
            val versions =
                PackageVersions(
                    t3 = "0.1.6-pw.5",
                    agents = agents.associateWith { "2.2.5" },
                    servers = servers.associateWith { kind -> if (kind == ServerKind.T3) "0.1.6-pw.5" else "2.5.3" },
                )
            return runtimeWith(root, versions).also { installed = it }
        }

        override suspend fun update(
            report: InstallReporter,
            versions: PackageVersions,
        ): InstalledRuntime {
            updateCalls += versions
            report.step(InstallStep.CopyEnvironment, null, null, MessageKey.ProgressCopyingEnvironment)
            updateFailWith?.let { throw it }
            val current = checkNotNull(installed)
            val currentServers =
                current.metadata.servers.mapNotNull { (id, version) -> ServerKind.fromId(id)?.let { it to version } }.toMap()
            val nextServers =
                buildMap {
                    putAll(currentServers)
                    if (ServerKind.T3 in currentServers) put(ServerKind.T3, versions.t3)
                    putAll(versions.servers)
                }
            val next = versions.copy(servers = nextServers)
            return runtimeWith(root, next).also { installed = it }
        }

        override suspend fun applyAgents(
            report: InstallReporter,
            selected: Set<String>,
        ): InstalledRuntime {
            applyCalls += selected
            applyFailWith?.let { throw it }
            onApply?.invoke()
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

        override suspend fun applyServers(
            report: InstallReporter,
            selected: Set<ServerKind>,
            agents: Set<String>,
        ): InstalledRuntime {
            applyServerCalls += selected
            applyFailWith?.let { throw it }
            val current = checkNotNull(installed)
            val next =
                current.copy(
                    metadata =
                        current.metadata.copy(
                            servers =
                                selected
                                    .associateWith { kind ->
                                        current.metadata.servers[kind.id]
                                            ?: if (kind == ServerKind.T3) "0.1.6-pw.5" else "2.5.3"
                                    }.mapKeys { (kind, _) -> kind.id },
                            agents = agents.associateWith { current.metadata.agents[it] ?: "2.2.5" },
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
    ) : RuntimeServer {
        val passwords = mutableListOf<String>()
        var startAttempts = 0
        var failNextStarts = 0
        var alive = false
        var stops = 0
        /** Runs inside stop(), so a later Stop can be interleaved with a maintenance stop stage. */
        var onStop: (() -> Unit)? = null
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
            onStop?.invoke()
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
        server: RuntimeServer,
        health: HealthProbe = FakeHealth(),
        abi: String = "arm64-v8a",
        states: MutableList<RuntimeState>? = null,
        killed: MutableList<Int>? = null,
        requested: MutableList<RuntimeOperation>? = null,
        selection: AgentSelectionStore = InMemoryAgentSelectionStore(),
        serverSelection: ServerSelectionStore = InMemoryServerSelectionStore(),
        policy: RestartPolicy = RestartPolicy(listOf(1_000L, 2_000L), healthyResetMillis = 120_000L),
        watchdogPollMillis: Long = 500L,
        versionSource: PackageVersionSource? = null,
        serverFor: ((ServerKind) -> RuntimeServer)? = null,
        pinnedT3Version: String = "0.1.6-pw.5",
    ): DefaultLocalRuntimeController {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        scopes += scope
        return DefaultLocalRuntimeController(
            paths = RuntimePaths(folder.root),
            abi = abi,
            installer = installer,
            serverFor = serverFor ?: { server },
            healthFor = { health },
            uiPassword = { _, _ -> "ui-secret" },
            manifestAgents = agents,
            selectionStore = selection,
            serverSelectionStore = serverSelection,
            pinnedT3Version = pinnedT3Version,
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
            assertEquals(listOf(setOf(ServerKind.T3)), installer.installServerCalls)
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
    fun `an empty agent selection is ignored and keeps the saved selection`() =
        controllerTest {
            val selection = InMemoryAgentSelectionStore(initial = setOf("codex"))
            val requested = mutableListOf<RuntimeOperation>()
            val controller = controller(FakeInstaller(folder.root), FakeServer(), selection = selection, requested = requested)
            controller.initialize()
            step()

            controller.setAgents(emptySet())

            assertEquals(setOf("codex"), controller.agents.value.selected)
            assertEquals(setOf("codex"), selection.selected())
            assertTrue("nothing usable was chosen: no apply is queued", requested.isEmpty())
        }

    @Test
    fun `an unknown-only selection is ignored instead of falling back to defaults`() =
        controllerTest {
            val selection = InMemoryAgentSelectionStore(initial = setOf("codex"))
            val controller = controller(FakeInstaller(folder.root), FakeServer(), selection = selection)
            controller.initialize()
            step()

            controller.setAgents(setOf("not-an-agent"))

            assertEquals(setOf("codex"), controller.agents.value.selected)
            assertEquals(setOf("codex"), selection.selected())
        }

    @Test
    fun `an empty selection on an installed runtime queues no apply`() =
        controllerTest {
            val requested = mutableListOf<RuntimeOperation>()
            val selection = InMemoryAgentSelectionStore(initial = setOf("opencode"))
            val controller =
                controller(
                    FakeInstaller(folder.root, installed = runtimeWith(folder.root)),
                    FakeServer(),
                    selection = selection,
                    requested = requested,
                )
            controller.initialize()
            step()

            controller.setAgents(emptySet())

            assertEquals(setOf("opencode"), controller.agents.value.selected)
            assertEquals(setOf("opencode"), selection.selected())
            assertTrue("an empty draft must not start an operation", requested.isEmpty())
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
    fun `retrying the same selection after a failed apply queues apply again`() =
        controllerTest {
            val installer = FakeInstaller(folder.root, installed = runtimeWith(folder.root))
            val requested = mutableListOf<RuntimeOperation>()
            val selection = InMemoryAgentSelectionStore()
            val controller = controller(installer, FakeServer(), selection = selection, requested = requested)
            controller.initialize()
            step()

            controller.setAgents(setOf("codex"))
            assertEquals(listOf(RuntimeOperation.ApplyAgents), requested)

            installer.applyFailWith = UserFacingException(MessageKey.AgentInstallFailed, "npm failed")
            controller.perform(RuntimeOperation.ApplyAgents)
            step()
            assertEquals("the draft is persisted even though the apply failed", setOf("codex"), selection.selected())
            assertEquals("the installed environment is still the old one", mapOf("opencode" to "2.2.5"), controller.agents.value.installed)

            // The persisted selection now equals the draft, but it was never installed. The UI can
            // still offer Apply (installed keys differ), so the same selection must run again.
            requested.clear()
            installer.applyFailWith = null
            controller.setAgents(setOf("codex"))

            assertEquals(listOf(RuntimeOperation.ApplyAgents), requested)
            controller.perform(RuntimeOperation.ApplyAgents)
            step()

            assertEquals(2, installer.applyCalls.size)
            assertEquals(mapOf("codex" to "2.3.0"), controller.agents.value.installed)
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
    fun `a legacy t3 bundle is always offered the cox release even though it sorts higher`() =
        controllerTest {
            val controller =
                controller(
                    FakeInstaller(
                        folder.root,
                        installed = runtimeWith(folder.root, PackageVersions("0.1.6-pw.6", mapOf("opencode" to "2.2.5"))),
                    ),
                    FakeServer(),
                    versionSource = FixedSource(PackageVersions("0.1.1", mapOf("opencode" to "2.2.5"))),
                    pinnedT3Version = "0.1.1",
                )
            controller.initialize()
            step()
            controller.checkForUpdates()
            step()

            val update = controller.updates.value.available
            assertEquals(VersionChange("0.1.6-pw.6", "0.1.1"), update?.t3)
            assertTrue(update!!.replacesLegacyT3)
            assertEquals("0.1.1", controller.updates.value.available?.t3?.target)
        }

    @Test
    fun `a failed check still offers replacing a legacy bundle with the pinned cox version`() =
        controllerTest {
            val controller =
                controller(
                    FakeInstaller(
                        folder.root,
                        installed = runtimeWith(folder.root, PackageVersions("0.1.6-pw.6", emptyMap())),
                    ),
                    FakeServer(),
                    versionSource = FixedSource(error = java.io.IOException("offline")),
                    pinnedT3Version = "0.1.1",
                )
            controller.initialize()
            step()

            assertEquals(VersionChange("0.1.6-pw.6", "0.1.1"), controller.updates.value.available?.t3)
            assertTrue(controller.updates.value.available!!.replacesLegacyT3)
        }

    @Test
    fun `a cox bundle newer than the pinned version is never offered a downgrade`() =
        controllerTest {
            val controller =
                controller(
                    FakeInstaller(folder.root, installed = runtimeWith(folder.root, PackageVersions("0.2.0", mapOf("opencode" to "2.2.5")))),
                    FakeServer(),
                    pinnedT3Version = "0.1.1",
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
    fun `both servers install together and T3 stays the default active server`() =
        controllerTest {
            val installer = FakeInstaller(folder.root)
            val controller =
                controller(
                    installer,
                    FakeServer(),
                    serverSelection =
                        InMemoryServerSelectionStore(
                            ServerSelection(setOf(ServerKind.T3, ServerKind.OpenChamber), ServerKind.T3),
                        ),
                )
            controller.initialize()
            step()

            controller.perform(RuntimeOperation.Install)
            step()

            assertEquals(setOf(ServerKind.T3, ServerKind.OpenChamber), installer.installServerCalls.single())
            assertEquals(setOf(ServerKind.T3, ServerKind.OpenChamber), controller.servers.value.installed.keys)
            assertEquals(ServerKind.T3, controller.servers.value.active)
            val running = controller.state.value as RuntimeState.Running
            assertEquals(3773, running.port)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `a failed server change keeps the previous installed servers`() =
        controllerTest {
            val installer =
                FakeInstaller(
                    folder.root,
                    installed =
                        runtimeWith(
                            folder.root,
                            PackageVersions(
                                "0.1.6-pw.5",
                                mapOf("opencode" to "2.2.5"),
                                mapOf(ServerKind.T3 to "0.1.6-pw.5", ServerKind.OpenChamber to "2.5.3"),
                            ),
                        ),
                )
            installer.applyFailWith = UserFacingException(MessageKey.Generic, "staging failed")
            val controller =
                controller(
                    installer,
                    FakeServer(),
                    serverSelection =
                        InMemoryServerSelectionStore(
                            ServerSelection(setOf(ServerKind.T3, ServerKind.OpenChamber), ServerKind.T3),
                        ),
                )
            controller.initialize()
            step()

            controller.setServers(setOf(ServerKind.T3))
            controller.perform(RuntimeOperation.ApplyServers)
            step()

            assertEquals(
                "the old installed set survives a failed change",
                setOf(ServerKind.T3, ServerKind.OpenChamber),
                controller.servers.value.installed.keys,
            )
            assertEquals("staging failed", controller.updates.value.lastFailure?.reason)
            assertEquals(RuntimeState.Stopped, controller.state.value)
        }

    @Test
    fun `an OpenChamber update is offered only when OpenChamber is installed`() =
        controllerTest {
            val both =
                runtimeWith(
                    folder.root,
                    PackageVersions(
                        "0.1.6-pw.5",
                        mapOf("opencode" to "2.2.5"),
                        mapOf(ServerKind.T3 to "0.1.6-pw.5", ServerKind.OpenChamber to "2.5.3"),
                    ),
                )
            val source =
                FixedSource(
                    answer =
                        PackageVersions(
                            t3 = "0.1.6-pw.5",
                            agents = mapOf("opencode" to "2.2.5"),
                            servers = mapOf(ServerKind.OpenChamber to "2.5.4"),
                        ),
                )
            val installer = FakeInstaller(folder.root, installed = both)
            val controller =
                controller(
                    installer,
                    FakeServer(),
                    versionSource = source,
                    serverSelection =
                        InMemoryServerSelectionStore(
                            ServerSelection(setOf(ServerKind.T3, ServerKind.OpenChamber), ServerKind.T3),
                        ),
                )
            controller.initialize()
            step()

            assertEquals("2.5.4", controller.updates.value.available?.servers?.get(ServerKind.OpenChamber)?.target)
            controller.perform(RuntimeOperation.Update)
            step()
            assertEquals(
                "the OC target is passed with the installed server map",
                mapOf(ServerKind.OpenChamber to "2.5.4"),
                installer.updateCalls.single().servers,
            )
            assertEquals("2.5.4", controller.info.value?.serverVersions?.get(ServerKind.OpenChamber))
        }

    @Test
    fun `an OC-only update never offers or installs T3`() =
        controllerTest {
            val installer =
                FakeInstaller(
                    folder.root,
                    installed =
                        runtimeWith(
                            folder.root,
                            PackageVersions(
                                "0.1.6-pw.5",
                                mapOf("opencode" to "2.2.5"),
                                mapOf(ServerKind.OpenChamber to "2.5.3"),
                            ),
                        ),
                )
            val source =
                FixedSource(
                    answer =
                        PackageVersions(
                            t3 = "9.9.9",
                            agents = mapOf("opencode" to "2.2.5"),
                            servers = mapOf(ServerKind.OpenChamber to "2.5.4"),
                        ),
                )
            val controller = controller(installer, FakeServer(), versionSource = source)
            controller.initialize()
            step()

            assertEquals("no T3 offer without T3 installed", false, controller.updates.value.available?.t3?.changed)
            controller.perform(RuntimeOperation.Update)
            step()

            assertTrue(installer.installServerCalls.isEmpty())
            assertEquals(mapOf(ServerKind.OpenChamber to "2.5.4"), installer.updateCalls.single().servers)
            assertEquals(setOf(ServerKind.OpenChamber), controller.info.value?.serverVersions?.keys)
            assertEquals(null, controller.info.value?.t3Version)
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

    @Test
    fun `an OpenChamber-only install adds the required OpenCode dependency`() =
        controllerTest {
            val installer = FakeInstaller(folder.root)
            val controller =
                controller(
                    installer,
                    FakeServer(),
                    selection = InMemoryAgentSelectionStore(setOf("codex")),
                    serverSelection = InMemoryServerSelectionStore(ServerSelection(setOf(ServerKind.OpenChamber), ServerKind.OpenChamber)),
                )
            controller.initialize()
            step()
            assertEquals(setOf(ServerKind.OpenChamber), controller.servers.value.selected)
            assertEquals(setOf("opencode"), controller.agents.value.required)

            controller.perform(RuntimeOperation.Install)
            step()

            assertEquals(listOf(setOf(ServerKind.OpenChamber)), installer.installServerCalls)
            assertEquals("user Codex plus the OpenChamber dependency", setOf("codex", "opencode"), installer.installCalls.single())
            assertEquals(setOf(ServerKind.OpenChamber), controller.servers.value.installed.keys)
            assertEquals("2.5.3", controller.info.value?.serverVersions?.get(ServerKind.OpenChamber))
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `selectServer ignores a kind that is not installed`() =
        controllerTest {
            val requested = mutableListOf<RuntimeOperation>()
            val controller =
                controller(
                    FakeInstaller(folder.root, installed = runtimeWith(folder.root)),
                    FakeServer(),
                    requested = requested,
                )
            controller.initialize()
            step()

            controller.selectServer(ServerKind.OpenChamber)

            assertTrue("OpenChamber is not installed", requested.isEmpty())
            assertEquals(ServerKind.T3, controller.servers.value.active)
        }

    @Test
    fun `selectServer stops the former server and runs the chosen kind on its port`() =
        controllerTest {
            val t3 = FakeServer()
            val openChamber = FakeServer()
            val installer =
                FakeInstaller(
                    folder.root,
                    installed =
                        runtimeWith(
                            folder.root,
                            PackageVersions(
                                "0.1.6-pw.5",
                                mapOf("opencode" to "2.2.5"),
                                mapOf(ServerKind.T3 to "0.1.6-pw.5", ServerKind.OpenChamber to "2.5.3"),
                            ),
                        ),
                )
            val requested = mutableListOf<RuntimeOperation>()
            val controller =
                controller(
                    installer,
                    t3,
                    requested = requested,
                    serverSelection =
                        InMemoryServerSelectionStore(
                            ServerSelection(setOf(ServerKind.T3, ServerKind.OpenChamber), ServerKind.T3),
                        ),
                    serverFor = { if (it == ServerKind.T3) t3 else openChamber },
                )
            controller.initialize()
            step()
            assertEquals(setOf(ServerKind.T3, ServerKind.OpenChamber), controller.servers.value.installed.keys)

            controller.selectServer(ServerKind.OpenChamber)
            assertEquals(listOf(RuntimeOperation.SelectServer), requested)
            controller.perform(RuntimeOperation.SelectServer)
            step()

            assertEquals(ServerKind.OpenChamber, controller.servers.value.active)
            assertEquals(1, openChamber.startAttempts)
            assertTrue("the former server was stopped", t3.stops > 0)
            val running = controller.state.value as RuntimeState.Running
            assertEquals(3000, running.port)
            controller.perform(RuntimeOperation.Stop)
            step()
        }

    @Test
    fun `setServers after install queues a transactional server change`() =
        controllerTest {
            val installer =
                FakeInstaller(
                    folder.root,
                    installed =
                        runtimeWith(
                            folder.root,
                            PackageVersions(
                                "0.1.6-pw.5",
                                mapOf("opencode" to "2.2.5"),
                                mapOf(ServerKind.T3 to "0.1.6-pw.5", ServerKind.OpenChamber to "2.5.3"),
                            ),
                        ),
                )
            val requested = mutableListOf<RuntimeOperation>()
            val controller =
                controller(
                    installer,
                    FakeServer(),
                    requested = requested,
                    serverSelection =
                        InMemoryServerSelectionStore(
                            ServerSelection(setOf(ServerKind.T3, ServerKind.OpenChamber), ServerKind.T3),
                        ),
                )
            controller.initialize()
            step()

            controller.setServers(setOf(ServerKind.T3))
            assertEquals(listOf(RuntimeOperation.ApplyServers), requested)
            controller.perform(RuntimeOperation.ApplyServers)
            step()

            assertEquals(listOf(setOf(ServerKind.T3)), installer.applyServerCalls)
            assertEquals(setOf(ServerKind.T3), controller.servers.value.installed.keys)
            assertEquals(RuntimeState.Stopped, controller.state.value)
        }

    @Test
    fun `removing T3 preserves OpenChamber and the agent set`() =
        controllerTest {
            val installer =
                FakeInstaller(
                    folder.root,
                    installed =
                        runtimeWith(
                            folder.root,
                            PackageVersions(
                                "0.1.6-pw.5",
                                mapOf("opencode" to "2.2.5"),
                                mapOf(ServerKind.T3 to "0.1.6-pw.5", ServerKind.OpenChamber to "2.5.3"),
                            ),
                        ),
                )
            val controller =
                controller(
                    installer,
                    FakeServer(),
                    serverSelection =
                        InMemoryServerSelectionStore(
                            ServerSelection(setOf(ServerKind.T3, ServerKind.OpenChamber), ServerKind.T3),
                        ),
                )
            controller.initialize()
            step()

            controller.setServers(setOf(ServerKind.OpenChamber))
            controller.perform(RuntimeOperation.ApplyServers)
            step()

            assertEquals(listOf(setOf(ServerKind.OpenChamber)), installer.applyServerCalls)
            assertEquals(setOf(ServerKind.OpenChamber), controller.servers.value.installed.keys)
            assertEquals(setOf("opencode"), controller.agents.value.installed.keys)
            assertEquals(ServerKind.OpenChamber, controller.servers.value.active)
        }

    @Test
    fun `retrying the same server draft after a failure queues ApplyServers again`() =
        controllerTest {
            val installer =
                FakeInstaller(
                    folder.root,
                    installed =
                        runtimeWith(
                            folder.root,
                            PackageVersions(
                                "0.1.6-pw.5",
                                mapOf("opencode" to "2.2.5"),
                                mapOf(ServerKind.T3 to "0.1.6-pw.5", ServerKind.OpenChamber to "2.5.3"),
                            ),
                        ),
                )
            val requested = mutableListOf<RuntimeOperation>()
            val selection =
                InMemoryServerSelectionStore(
                    ServerSelection(setOf(ServerKind.T3, ServerKind.OpenChamber), ServerKind.T3),
                )
            val controller = controller(installer, FakeServer(), requested = requested, serverSelection = selection)
            controller.initialize()
            step()

            controller.setServers(setOf(ServerKind.T3))
            installer.applyFailWith = UserFacingException(MessageKey.Generic, "staging failed")
            controller.perform(RuntimeOperation.ApplyServers)
            step()
            assertEquals("the draft is persisted", setOf(ServerKind.T3), selection.load().selected)
            assertEquals(
                "the installed set is still the old one",
                setOf(ServerKind.T3, ServerKind.OpenChamber),
                controller.servers.value.installed.keys,
            )

            requested.clear()
            installer.applyFailWith = null
            controller.setServers(setOf(ServerKind.T3))

            assertEquals(listOf(RuntimeOperation.ApplyServers), requested)
            controller.perform(RuntimeOperation.ApplyServers)
            step()
            assertEquals(setOf(ServerKind.T3), controller.servers.value.installed.keys)
        }

    @Test
    fun `the OpenCode dependency cannot be removed while OpenChamber is installed`() =
        controllerTest {
            val installer =
                FakeInstaller(
                    folder.root,
                    installed =
                        runtimeWith(
                            folder.root,
                            PackageVersions(
                                "0.1.6-pw.5",
                                mapOf("codex" to "0.166.0", "opencode" to "2.2.5"),
                                mapOf(ServerKind.T3 to "0.1.6-pw.5", ServerKind.OpenChamber to "2.5.3"),
                            ),
                        ),
                )
            val requested = mutableListOf<RuntimeOperation>()
            val controller =
                controller(
                    installer,
                    FakeServer(),
                    requested = requested,
                    selection = InMemoryAgentSelectionStore(setOf("codex")),
                    serverSelection =
                        InMemoryServerSelectionStore(
                            ServerSelection(setOf(ServerKind.T3, ServerKind.OpenChamber), ServerKind.T3),
                        ),
                )
            controller.initialize()
            step()
            assertEquals(setOf("opencode"), controller.agents.value.required)

            controller.setAgents(setOf("codex"))

            assertTrue("the dependency still satisfies the effective set", requested.isEmpty())
            assertEquals(setOf("codex"), controller.agents.value.selected)
            assertEquals(setOf("codex", "opencode"), controller.agents.value.installed.keys)
        }

    @Test
    fun `a Stop queued after Repair suppresses the repair auto-start`() =
        controllerTest {
            val installer = FakeInstaller(folder.root, installed = runtimeWith(folder.root))
            val requested = mutableListOf<RuntimeOperation>()
            val controller = controller(installer, FakeServer(), requested = requested)
            controller.initialize()
            step()

            controller.repair()
            controller.stop()
            assertEquals(listOf(RuntimeOperation.Repair, RuntimeOperation.Stop), requested)

            controller.perform(RuntimeOperation.Repair)
            step()

            assertEquals("the user-requested repair still installs", 1, installer.installCalls.size)
            assertFalse(controller.state.value is RuntimeState.Running)
            assertFalse(controller.state.value is RuntimeState.Starting)
            assertEquals(RuntimeState.Stopped, controller.state.value)
        }

    @Test
    fun `a Stop during Restart's stop stage suppresses the new start`() =
        controllerTest {
            val installer = FakeInstaller(folder.root, installed = runtimeWith(folder.root))
            val server = FakeServer()
            val controller = controller(installer, server)
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()
            assertTrue(controller.state.value is RuntimeState.Running)
            assertEquals(1, server.startAttempts)

            server.onStop = { controller.stop() }
            controller.restart()
            controller.perform(RuntimeOperation.Restart)
            step()

            assertEquals("the stopped server must not be started again", 1, server.startAttempts)
            assertFalse(controller.state.value is RuntimeState.Running)
            assertEquals(RuntimeState.Stopped, controller.state.value)
        }

    @Test
    fun `a Stop during an agent change suppresses the automatic restart`() =
        controllerTest {
            val installer = FakeInstaller(folder.root, installed = runtimeWith(folder.root))
            val controller = controller(installer, FakeServer())
            controller.initialize()
            step()
            controller.perform(RuntimeOperation.Start)
            step()
            assertTrue(controller.state.value is RuntimeState.Running)

            controller.setAgents(setOf("codex"))
            installer.onApply = { controller.stop() }
            controller.perform(RuntimeOperation.ApplyAgents)
            step()

            assertFalse("a maintenance operation must not restart after Stop", controller.state.value is RuntimeState.Running)
            assertFalse(controller.state.value is RuntimeState.Starting)
            assertEquals(RuntimeState.Stopped, controller.state.value)
        }
}
