package io.cocode.app.runtime.install

import io.cocode.app.runtime.InstallStep
import io.cocode.app.runtime.MessageKey
import io.cocode.app.runtime.ServerKind
import kotlinx.serialization.Serializable
import java.io.File

/** Persisted in `runtime/metadata.json`; describes the active environment. */
@Serializable
data class RuntimeMetadata(
    /** 2 adds [servers]; 1 and 0 are the v0.1.0 shape with only [t3Version]. */
    val schemaVersion: Int = 2,
    val runtimeVersion: String,
    val alpineVersion: String,
    val abi: String,
    val port: Int,
    /** Installed T3 Code server bundle version. Legacy field, still written for old readers. */
    val t3Version: String,
    /** Installed server versions, keyed by [ServerKind.id]. Empty means legacy T3-only metadata. */
    val servers: Map<String, String> = emptyMap(),
    /** Installed agent versions, keyed by agent id. Includes required dependencies. */
    val agents: Map<String, String> = emptyMap(),
    val nodeVersion: String? = null,
    val installedAt: Long,
)

/**
 * Versions the update check compares: the installed servers plus one version per effective agent.
 * [t3] is kept for existing code; [servers] carries every installed kind.
 */
data class PackageVersions(
    val t3: String,
    val agents: Map<String, String> = emptyMap(),
    val servers: Map<ServerKind, String> = emptyMap(),
)

/**
 * Where newer versions come from (the npm registry in the app). Implementations
 * do network I/O and must be called off the main thread. Throws when a source cannot be read.
 */
fun interface PackageVersionSource {
    /** The newest versions for this environment, never below [pinned]. */
    suspend fun latest(pinned: PackageVersions): PackageVersions
}

data class InstalledRuntime(
    val metadata: RuntimeMetadata,
    /** `environment/rootfs` of the active environment. */
    val rootfs: File,
)

/** Receives progress and log lines while [RuntimeInstaller.install] runs. */
interface InstallReporter {
    fun step(
        step: InstallStep,
        progress: Float?,
        detail: String?,
        messageKey: MessageKey? = null,
    )

    fun log(line: String)
}

/** Provisions and inspects the Linux environment. Implementations must do blocking I/O on IO. */
interface RuntimeInstaller {
    /**
     * Settles a swap that a process death interrupted. Cheap when nothing is pending (one stat), so
     * it is safe to call at every detection. Returns true when a pre-commit swap was rolled back.
     * Throws only when a rollback itself fails.
     */
    fun recover(): Boolean

    /** The active environment, or null when nothing usable is installed. */
    fun installedRuntime(): InstalledRuntime?

    /**
     * Builds a fresh environment in staging, verifies it, then atomically activates it. The active
     * environment is untouched until that swap, so a failed install or repair keeps the old one.
     * [agents] is the effective agent set (user selection plus required dependencies) and must not
     * be empty; [servers] is the selected server set and must not be empty. Throws on failure.
     */
    suspend fun install(
        report: InstallReporter,
        agents: Set<String>,
        servers: Set<ServerKind>,
    ): InstalledRuntime

    /**
     * Updates the installed servers and effective agents to [versions] without a new Linux base:
     * copies the active environment into staging, updates/verifies each installed server and changed
     * agent, then swaps it in like [install]. The active environment is untouched until that swap,
     * so a failed update keeps the old one. The server must be stopped first. Throws on failure (a
     * [io.cocode.app.runtime.UserFacingException] with `UpdateNotEnoughSpace` when the disk is too full).
     */
    suspend fun update(
        report: InstallReporter,
        versions: PackageVersions,
    ): InstalledRuntime

    /**
     * Applies an effective agent selection to the active environment through a staging copy and an
     * atomic swap (the server must be stopped). Only added agents are installed; agents that stay
     * selected are left untouched. Every effective agent is verified before the swap, then agents
     * no longer needed are removed. Throws on failure, leaving the active environment untouched.
     */
    suspend fun applyAgents(
        report: InstallReporter,
        selected: Set<String>,
    ): InstalledRuntime

    /** Applies the compatibility server/agent selection using a verified atomic staging swap. */
    suspend fun applyServers(
        report: InstallReporter,
        selected: Set<ServerKind>,
        agents: Set<String>,
    ): InstalledRuntime

    /**
     * Removes the environment (rootfs + metadata) but keeps the guest home, workspace and secrets.
     * Not for repair: a repair needs no pre-delete, [install] swaps the environment in place.
     */
    fun discardEnvironment()

    /** Removes the whole runtime directory, including the guest home and workspace. */
    fun removeAll()
}
