package io.cocode.app.runtime

import kotlinx.coroutines.flow.StateFlow

/**
 * Contract between the runtime layer (PRoot + Alpine + Cox + agents) and the UI layer.
 * The UI only depends on this file; implementations live in io.cocode.app.runtime.*.
 */

/** Ordered steps of the first-time on-device setup. */
enum class InstallStep {
    DownloadRootfs,
    ExtractRootfs,
    InstallPackages,

    /** Update only: copy the active environment into staging. */
    CopyEnvironment,

    /** Install and verify the Cox npm server. */
    InstallServer,

    /** Install selected npm CLIs and select SDKs bundled with Cox. */
    InstallAgents,
    Verify,
    ;

    companion object {
        /** Steps of a first setup or a repair, in order. */
        val setupSteps: List<InstallStep> =
            listOf(DownloadRootfs, ExtractRootfs, InstallPackages, InstallServer, InstallAgents, Verify)

        /** Steps of a T3 Code / agent CLI update, in order. */
        val updateSteps: List<InstallStep> =
            listOf(CopyEnvironment, InstallServer, InstallAgents, Verify)
    }
}

/** Which part of the lifecycle a failure came from; drives what Retry does. */
enum class FailurePhase {
    /** Download, extraction, packages, npm or verification failed. Retry reinstalls. */
    Install,

    /** The server did not come up. Retry starts it again. */
    Start,

    /** The server was running and then died. Retry starts it again. */
    Runtime,
}

sealed interface RuntimeState {
    /** Initial state, before the on-disk check has finished. */
    data object Detecting : RuntimeState

    /** Device ABI is not arm64-v8a / x86_64. */
    data object UnsupportedAbi : RuntimeState

    data object NotInstalled : RuntimeState

    /** [progress] is 0f..1f for the current step, or null when indeterminate. */
    data class Installing(
        val step: InstallStep,
        val progress: Float?,
        /** Technical detail text (untranslated), e.g. a package command; the step label is localised. */
        val detail: String?,
        /** Localised progress phrase for the current sub-step, when there is one. */
        val messageKey: MessageKey? = null,
        /** True while updating the installed environment, not a first setup or repair. */
        val updating: Boolean = false,
    ) : RuntimeState

    data object Stopped : RuntimeState

    data class Starting(val detail: String?) : RuntimeState

    /** [url] is the loopback base URL of the active server, e.g. http://127.0.0.1:3773 or :3000. */
    data class Running(val url: String, val port: Int, val sinceEpochMs: Long) : RuntimeState

    data class Stopping(val detail: String?) : RuntimeState

    /**
     * [reason] is the English, technical text (logs, tests, fallbacks). [messageKey] names the
     * localised headline the UI should show instead; [detail] is raw technical output (logs, command
     * text) and is never translated. [recoverable] true -> offer "Retry/Start", false -> offer
     * "Repair (reinstall)". [phase] says what Retry should redo.
     */
    data class Failed(
        val reason: String,
        val recoverable: Boolean,
        val phase: FailurePhase = FailurePhase.Runtime,
        val messageKey: MessageKey? = null,
        val detail: String? = null,
    ) : RuntimeState
}

/**
 * Keys for user-visible runtime messages. The UI resolves each to a string resource; new messages get
 * a key here and an entry in values/strings.xml and values-zh/strings.xml.
 */
enum class MessageKey {
    /** Network output seen during setup: the Alpine or npm mirrors were not reachable. */
    MirrorsUnreachable,

    /** Linux packages (apk) failed to install. */
    PackagesFailed,

    /** The T3 Code server bundle could not be downloaded, verified or extracted. */
    ServerInstallFailed,

    /** One of the selected agent CLIs could not be installed from npm. */
    AgentInstallFailed,

    /** No agent is selected; at least one is required. */
    AgentsRequired,

    /** The T3 Code port is held by another app. */
    PortInUse,

    /** T3 Code exited while starting. */
    ServerExitedDuringStart,

    /** T3 Code did not start listening in time. */
    ServerNotListeningInTime,

    /** T3 Code did not become ready in time. */
    ServerNotReadyInTime,

    /** T3 Code stopped on its own and the restart budget is spent. */
    ServerKeptStopping,

    /** T3 Code stopped unexpectedly. */
    ServerStoppedUnexpectedly,

    /** A restart attempt failed. */
    ServerRestartFailed,

    /** The embedded PRoot launcher is missing from the app. */
    EmbeddedProotMissing,

    /** Generic failure with no more specific message. */
    Generic,

    /** The bundled node-pty terminal module does not work in the guest. */
    PtySmokeFailed,

