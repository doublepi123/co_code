package io.cocode.app.runtime.install

import io.cocode.app.runtime.InstallStep
import io.cocode.app.runtime.MessageKey
import kotlinx.serialization.Serializable
import java.io.File

/** Persisted in `runtime/metadata.json`; describes the active environment. */
@Serializable
data class RuntimeMetadata(
    val runtimeVersion: String,
    val alpineVersion: String,
    val abi: String,
    val port: Int,
    /** Installed T3 Code server bundle version (`0.1.6-pw.5`). */
    val t3Version: String,
    /** Installed agent versions, keyed by agent id. */
    val agents: Map<String, String> = emptyMap(),
    val nodeVersion: String? = null,
    val installedAt: Long,
)

/**
 * Versions the update check compares: the T3 Code server plus one version per installed agent.
 * [agents] holds only the agents that are installed (and selected).
 */
data class PackageVersions(
    val t3: String,
    val agents: Map<String, String> = emptyMap(),
)

/**
 * Where newer versions come from (GitHub releases and the npm registry in the app). Implementations
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
     * [agents] is the selected agent set and must not be empty; agents are installed at their latest
     * npm version. Throws on failure.
     */
    suspend fun install(
        report: InstallReporter,
        agents: Set<String>,
    ): InstalledRuntime

    /**
     * Updates T3 Code and the selected agents to [versions] without downloading a new Linux base:
     * copies the active environment into staging, replaces the T3 bundle, reinstalls changed agents,
     * verifies, then swaps it in like [install]. The active environment is untouched until that swap,
     * so a failed update keeps the old one. The server must be stopped first. Throws on failure (a
     * [io.cocode.app.runtime.UserFacingException] with `UpdateNotEnoughSpace` when the disk is too full).
     */
    suspend fun update(
        report: InstallReporter,
        versions: PackageVersions,
    ): InstalledRuntime

    /**
     * Applies an agent selection to the active environment in place (the server must be stopped).
     * Removes agents that are no longer selected, installs the new ones, verifies every selected
     * agent and rewrites `metadata.json`. Throws on failure, leaving the previous metadata untouched.
     */
    suspend fun applyAgents(
        report: InstallReporter,
        selected: Set<String>,
    ): InstalledRuntime

    /**
     * Removes the environment (rootfs + metadata) but keeps the guest home, workspace and secrets.
     * Not for repair: a repair needs no pre-delete, [install] swaps the environment in place.
     */
    fun discardEnvironment()

    /** Removes the whole runtime directory, including the guest home and workspace. */
    fun removeAll()
}
