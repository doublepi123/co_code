package io.cocode.app.runtime

import io.cocode.app.runtime.install.InstallReporter
import io.cocode.app.runtime.install.InstalledRuntime
import io.cocode.app.runtime.install.PackageVersionSource
import io.cocode.app.runtime.install.PackageVersions
import io.cocode.app.runtime.install.RuntimeInstaller
import io.cocode.app.runtime.install.RuntimeEnvironmentInstaller
import io.cocode.app.runtime.install.installedServerVersions
import io.cocode.app.runtime.manifest.AgentSpec
import io.cocode.app.runtime.process.HealthProbe
import io.cocode.app.runtime.process.RuntimeServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.ArrayDeque

/** Hook for the foreground service: told whenever the state changes. */
fun interface RuntimeStateListener {
    fun onStateChanged(state: RuntimeState)
}

/** The operations that must run inside the foreground service's lifetime. */
enum class RuntimeOperation { Install, Start, Repair, Stop, Uninstall, Restart, Update, ApplyAgents, ApplyServers, SelectServer }

/**
 * Gate between the UI and the controller. [request] must bring the foreground service up first and
 * only then run [RuntimeOperation] on the controller, so no network work starts in the background.
 */
fun interface ForegroundStarter {
    fun request(operation: RuntimeOperation)
}

/**
 * Owns the Linux runtime lifecycle: install, start, stop, repair, uninstall, server/agent selection,
 * server switching and crash recovery.
 *
 * Public actions queue behind [operationLock]; the watchdog's recovery takes the same lock. [busy]
 * is true from the moment an action is queued until it has finished, so the service can tell "work
 * in progress" from "resting" without guessing from intermediate states.
 *
 * Intent: [wantsRunning] records what the user asked for. Start, Install, Repair and ApplyAgents
 * (which restarts a previously running server) set it; Stop and Uninstall clear it. Only a set
 * intent allows automatic crash recovery.
 */