    /** Install sub-step: unpacking the Linux base. */
    ProgressUnpacking,

    /** Install sub-step: installing system packages. */
    ProgressInstallingPackages,

    /** Install sub-step: downloading and unpacking T3 Code. */
    ProgressInstallingServer,

    /** Install sub-step: installing the selected agent CLIs. */
    ProgressInstallingAgents,

    /** Install sub-step: checking the installation. */
    ProgressVerifying,

    /** Install sub-step: activating the environment. */
    ProgressActivating,

    /** Install finished. */
    ProgressDone,

    /** Update sub-step: copying the current environment before changing it. */
    ProgressCopyingEnvironment,

    /** Not enough free space to copy the environment for an update. */
    UpdateNotEnoughSpace,
}

/** One component's installed version and the version an update would install. */
data class VersionChange(
    /** Installed version, or null when unknown. */
    val current: String?,
    val target: String,
) {
    val changed: Boolean get() = current != target
}

/** One agent CLI that moves to a new version. */
data class AgentChange(
    val id: String,
    val displayName: String,
    val change: VersionChange,
)

/** Newer Cox and agent CLI versions the installed runtime can move to. */
data class RuntimeUpdate(
    /** Cox's change. [current] equals [target] when Cox is not installed. */
    val t3: VersionChange,
    val agents: List<AgentChange> = emptyList(),
    /** Server-keyed changes retained for callers using the historical selection API. */
    val servers: Map<ServerKind, VersionChange> = emptyMap(),
    /**
     * True when [t3] replaces the legacy T3 Code bundle with Cox. The versions are from two products,
     * so the offer is made regardless of how they compare and the UI names both products.
     */
    val replacesLegacyT3: Boolean = false,
    val migratesCoxToNpm: Boolean = false,
) {
    val hasChanges: Boolean get() = migratesCoxToNpm || t3.changed || agents.any { it.change.changed } || servers.values.any { it.changed }
}

/** Why the last update attempt failed. The previous environment is kept and still usable. */
data class UpdateFailure(
    /** English, technical text. */
    val reason: String,
    val messageKey: MessageKey? = null,
    /** Raw technical output; never translated. */
    val detail: String? = null,
)

/** Update check and update outcome, separate from [RuntimeState] so a failed update never blocks the runtime. */
data class RuntimeUpdateState(
    /** A check against GitHub and the npm registry is running. */
    val checking: Boolean = false,
    /** Non-null only when it has changes. */
    val available: RuntimeUpdate? = null,
    /** When the last check finished (success or failure), epoch ms. */
    val lastCheckedAtMs: Long? = null,
    /** The last check could not reach GitHub or the npm registry. [available] may still come from bundled versions. */
    val checkFailed: Boolean = false,
    /** Set when the last update or agent change failed; cleared by a new one or [LocalRuntimeController.dismissUpdateFailure]. */
    val lastFailure: UpdateFailure? = null,
)

data class RuntimeInfo(
    val abi: String,
    val alpineVersion: String,
    /** Cox's version (legacy field name), kept for the old UI; null when Cox is not installed. */
    val t3Version: String?,
    /** Installed server versions, keyed by kind. */
    val serverVersions: Map<ServerKind, String> = emptyMap(),
    /** Installed agent versions, keyed by agent id. */
    val agents: Map<String, String>,
    val nodeVersion: String?,
    val diskUsageBytes: Long?,
    val freeSpaceBytes: Long?,
)

interface LocalRuntimeController {
    val state: StateFlow<RuntimeState>

    /**
     * True while an operation (install, start, stop, repair, uninstall) or an automatic crash
     * recovery is queued or running. The foreground service uses it to decide when work has ended,
     * instead of inferring that from [state].
     */
    val busy: StateFlow<Boolean>

    /** Rolling tail of runtime/installer log lines (most recent last, bounded). */
    val logTail: StateFlow<List<String>>

    val info: StateFlow<RuntimeInfo?>

    /** Available agents, the saved selection, required dependencies and the installed versions. */
    val agents: StateFlow<AgentsState>

    /** Selected/installed servers and the one active server. */
    val servers: StateFlow<ServersState>

    /** Persists the server selection; after setup the diff is applied transactionally. */
    fun setServers(selected: Set<ServerKind>)

    /** Stops the former server and starts [kind]; ignored unless [kind] is installed. */
    fun selectServer(kind: ServerKind)

