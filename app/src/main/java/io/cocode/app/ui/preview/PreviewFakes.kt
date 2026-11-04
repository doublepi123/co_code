package io.cocode.app.ui.preview

import io.cocode.app.runtime.AgentsState
import io.cocode.app.runtime.InstallStep
import io.cocode.app.runtime.LocalRuntimeController
import io.cocode.app.runtime.RemoteServerStore
import io.cocode.app.runtime.RuntimeInfo
import io.cocode.app.runtime.RuntimeOperation
import io.cocode.app.runtime.RuntimeState
import io.cocode.app.runtime.RuntimeUpdateState
import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.ServerTarget
import io.cocode.app.runtime.ServersState
import io.cocode.app.runtime.SharedStorageController
import io.cocode.app.runtime.SharedStorageState
import io.cocode.app.runtime.SharedStorageStatus
import io.cocode.app.runtime.WebSessionAuthenticator
import io.cocode.app.runtime.WebSessionCookies
import io.cocode.app.runtime.WebSessionResult
import io.cocode.app.runtime.withServerMoved
import io.cocode.app.runtime.withServerUpserted
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory runtime for previews. Actions jump straight to the resulting state. */
internal class FakeRuntimeController(
    initial: RuntimeState = RuntimeState.Stopped,
    info: RuntimeInfo? = PreviewData.info,
    log: List<String> = PreviewData.log,
    updates: RuntimeUpdateState = RuntimeUpdateState(),
    servers: ServersState = PreviewData.localServers,
) : LocalRuntimeController {
    private val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    override val busy: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    private val mutableLog = MutableStateFlow(log)
    override val logTail: StateFlow<List<String>> = mutableLog.asStateFlow()

    private val mutableInfo = MutableStateFlow(info)
    override val info: StateFlow<RuntimeInfo?> = mutableInfo.asStateFlow()

    private val mutableUpdates = MutableStateFlow(updates)
    override val updates: StateFlow<RuntimeUpdateState> = mutableUpdates.asStateFlow()

    private val mutableAgents = MutableStateFlow(PreviewData.agents)
    override val agents: StateFlow<AgentsState> = mutableAgents.asStateFlow()

    override fun setAgents(selected: Set<String>) {
        mutableAgents.value = mutableAgents.value.copy(selected = selected)
    }

    private val mutableServers = MutableStateFlow(servers)
    override val servers: StateFlow<ServersState> = mutableServers.asStateFlow()

    override fun setServers(selected: Set<ServerKind>) {
        if (selected.isEmpty()) return
        mutableServers.value = mutableServers.value.copy(selected = selected)
    }

    override fun selectServer(kind: ServerKind) {
        if (kind !in mutableServers.value.installed) return
        mutableServers.value = mutableServers.value.copy(active = kind)
        mutableState.value = PreviewData.running
    }

    override fun checkForUpdates() = Unit

    override fun update() {
        mutableState.value = RuntimeState.Installing(InstallStep.CopyEnvironment, null, null, updating = true)
    }

    override fun dismissUpdateFailure() {
        mutableUpdates.value = mutableUpdates.value.copy(lastFailure = null)
    }

    override fun install() {
        mutableState.value = RuntimeState.Installing(InstallStep.DownloadRootfs, 0.1f, "alpine-minirootfs")
    }

    override fun start() {
        mutableState.value = PreviewData.running
    }

    override fun stop() {
        mutableState.value = RuntimeState.Stopped
    }

    override fun repair() = install()

    override fun uninstall() {
        mutableInfo.value = null
        mutableState.value = RuntimeState.NotInstalled
    }

    override fun perform(operation: RuntimeOperation) {
        when (operation) {
            RuntimeOperation.Install -> install()
            RuntimeOperation.Start -> start()
            RuntimeOperation.Repair -> repair()
            RuntimeOperation.Stop -> stop()
            RuntimeOperation.Uninstall -> uninstall()
            RuntimeOperation.Restart -> restart()
            RuntimeOperation.Update -> update()
            RuntimeOperation.ApplyAgents -> setAgents(agents.value.selected)
            RuntimeOperation.ApplyServers, RuntimeOperation.SelectServer -> Unit
        }
    }

    override fun restart() = start()
}