class DefaultLocalRuntimeController(
    private val paths: RuntimePaths,
    private val abi: String,
    private val installer: RuntimeInstaller,
    private val serverFor: (ServerKind) -> RuntimeServer,
    private val healthFor: (ServerKind) -> HealthProbe = { HealthProbe.Loopback },
    private val uiPassword: (RuntimePaths, ServerKind) -> String = RuntimeSecrets::uiPassword,
    /** The agents the manifest offers; production passes them, tests may use a subset. */
    private val manifestAgents: List<AgentSpec> = emptyList(),
    private val selectionStore: AgentSelectionStore = InMemoryAgentSelectionStore(),
    /** Persisted server choice; defaults to T3 for a fresh install. */
    private val serverSelectionStore: ServerSelectionStore = FileServerSelectionStore(paths.root),
    /** The manifest's T3 version, offered even when GitHub cannot be reached. */
    private val pinnedT3Version: String? = null,
    private val listener: RuntimeStateListener = RuntimeStateListener {},
    private val foregroundStarter: ForegroundStarter = ForegroundStarter {},
    /** Removes every owned runtime process (Stop, Repair, Uninstall run this before touching files). */
    private val killOwned: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val restartPolicy: RestartPolicy = RestartPolicy(),
    /** Interval between watchdog probes while Running. */
    private val watchdogPollMillis: Long = 5_000L,
    /**
     * The ABIs that run the on-device runtime. x86_64 is enabled because PRoot 5.1.107.98 rewrites the
     * guest's fork(2) (syscall 57, which Android's app seccomp policy rejects on x86_64) to clone(2) with
     * SIGCHLD, and it restores the child's registers (see docs/testing-x86-runtime.md for the probe gates).
     * Other ABIs are UnsupportedAbi (remote servers still work).
     */
    private val supportedAbis: Set<String> = setOf("arm64-v8a", "x86_64"),
    /** Registry lookup for newer versions. Null disables the registry check; bundled pins still apply. */
    private val versionSource: PackageVersionSource? = null,
    /** Minimum time between automatic registry checks. Manual checks ignore it. */
    private val updateCheckIntervalMillis: Long = UPDATE_CHECK_INTERVAL_MILLIS,
) : LocalRuntimeController {
    private val operationLock = Mutex()

    private val mutableState = MutableStateFlow<RuntimeState>(RuntimeState.Detecting)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    override val busy: StateFlow<Boolean> get() = work.busy

    private val mutableLog = MutableStateFlow<List<String>>(emptyList())
    override val logTail: StateFlow<List<String>> = mutableLog.asStateFlow()

    private val mutableInfo = MutableStateFlow<RuntimeInfo?>(null)
    override val info: StateFlow<RuntimeInfo?> = mutableInfo.asStateFlow()

    private val knownAgentIds = manifestAgents.map { it.id }.toSet()
    private val initialSelection = sanitizeSelection(selectionStore.selected())
    private val initialServerSelection = serverSelectionStore.load()
    private val initialServers = sanitizeServerSelection(initialServerSelection.selected)
    private val initialActive =
        initialServerSelection.active.takeIf { it in initialServers }
            ?: preferredActive(initialServers, emptySet())
    private val mutableAgents =
        MutableStateFlow(
            AgentsState(
                available = manifestAgents,
                selected = initialSelection,
                installed = emptyMap(),
                required = requiredAgentsForServers(initialServers, emptySet(), knownAgentIds),
            ),
        )
    override val agents: StateFlow<AgentsState> = mutableAgents.asStateFlow()

    private val mutableServers =
        MutableStateFlow(
            ServersState(
                selected = initialServers,
                installed = emptyMap(),
                active = initialActive,
            ),
        )
    override val servers: StateFlow<ServersState> = mutableServers.asStateFlow()

    private fun persistServers(selection: ServerSelection) {
        runCatching { serverSelectionStore.save(selection) }
    }

    private val mutableUpdates = MutableStateFlow(RuntimeUpdateState())
    override val updates: StateFlow<RuntimeUpdateState> = mutableUpdates.asStateFlow()

    /** Operations and recovery jobs queued or running; count and [busy] change together (see WorkCounter). */
    private val work = WorkCounter()

    init {
        if (selectionStore.selected() != initialSelection) selectionStore.save(initialSelection)
    }

    private fun beginWork() = work.begin()

    private fun endWork() = work.end()

    // Guarded by operationLock, except cancelRequested which Stop sets without the lock.
    @Volatile private var cancelRequested = false

    /**
     * Explicit-intent ordering. A run token is created when the user asks for install/start/repair/
     * restart/update/apply/server selection; a Stop token is created when Stop is requested. A
     * maintenance operation may auto-start only while its token is newer than the last Stop token.
     * This cannot be swallowed by an operation starting later (queued Repair then Stop, Stop during
     * Restart), and still lets a later explicit Start win.
     */
    private data class Intent(
        val operation: RuntimeOperation,
        val token: Long,
    )

    private val intentLock = Any()
    private val pendingIntents = ArrayDeque<Intent>()
    private var intentSequence = 0L
    private var lastStopToken = 0L
    private var watchdog: Job? = null
    private var restartJob: Job? = null
    private var wantsRunning = false
    private var currentPhase: FailurePhase = FailurePhase.Runtime
    /** Last selectServer request, consumed by the queued SelectServer operation. */
    @Volatile private var requestedServer: ServerKind? = null

    /**
     * Installed versions the update check compares against. Written under operationLock; the registry
     * check reads it without the lock, so it is volatile.
     */
    @Volatile private var installedVersions: PackageVersions? = null

    /** Newest versions from the last successful check. Written by the check job only. */
    @Volatile private var latestKnown: PackageVersions? = null

    /** Registry check in flight. Not under operationLock: the network must not block the runtime. */
    @Volatile private var checkJob: Job? = null

    /** Decides the starting state from disk, after recovering any interrupted swap. */
    fun initialize() {
        launch(FailurePhase.Runtime) { detectLocked() }
    }

    // --- Public actions. Each one first asks the foreground service to start. ---

    private fun requestRun(operation: RuntimeOperation) {
        val token =
            synchronized(intentLock) {
                ++intentSequence
            }
        synchronized(intentLock) { pendingIntents.addLast(Intent(operation, token)) }
        foregroundStarter.request(operation)
    }

    private fun requestStop() {
        val token =
            synchronized(intentLock) {
                val next = ++intentSequence
                lastStopToken = next
                next
            }
        synchronized(intentLock) { pendingIntents.addLast(Intent(RuntimeOperation.Stop, token)) }
        foregroundStarter.request(RuntimeOperation.Stop)
    }

    /** The token of this request, or a fresh one for a direct/notification invocation. */
    private fun takeIntentToken(operation: RuntimeOperation): Long =
        synchronized(intentLock) {
            val iterator = pendingIntents.iterator()
            while (iterator.hasNext()) {
                val intent = iterator.next()
                if (intent.operation == operation) {
                    iterator.remove()
                    return@synchronized intent.token
                }
            }
            val next = ++intentSequence
            if (operation == RuntimeOperation.Stop) lastStopToken = next
            next
        }

    private fun canAutoStart(token: Long): Boolean = synchronized(intentLock) { token > lastStopToken }

    override fun install() = requestRun(RuntimeOperation.Install)

    override fun start() = requestRun(RuntimeOperation.Start)

    override fun stop() {
        // Stop is not queued behind a start: flag it, then request the service op so it can cancel.
        cancelRequested = true
        wantsRunning = false
        requestedServer = null
        requestStop()
    }

    override fun repair() = requestRun(RuntimeOperation.Repair)

    override fun uninstall() = requestRun(RuntimeOperation.Uninstall)

    override fun restart() = requestRun(RuntimeOperation.Restart)

    override fun setAgents(selected: Set<String>) {
        // An empty or unknown-only set is a draft the UI may legitimately hold (for example while
        // its last checkbox is off). Keep the previous valid selection instead of throwing: this
        // method is called straight from a click handler, where an exception kills the app.
        val sanitized = knownSelectionOrNull(selected) ?: return
        val persisted = mutableAgents.value.selected
        val effective = sanitized + mutableAgents.value.required
        // The persisted selection can already be ahead of what is installed after a failed apply.
        // Retrying the same draft must apply again, so the no-op also requires the installed set to
        // match; comparing only against `persisted` would swallow that retry.
        val installed =
            installedVersions?.agents?.keys
                ?: mutableAgents.value.installed.keys.takeIf { it.isNotEmpty() }
        val needsApply = installed != null && effective != installed
        if (sanitized == persisted && !needsApply) return
        if (sanitized != persisted) {
            selectionStore.save(sanitized)
            mutableAgents.update { it.copy(selected = sanitized) }
        }
        // Before an install the selection is simply used by Set up; with an installed environment the
        // change is applied through a staging copy by the service.
        if (needsApply) {
            requestRun(RuntimeOperation.ApplyAgents)
        }
    }

    override fun setServers(selected: Set<ServerKind>) {
        if (selected.isEmpty()) return
        val persisted = mutableServers.value.selected
        val installed = mutableServers.value.installed.keys
        val needsApply = installed.isNotEmpty() && selected != installed
        if (selected == persisted && !needsApply) return
        if (selected != persisted) {
            val nextActive =
                if (installed.isEmpty()) {
                    preferredActive(selected, emptySet())
                } else {
                    // Keep the running/active kind until ApplyServers has stopped it; never point the
                    // UI at a new local target while the old process may still be trusted.
                    mutableServers.value.active
                }
            mutableServers.update { it.copy(selected = selected, active = nextActive) }
            persistServers(ServerSelection(selected, nextActive))
            updateRequiredAgents()
        }
        if (needsApply) {
            requestRun(RuntimeOperation.ApplyServers)
        }
    }

    override fun selectServer(kind: ServerKind) {
        val current = mutableServers.value
        if (kind !in current.installed) return
        if (kind == current.active && (mutableState.value is RuntimeState.Running || mutableState.value is RuntimeState.Starting)) return
        if (requestedServer == kind) return
        requestedServer = kind
        requestRun(RuntimeOperation.SelectServer)
    }

    /**
     * Checks GitHub and npm now. Runs outside operationLock, so it can finish while an operation is
     * queued. Ignored while a check is already running, and when the runtime is not installed.
     */
    override fun checkForUpdates() {
        if (versionSource == null || installedVersions == null) return
        if (checkJob?.isActive == true) return
        checkJob = scope.launch { runUpdateCheck() }
    }

    override fun update() = requestRun(RuntimeOperation.Update)

    override fun dismissUpdateFailure() {
        mutableUpdates.update { it.copy(lastFailure = null) }
    }

    /**
     * Runs one [RuntimeOperation] on the controller. Called by the foreground service after it has
     * started in the foreground; never by the UI directly.
     */
    override fun perform(operation: RuntimeOperation) {
        val token = takeIntentToken(operation)
        when (operation) {
            RuntimeOperation.Install -> launch(FailurePhase.Install, token) { installLocked(thenStart = true, token = it) }
            RuntimeOperation.Update -> launch(FailurePhase.Runtime, token) { updateLocked(it) }
            RuntimeOperation.ApplyAgents -> launch(FailurePhase.Runtime, token) { applyAgentsLocked(it) }
            RuntimeOperation.ApplyServers -> launch(FailurePhase.Runtime, token) { applyServersLocked(it) }
            RuntimeOperation.SelectServer -> launch(FailurePhase.Start, token) { selectServerLocked(it) }
            RuntimeOperation.Start -> {
                cancelRequested = false
                launch(FailurePhase.Start, token) { startLocked(manual = true, token = it) }
            }
            RuntimeOperation.Stop -> {
                // Cancel a pending backoff/restart BEFORE queueing behind the lock: the restart holds
                // the lock while it waits, so waiting for the lock would let it run to completion.
                cancelRequested = true
                requestedServer = null
                wantsRunning = false
                restartJob?.cancel()
                launch(FailurePhase.Runtime, token) { stopLocked() }
            }
            RuntimeOperation.Repair ->
                launch(FailurePhase.Runtime, token) {
                    // Repair never deletes the live environment first: stop what runs, then install
                    // into staging and swap atomically. A failed repair leaves the old runtime intact.
                    stopLocked()
                    currentPhase = FailurePhase.Install
                    installLocked(thenStart = true, token = it)
                }
            RuntimeOperation.Restart -> {
                // One queued op, so stop and start can't be reordered by the lock (two separate
                // launches race for it). Used to apply new storage bindings.
                wantsRunning = false
                restartJob?.cancel()
                cancelRequested = false
                launch(FailurePhase.Start, token) {
                    stopLocked()
                    cancelRequested = false
                    startLocked(manual = true, token = it)
                }
            }
            RuntimeOperation.Uninstall ->
                launch(FailurePhase.Runtime) {
                    stopLocked()
                    withContext(ioDispatcher) { installer.removeAll() }
                    mutableInfo.value = null
                    mutableAgents.update { it.copy(installed = emptyMap()) }
                    mutableServers.update {
                        it.copy(installed = emptyMap(), active = it.selected.firstOrNull() ?: ServerKind.T3)
                    }
                    updateRequiredAgents()
                    installedVersions = null
                    latestKnown = null
                    mutableUpdates.value = RuntimeUpdateState()
                    publish(RuntimeState.NotInstalled)
                }
        }
    }

    /** Suspends until queued operations have drained. Used by tests. */
    suspend fun awaitIdle() {
        operationLock.withLock { }
    }

    private fun launch(
        phase: FailurePhase,
        block: suspend () -> Unit,
    ) = launch(phase, 0L) { block() }

    private fun launch(
        phase: FailurePhase,
        token: Long,
        block: suspend (Long) -> Unit,
    ) {
        beginWork()
        // invokeOnCompletion, not finally: a job cancelled before it first runs never enters its body,
        // but its completion handler always fires, so the counter cannot stay high.
        scope.launch {
            operationLock.withLock {
                currentPhase = phase
                try {
                    block(token)
                } catch (error: CancellationException) {
                    publishTerminalAfterCancel()
                    throw error
                } catch (error: Throwable) {
                    // currentPhase, not the launch argument: install hands over to Start mid-operation.
                    failed(error.message ?: "Runtime operation failed", currentPhase, error)
                }
            }
        }.invokeOnCompletion { endWork() }
    }

    // --- All helpers below run with operationLock held. ---

    private suspend fun detectLocked() {
        if (abi !in supportedAbis) {
            publish(RuntimeState.UnsupportedAbi)
            return
        }
        // Finish or roll back an interrupted environment swap before reading what is installed.
        withContext(ioDispatcher) { installer.recover() }
        val existing = withContext(ioDispatcher) { installer.installedRuntime() }
        if (existing == null) {
            mutableServers.update {
                it.copy(installed = emptyMap(), active = it.selected.firstOrNull() ?: ServerKind.T3)
            }
            updateRequiredAgents()
            publish(RuntimeState.NotInstalled)
        } else {
            adopt(existing)
            publish(RuntimeState.Stopped)
            // The bundled pins may already be newer than what is installed: offer that without network.
            recomputeAvailable(existing)
            maybeCheckForUpdates()
        }
    }

    /** Install, then optionally start (Install and Repair both end running, as the user asked for it). */
    private suspend fun installLocked(thenStart: Boolean, token: Long) {
        if (abi !in supportedAbis) {
            publish(RuntimeState.UnsupportedAbi)
            return
        }
        cancelRequested = false
        val requestedAgents = mutableAgents.value.selected
        val selectedServers = sanitizeServerSelection(mutableServers.value.selected)
        val effectiveAgents =
            requestedAgents + requiredAgentsForServers(selectedServers, emptySet(), knownAgentIds)
        publish(RuntimeState.Installing(InstallStep.DownloadRootfs, 0f, null))
        val installed = installer.install(installReporter(updating = false), effectiveAgents, selectedServers)
        adopt(installed)
        recomputeAvailable(installed)
        maybeCheckForUpdates()
        // Intermediate Stopped: the service keeps running because busy is still true here.
        publish(RuntimeState.Stopped)
        if (!thenStart || !canAutoStart(token)) return
        currentPhase = FailurePhase.Start
        startLocked(manual = true, token = token)
    }

    private fun installReporter(updating: Boolean): InstallReporter =
        object : InstallReporter {
            override fun step(
                step: InstallStep,
                progress: Float?,
                detail: String?,
                messageKey: MessageKey?,
            ) {
                publish(RuntimeState.Installing(step, progress, detail, messageKey, updating = updating))
            }

            override fun log(line: String) = appendLog(line)
        }

    /**
     * Updates T3 Code and the selected agents to the available targets. The server is stopped first.
     * A failure keeps the previous environment (which the installer never touches before the swap):
     * it sets [RuntimeUpdateState.lastFailure] and does not publish [RuntimeState.Failed]. Afterwards
     * the server is restarted when it was running, otherwise the state rests at Stopped.
     */
    private suspend fun updateLocked(token: Long) {
        val target = mutableUpdates.value.available
        if (target == null || !target.hasChanges) return
        val runtime = withContext(ioDispatcher) { installer.installedRuntime() } ?: return
        val wasRunning = mutableState.value.let { it is RuntimeState.Running || it is RuntimeState.Starting } || wantsRunning

        val selectedAgents = mutableAgents.value.selected
        val effectiveAgents = selectedAgents + mutableAgents.value.required
        val agentTargets =
            effectiveAgents.associateWith { id ->
                target.agents.firstOrNull { it.id == id }?.change?.target
                    ?: runtime.metadata.agents[id]
                    ?: ""
            }
        val installedServers = installedServerVersions(runtime.rootfs, runtime.metadata).keys
        val serverTargets =
            installedServers.associateWith { kind ->
                when (kind) {
                    ServerKind.T3 -> target.t3.target
                    ServerKind.OpenChamber -> target.servers[kind]?.target ?: runtime.metadata.servers[kind.id] ?: ""
                }
            }
        val versions =
            PackageVersions(
                t3 = target.t3.target,
                agents = agentTargets,
                servers = serverTargets.filterKeys { it != ServerKind.T3 },
            )

        cancelRequested = false
        stopLocked()
        mutableUpdates.update { it.copy(lastFailure = null) }
        try {
            publish(RuntimeState.Installing(InstallStep.CopyEnvironment, null, null, MessageKey.ProgressCopyingEnvironment, updating = true))
            val updated = installer.update(installReporter(updating = true), versions)
            adopt(updated)
            recomputeAvailable(updated)
            mutableUpdates.update { it.copy(lastFailure = null) }
        } catch (error: CancellationException) {
            // Like install: leave the transient Installing state. The old environment is intact.
            publish(RuntimeState.Stopped)
            throw error
        } catch (error: Throwable) {
            // Not Failed: the previous environment is still installed and usable. The reason is shown
            // on the update card instead, so a failed update never blocks the runtime.
            recordFailure(error, "update")
            // The failed attempt left the old environment in place: show its versions again.
            adopt(runtime)
        }
        recomputeAvailable(withContext(ioDispatcher) { installer.installedRuntime() } ?: runtime)
        if (!wasRunning || !canAutoStart(token)) {
            publish(RuntimeState.Stopped)
            return
        }
        currentPhase = FailurePhase.Start
        startLocked(manual = true, token = token)
    }

    /**
     * Applies the saved agent selection to the installed environment. The server is stopped first;
     * the installer removes no-longer-selected packages, installs new ones, verifies and rewrites the
     * metadata. A failure keeps the previous metadata and records [RuntimeUpdateState.lastFailure].
     */
    private suspend fun applyAgentsLocked(token: Long) {
        val runtime = withContext(ioDispatcher) { installer.installedRuntime() } ?: return
        val selected = mutableAgents.value.selected
        if (selected.isEmpty()) {
            recordFailure(UserFacingException(MessageKey.AgentsRequired, "Select at least one agent"), "agent change")
            return
        }
        val effective = selected + mutableAgents.value.required
        val wasRunning = mutableState.value.let { it is RuntimeState.Running || it is RuntimeState.Starting } || wantsRunning
        cancelRequested = false
        stopLocked()
        mutableUpdates.update { it.copy(lastFailure = null) }
        try {
            publish(RuntimeState.Installing(InstallStep.CopyEnvironment, null, null, MessageKey.ProgressCopyingEnvironment, updating = true))
            val updated = installer.applyAgents(installReporter(updating = true), effective)
            adopt(updated)
            recomputeAvailable(updated)
        } catch (error: CancellationException) {
            publish(RuntimeState.Stopped)
            throw error
        } catch (error: Throwable) {
            recordFailure(error, "agent change")
            adopt(runtime)
        }
        if (!wasRunning || !canAutoStart(token)) {
            publish(RuntimeState.Stopped)
            return
        }
        currentPhase = FailurePhase.Start
        startLocked(manual = true, token = token)
    }

    /** Applies the selected server set through staging, then restarts whichever server was active. */
    private suspend fun applyServersLocked(token: Long) {
        val runtime = withContext(ioDispatcher) { installer.installedRuntime() } ?: return
        val selectedServers = sanitizeServerSelection(mutableServers.value.selected)
        val effectiveAgents =
            mutableAgents.value.selected +
                requiredAgentsForServers(selectedServers, emptySet(), knownAgentIds)
        val wasRunning = mutableState.value.let { it is RuntimeState.Running || it is RuntimeState.Starting } || wantsRunning
        cancelRequested = false
        stopLocked()
        mutableUpdates.update { it.copy(lastFailure = null) }
        try {
            publish(RuntimeState.Installing(InstallStep.CopyEnvironment, null, null, MessageKey.ProgressCopyingEnvironment, updating = true))
            val updated = installer.applyServers(installReporter(updating = true), selectedServers, effectiveAgents)
            adopt(updated)
            recomputeAvailable(updated)
        } catch (error: CancellationException) {
            publish(RuntimeState.Stopped)
            throw error
        } catch (error: Throwable) {
            recordFailure(error, "server change")
            adopt(runtime)
        }
        if (!wasRunning || !canAutoStart(token)) {
            publish(RuntimeState.Stopped)
            return
        }
        currentPhase = FailurePhase.Start
        startLocked(manual = true, token = token)
    }

    /** Stops the former server, commits the new active kind, then starts it. */
    private suspend fun selectServerLocked(token: Long) {
        val target = requestedServer ?: return
        requestedServer = null
        val current = mutableServers.value
        if (target !in current.installed) return
        stopLocked()
        mutableServers.update { it.copy(active = target) }
        persistServers(ServerSelection(mutableServers.value.selected, target))
        updateRequiredAgents()
        if (!canAutoStart(token)) {
            publish(RuntimeState.Stopped)
            return
        }
        currentPhase = FailurePhase.Start
        startLocked(manual = true, token = token)
    }

    private fun recordFailure(
        error: Throwable,
        what: String,
    ) {
        val typed = error as? UserFacingException
        appendLog("ERROR: $what failed: ${error.message}")
        mutableUpdates.update {
            it.copy(
                lastFailure =
                    UpdateFailure(
                        reason = error.message ?: "$what failed",
                        messageKey = typed?.key ?: MessageKey.Generic,
                        detail = typed?.detail,
                    ),
            )
        }
    }

    /**
     * Recomputes [RuntimeUpdateState.available] from the installed versions and the newest known
     * versions: the registry's (when fetched) and the bundled pins. Never offers a downgrade.
     */
    private fun recomputeAvailable(runtime: InstalledRuntime) {
        val installedServers = installedServerVersions(runtime.rootfs, runtime.metadata)
        val installed =
            PackageVersions(
                t3 = installedServers[ServerKind.T3] ?: runtime.metadata.t3Version,
                agents = runtime.metadata.agents,
                servers = installedServers,
            )
        installedVersions = installed
        mutableUpdates.update { it.copy(available = availableFor(installed, latestKnown)) }
    }

    private fun availableFor(
        installed: PackageVersions,
        registry: PackageVersions?,
    ): RuntimeUpdate? {
        val t3Current = installed.servers[ServerKind.T3] ?: installed.t3
        val t3Installed = ServerKind.T3 in installed.servers
        val targetT3 =
            if (t3Installed) {
                newestOf(registry?.servers?.get(ServerKind.T3), registry?.t3, pinnedT3Version, t3Current)
                    ?: t3Current
            } else {
                t3Current
            }
        val t3 = VersionChange(current = t3Current, target = targetT3)
        val effectiveAgents = mutableAgents.value.selected + mutableAgents.value.required
        val agents =
            effectiveAgents.mapNotNull { id ->
                val current = installed.agents[id] ?: return@mapNotNull null
                val target = newestOf(registry?.agents?.get(id), current) ?: return@mapNotNull null
                AgentChange(id = id, displayName = displayNameOf(id), change = VersionChange(current, target))
            }
        val servers =
            installed.servers
                .filterKeys { it != ServerKind.T3 }
                .mapValues { (kind, current) ->
                    VersionChange(current, newestOf(registry?.servers?.get(kind), current) ?: current)
                }
        val update = RuntimeUpdate(t3 = t3, agents = agents, servers = servers)
        return update.takeIf { it.hasChanges }
    }

    private fun newestOf(vararg versions: String?): String? =
        versions.filterNotNull().maxWithOrNull { a, b -> RuntimeEnvironmentInstaller.compareVersions(a, b) }

    /** An automatic check, throttled by [updateCheckIntervalMillis] since the last one. */
    private fun maybeCheckForUpdates() {
        if (versionSource == null) return
        val last = mutableUpdates.value.lastCheckedAtMs
        if (last != null && nowMillis() - last < updateCheckIntervalMillis) return
        if (checkJob?.isActive == true) return
        checkJob = scope.launch { runUpdateCheck() }
    }

    /**
     * Asks GitHub and npm for newer versions, with no operationLock: the network must not hold up the
     * runtime. Cancellation propagates. A failure keeps the currently known versions and sets
     * [RuntimeUpdateState.checkFailed].
     */
    private suspend fun runUpdateCheck() {
        val source = versionSource ?: return
        val installed = installedVersions ?: return
        val effectiveAgents = mutableAgents.value.selected + mutableAgents.value.required
        val pinned =
            PackageVersions(
                t3 = pinnedT3Version ?: installed.t3,
                agents = installed.agents.filterKeys { it in effectiveAgents },
                servers = installed.servers,
            )
        mutableUpdates.update { it.copy(checking = true) }
        try {
            val latest: PackageVersions? =
                try {
                    source.latest(pinned)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    appendLog("Update check failed: ${error.message}")
                    null
                }
            latestKnown = latest ?: latestKnown
            mutableUpdates.update {
                it.copy(
                    checking = false,
                    checkFailed = latest == null,
                    lastCheckedAtMs = nowMillis(),
                    // Re-read: an update may have finished while the request was in flight.
                    available = availableFor(installedVersions ?: installed, latestKnown),
                )
            }
        } finally {
            mutableUpdates.update { if (it.checking) it.copy(checking = false) else it }
        }
    }

    private suspend fun startLocked(manual: Boolean, token: Long = 0L) {
        val runtime = withContext(ioDispatcher) { installer.installedRuntime() }
        if (runtime == null) {
            publish(RuntimeState.NotInstalled)
            return
        }
        val active = mutableServers.value.active
        if (active !in mutableServers.value.installed) {
            publish(RuntimeState.NotInstalled)
            return
        }
        val server = serverFor(active)
        val health = healthFor(active)
        if (manual) {
            if (!canAutoStart(token)) {
                publish(RuntimeState.Stopped)
                return
            }
            wantsRunning = true
            restartPolicy.reset()
        }
        // A second Start queued behind the first (UI launch and a notification or test racing through
        // the service) must not tear down the server that just came up: start() stops the old child.
        if (mutableState.value is RuntimeState.Running && runCatching { server.isAlive() }.getOrDefault(false)) {
            appendLog("Start ignored: ${active.displayName} is already running")
            return
        }
        val port = active.port
        publish(RuntimeState.Starting("Starting ${active.displayName} on port $port"))
        val password = withContext(ioDispatcher) { uiPassword(paths, active) }
        withContext(ioDispatcher) {
            server.start(runtime, password, ::appendLog) { cancelRequested }
        }
        if (cancelRequested) throw CancellationException("Start cancelled by Stop")
        publish(RuntimeState.Running("http://127.0.0.1:$port", port, nowMillis()))
        adopt(runtime)
        scheduleWatchdog()
    }

    private suspend fun stopLocked() {
        cancelRequested = true
        wantsRunning = false
        // Cancel a pending backoff/restart first: it holds no lock while waiting, so this is immediate.
        restartJob?.cancel()
        restartJob = null
        watchdog?.cancel()
        watchdog = null
        val current = mutableState.value
        if (current !is RuntimeState.Stopped && current !is RuntimeState.NotInstalled) {
            publish(RuntimeState.Stopping("Stopping ${mutableServers.value.active.displayName}"))
        }
        // Always run: never trust the UI's view of what is alive.
        withContext(ioDispatcher) {
            runCatching { serverFor(mutableServers.value.active).stop() }
                .onFailure { appendLog("Server stop failed: ${it.message}") }
            killOwned()
        }
        cancelRequested = false
        val installed = withContext(ioDispatcher) { installer.installedRuntime() }
        publish(if (installed != null) RuntimeState.Stopped else RuntimeState.NotInstalled)
    }

    /** A cancelled start lands in Stopped (or NotInstalled), never in Starting. */
    private fun publishTerminalAfterCancel() {
        if (mutableState.value is RuntimeState.Starting || mutableState.value is RuntimeState.Stopping) {
            publish(RuntimeState.Stopped)
        }
    }

    private fun failed(
        reason: String,
        phase: FailurePhase,
        error: Throwable?,
    ) {
        appendLog("ERROR: $reason")
        if (error != null) appendLog(error.stackTraceToString().lineSequence().take(3).joinToString("\n"))
        wantsRunning = false
        // A typed failure carries its own localised headline; anything else is generic.
        val typed = error as? UserFacingException
        publish(
            RuntimeState.Failed(
                reason = reason,
                recoverable = true,
                phase = phase,
                messageKey = typed?.key ?: MessageKey.Generic,
                detail = typed?.detail,
            ),
        )
    }

    /**
     * Probes while Running. A dead or unhealthy server starts a recovery job that owns the restart
     * budget. Probe errors are logged and count as unhealthy; they never escape the scope.
     */
    private fun scheduleWatchdog() {
        watchdog?.cancel()
        watchdog =
            scope.launch {
                var missedProbes = 0
                while (true) {
                    delay(watchdogPollMillis)
                    val running = mutableState.value as? RuntimeState.Running ?: return@launch
                    val active = mutableServers.value.active
                    val server = serverFor(active)
                    val health = healthFor(active)
                    // A dead child is certain; a slow environment read is not. Opening the web UI fires a
                    // burst of requests and a phone can take seconds to answer, so only a dead process or
                    // several consecutive missed probes count as a crash (seen on device).
                    val alive = runCatching { server.isAlive() }.getOrDefault(false)
                    val answered =
                        alive &&
                            runCatching { withContext(ioDispatcher) { health.read(running.port)?.ready == true } }
                                .getOrElse { error ->
                                    if (error is CancellationException) throw error
                                    appendLog("Watchdog probe failed: ${error.message}")
                                    false
                                }
                    if (answered) {
                        missedProbes = 0
                        restartPolicy.onHealthy(nowMillis())
                        continue
                    }
                    if (alive && ++missedProbes < WATCHDOG_MISSES_BEFORE_RESTART) {
                        appendLog("Server did not answer a health check ($missedProbes/$WATCHDOG_MISSES_BEFORE_RESTART)")
                        continue
                    }
                    startRecovery()
                    return@launch
                }
            }
    }

    /** One recovery job for one crash. Counted in [busy] so the service stays up while it runs. */
    private fun startRecovery() {
        beginWork()
        restartJob =
            scope.launch {
                try {
                    operationLock.withLock {
                        if (!wantsRunning || mutableState.value !is RuntimeState.Running) return@withLock
                        restartAfterCrash()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    failed(error.message ?: "Recovery failed", FailurePhase.Runtime, error)
                }
            }.also { job ->
                // Fires even when the job is cancelled before it starts; busy never consults isActive.
                job.invokeOnCompletion { endWork() }
            }
    }

    /**
     * Automatic recovery. Each attempt consumes one budget step: a failed attempt goes around again
     * (after its own backoff) until the budget runs out, and only then is the state Failed(Runtime).
     * A successful attempt returns to the watchdog, which refills the budget after a healthy window.
     */
    private suspend fun restartAfterCrash() {
        publish(RuntimeState.Failed("${mutableServers.value.active.displayName} stopped unexpectedly", recoverable = true, phase = FailurePhase.Runtime))
        while (true) {
            val delayMillis = restartPolicy.nextDelayMillis()
            if (delayMillis == null) {
                wantsRunning = false
                // The budget is spent: the child may still be alive (health misses, not a death), so
                // clean it up before the terminal Failed lets the service finish. Cancellation rethrows
                // and an error is logged; either way the terminal state is still published.
                stopAfterExhaustion()
                publish(
                    RuntimeState.Failed(
                        "${mutableServers.value.active.displayName} kept stopping; tap Start to try again",
                        recoverable = true,
                        phase = FailurePhase.Runtime,
                    ),
                )
                return
            }
            publish(RuntimeState.Starting("${mutableServers.value.active.displayName} stopped; restarting in ${delayMillis / 1000}s"))
            appendLog("Restarting ${mutableServers.value.active.displayName} in ${delayMillis / 1000}s")
            delay(delayMillis)
            if (!wantsRunning) return
            try {
                withContext(ioDispatcher) { serverFor(mutableServers.value.active).stop() }
                startLocked(manual = false)
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // This attempt failed: log it and loop, so the remaining budget is actually used.
                appendLog("Restart attempt failed: ${error.message}")
                publish(RuntimeState.Failed("Restart failed: ${error.message}", recoverable = true, phase = FailurePhase.Start))
            }
        }
    }

    /**
     * Stops the server and every owned process before the terminal failure is published. Both steps
     * run in [NonCancellable] and are independent: a throw from [server].stop must not skip [killOwned].
     * Each error is logged; the terminal state is published by the caller either way. A cancellation
     * that arrives during cleanup is still honoured after both steps have been attempted.
     */
    private suspend fun stopAfterExhaustion() {
        var cancelled: CancellationException? = null
        withContext(NonCancellable) {
            withContext(ioDispatcher) {
                try {
                    serverFor(mutableServers.value.active).stop()
                } catch (error: CancellationException) {
                    cancelled = error
                } catch (error: Throwable) {
                    appendLog("Server stop after restart budget failed: ${error.message}")
                }
                try {
                    killOwned()
                } catch (error: CancellationException) {
                    cancelled = cancelled ?: error
                } catch (error: Throwable) {
                    appendLog("Owned process cleanup after restart budget failed: ${error.message}")
                }
            }
        }
        cancelled?.let { throw it }
    }

    private fun publish(next: RuntimeState) {
        mutableState.value = next
        listener.onStateChanged(next)
    }

    private fun appendLog(line: String) {
        mutableLog.value = (mutableLog.value + line).takeLast(MAX_LOG_LINES)
    }

    /** Adopts what is installed: servers, info, the agent versions and the available update. */
    private fun adopt(runtime: InstalledRuntime) {
        val installedServers = installedServerVersions(runtime.rootfs, runtime.metadata)
        val stored = serverSelectionStore.storedSelection()
        val requestedServers = stored?.selected ?: mutableServers.value.selected
        val selectedServers =
            if (stored == null) {
                // First run with no saved choice: adopt what is actually installed instead of
                // pretending the T3 default is selected while an OC-only root exists.
                if (installedServers.isNotEmpty()) installedServers.keys else requestedServers
            } else {
                sanitizeServerSelection(requestedServers, installedServers.keys)
            }
        val active =
            mutableServers.value.active
                .takeIf { it in installedServers }
                ?: preferredActive(selectedServers, installedServers.keys)
        mutableServers.value =
            ServersState(
                selected = selectedServers,
                installed = installedServers,
                active = active,
            )
        persistServers(ServerSelection(selectedServers, active))
        updateRequiredAgents()
        refreshInfo(runtime, installedServers)
        mutableAgents.update { it.copy(installed = runtime.metadata.agents) }
    }

    private fun updateRequiredAgents() {
        val required =
            requiredAgentsForServers(
                selected = mutableServers.value.selected,
                installed = mutableServers.value.installed.keys,
                knownAgents = knownAgentIds,
            )
        mutableAgents.update { it.copy(required = required) }
    }

    private fun refreshInfo(
        runtime: InstalledRuntime,
        installedServers: Map<ServerKind, String>,
    ) {
        mutableInfo.value =
            RuntimeInfo(
                abi = abi,
                alpineVersion = runtime.metadata.alpineVersion,
                t3Version = installedServers[ServerKind.T3],
                serverVersions = installedServers,
                agents = runtime.metadata.agents,
                nodeVersion = runtime.metadata.nodeVersion,
                diskUsageBytes = directorySize(paths.root),
                freeSpaceBytes = paths.root.usableSpace,
            )
    }

    private fun displayNameOf(id: String): String =
        manifestAgents.firstOrNull { it.id == id }?.displayName ?: id

    /**
     * The server that should be active when there is no valid stored choice: T3 when available,
     * otherwise the first selected installed kind, then any installed kind, then T3.
     */
    private fun preferredActive(
        selected: Set<ServerKind>,
        installed: Set<ServerKind>,
    ): ServerKind {
        if (ServerKind.T3 in selected && (installed.isEmpty() || ServerKind.T3 in installed)) return ServerKind.T3
        return selected.firstOrNull { installed.isEmpty() || it in installed }
            ?: installed.firstOrNull()
            ?: ServerKind.T3
    }

    /** Keeps only known agents and never returns an empty set when the manifest offers anything. */
    private fun sanitizeSelection(selected: Set<String>): Set<String> {
        if (knownAgentIds.isEmpty()) return selected.ifEmpty { DEFAULT_AGENTS }
        val known = selected.filter { it in knownAgentIds }.toSet()
        if (known.isNotEmpty()) return known
        val defaults = DEFAULT_AGENTS.filter { it in knownAgentIds }.toSet()
        return defaults.ifEmpty { setOf(manifestAgents.first().id) }
    }

    /**
     * An explicit selection from the UI, filtered to known agents. Null means "nothing usable was
     * chosen" (empty, or only ids this build does not offer), which must keep the saved selection
     * rather than fall back to the defaults: silently switching agents on a stray click would be
     * worse than ignoring it.
     */
    private fun knownSelectionOrNull(selected: Set<String>): Set<String>? {
        if (selected.isEmpty()) return null
        if (knownAgentIds.isEmpty()) return selected
        return selected.filter { it in knownAgentIds }.toSet().takeIf { it.isNotEmpty() }
    }

    /** Symlink-safe: guest links may point anywhere, so never follow them while sizing. */
    private fun directorySize(file: File): Long = runCatching { io.cocode.app.runtime.fs.treeSize(file) }.getOrDefault(0L)

    private companion object {
        const val MAX_LOG_LINES = 400
        const val WATCHDOG_MISSES_BEFORE_RESTART = 3

        /** Automatic registry checks run at most this often (manual checks ignore it). */
        const val UPDATE_CHECK_INTERVAL_MILLIS = 6L * 60 * 60 * 1000
    }
}