    /**
     * Persists a new agent selection. An empty set, or one with no agent this build offers, is
     * ignored: the previous valid selection is kept, because the UI calls this straight from a
     * click and a draft may briefly select nothing. When a runtime is installed, queues
     * [RuntimeOperation.ApplyAgents]: stops the server, installs additions and removes deselected
     * agents in place, verifies, updates metadata and restarts the server if it was running. The
     * same selection is applied again when the installed set still differs from it, so retrying
     * after a failed apply works even though the selection was already persisted.
     */
    fun setAgents(selected: Set<String>)

    fun install()

    fun start()

    fun stop()

    /** Wipe the environment and install again (keeps user workspace when possible). */
    fun repair()

    /** Remove the whole runtime directory. */
    fun uninstall()

    /** Stop and start again as one queued operation (applies new storage bindings). */
    fun restart()

    /** Update check state and the outcome of the last update. */
    val updates: StateFlow<RuntimeUpdateState>

    /** Checks GitHub and npm for newer T3 Code / agent versions now (no-op while a check runs). */
    fun checkForUpdates()

    /**
     * Installs [RuntimeUpdateState.available] into a copy of the environment and swaps it in. A running
     * server is stopped first and started again afterwards; a failure keeps the old environment.
     */
    fun update()

    fun dismissUpdateFailure()

    /**
     * Runs one operation inside the foreground service. Called by RuntimeService only, after it has
     * started in the foreground. UI code uses install/start/stop/repair/uninstall above.
     */
    fun perform(operation: RuntimeOperation)
}

/** A server the WebView can open. */
sealed interface ServerTarget {
    val id: String
    val displayName: String
    val baseUrl: String
    val kind: ServerKind

    data class Local(
        override val baseUrl: String,
        override val kind: ServerKind = ServerKind.T3,
    ) : ServerTarget {
        override val id: String = kind.localTargetId
        override val displayName: String = "This device"
    }

    data class Remote(
        override val id: String,
        override val displayName: String,
        override val baseUrl: String,
        /** T3 Code auth password, if the remote server requires one. Stored encrypted. */
        val uiPassword: String?,
        /**
         * Allow sending the password over plain http to a non-loopback host. Off by default; the
         * confirm UI sets it. Without it a password is never sent in cleartext.
         */
        val allowInsecureHttp: Boolean = false,
        /** Remote servers are T3 Code unless persistence records otherwise. */
        override val kind: ServerKind = ServerKind.T3,
    ) : ServerTarget

    companion object {
        /** Historical local id; new local targets use [ServerKind.localTargetId]. */
        const val LOCAL_ID = "local"
    }
}

interface RemoteServerStore {
    val servers: StateFlow<List<ServerTarget.Remote>>

    /** Adds [server] at the end, or replaces the saved server with the same id in place. */
    fun upsert(server: ServerTarget.Remote)

    fun remove(id: String)

    /**
     * Moves server [id] so it ends up at [toIndex] (clamped), in one write. Unknown ids are
     * ignored; the list keeps its length.
     */
    fun move(
        id: String,
        toIndex: Int,
    )
}

sealed interface WebSessionResult {
    /** Session cookies (if any) were stored in the target's own WebView profile, returned by
     *  [WebSessionCookies]; the UI may load [url]. */
    data class Ready(val url: String) : WebSessionResult

    /** Server reachable but no stored password / wrong password; UI may still load the page
     *  and let T3 Code show its own login screen. */
    data class NeedsLogin(val url: String) : WebSessionResult

    /** Remote server is protected by an interactive Cloudflare Access gateway. */
    data class NeedsGatewayLogin(val url: String) : WebSessionResult

    data class Unreachable(val reason: String) : WebSessionResult
}

/** The profile a WebView is bound to: one server target at one canonical origin (see canonicalWebOrigin). */
data class WebSessionIdentity(
    val targetId: String,
    val origin: String,
)

/**
 * Cookie capability of one bound WebView profile. Only ever valid for [identity]: a different
 * origin gets no cookies back and nothing is written for it.
 */
interface WebSessionCookies {
    val identity: WebSessionIdentity

    /** The Cookie header the profile would send to [url], or null when [url] is not on [identity]'s origin. */
    suspend fun cookieHeader(url: String): String?

    /** Writes the Set-Cookie [headers] for [url] and returns only after the platform has finished
     *  writing them. Throws when a write fails or [url] is not on [identity]'s origin. */
    suspend fun storeCookies(
        url: String,
        headers: List<String>,
    )
}

interface WebSessionAuthenticator {
    /** Health-checks the target, logs in via POST /api/auth/browser-session when a password is known,
     *  and stores the session cookies through [cookies] (the capability of the target's bound profile). */
    suspend fun prepare(
        target: ServerTarget,
        cookies: WebSessionCookies,
    ): WebSessionResult
}