internal class FakeRemoteServerStore(
    initial: List<ServerTarget.Remote> = PreviewData.servers,
) : RemoteServerStore {
    private val mutable = MutableStateFlow(initial)
    override val servers: StateFlow<List<ServerTarget.Remote>> = mutable.asStateFlow()

    override fun upsert(server: ServerTarget.Remote) {
        mutable.value = mutable.value.withServerUpserted(server)
    }

    override fun remove(id: String) {
        mutable.value = mutable.value.filterNot { it.id == id }
    }

    override fun move(
        id: String,
        toIndex: Int,
    ) {
        mutable.value = mutable.value.withServerMoved(id, toIndex)
    }
}

/** Shared-storage switch for previews. Turning it on lands in NeedsPermission, as on a fresh install. */
internal class FakeSharedStorageController(
    initial: SharedStorageState = PreviewData.storage(SharedStorageStatus.Off),
) : SharedStorageController {
    private val mutable = MutableStateFlow(initial)
    override val state: StateFlow<SharedStorageState> = mutable.asStateFlow()

    override fun setEnabled(enabled: Boolean) {
        val status = if (enabled) SharedStorageStatus.NeedsPermission else SharedStorageStatus.Off
        mutable.value = mutable.value.copy(status = status)
    }

    override fun refreshPermission() = Unit
}

/** Answers with [result], or never answers when it is null (to preview the connecting state). */
internal class FakeWebSessionAuthenticator(
    private val result: WebSessionResult? = null,
) : WebSessionAuthenticator {
    @Suppress("UNUSED_PARAMETER")
    override suspend fun prepare(
        target: ServerTarget,
        cookies: WebSessionCookies,
    ): WebSessionResult = result ?: awaitCancellation()
}

internal object PreviewData {
    const val sharedRoot = "/storage/emulated/0/CoCode"

    fun storage(
        status: SharedStorageStatus,
        restartRequired: Boolean = false,
    ) = SharedStorageState(status = status, hostRoot = sharedRoot, restartRequired = restartRequired)

    val info =
        RuntimeInfo(
            abi = "arm64-v8a",
            alpineVersion = "3.24.1",
            t3Version = "0.1.6-pw.5",
            agents = mapOf("opencode" to "2.2.5"),
            nodeVersion = "24.14.0",
            diskUsageBytes = 1_262_485_504L,
            freeSpaceBytes = 38_654_705_664L,
        )

    /** Both servers installed, T3 Code in use. */
    val localServers =
        ServersState(
            selected = setOf(ServerKind.T3),
            installed = mapOf(ServerKind.T3 to "0.1.6-pw.5"),
            active = ServerKind.T3,
        )

    val agents =
        AgentsState(
            available =
                listOf(
                    io.cocode.app.runtime.manifest.AgentSpec("codex", "@openai/codex", "codex", "Codex"),
                    io.cocode.app.runtime.manifest.AgentSpec("claude", "@anthropic-ai/claude-code", "claude", "Claude Code"),
                    io.cocode.app.runtime.manifest.AgentSpec("opencode", "@opencode/cli", "opencode", "OpenCode"),
                ),
            selected = setOf("opencode"),
            installed = mapOf("opencode" to "2.2.5"),
        )

    val running =
        RuntimeState.Running(
            url = "http://127.0.0.1:3773",
            port = 3773,
            sinceEpochMs = System.currentTimeMillis() - 2 * 60 * 60 * 1000L - 14 * 60 * 1000L,
        )

    val installing =
        RuntimeState.Installing(
            step = InstallStep.InstallPackages,
            progress = 0.62f,
            detail = "(38/61) Installing nodejs (24.14.0-r0)",
        )

    val servers =
        listOf(
            ServerTarget.Remote(
                id = "workstation",
                displayName = "Workstation",
                baseUrl = "http://192.168.1.20:3000",
                uiPassword = "secret",
            ),
            ServerTarget.Remote(
                id = "laptop",
                displayName = "Laptop (Tailscale)",
                baseUrl = "http://laptop.tail0c1d2.ts.net:3000",
                uiPassword = null,
            ),
        )

    val log =
        listOf(
            "$ apk add --no-cache nodejs npm git bash curl ripgrep",
            "fetch https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/APKINDEX.tar.gz",
            "(36/61) Installing libstdc++ (14.2.0-r6)",
            "(37/61) Installing ca-certificates (20250911-r0)",
            "(38/61) Installing nodejs (24.14.0-r0)",
            "WARN: low battery, keep the phone plugged in",
            "Listening on http://127.0.0.1:3773",
        )
}
