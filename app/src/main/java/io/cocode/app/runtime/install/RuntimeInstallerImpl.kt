package io.cocode.app.runtime.install

import io.cocode.app.runtime.RuntimePaths
import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.fs.deleteTreeOrThrow
import io.cocode.app.runtime.manifest.RuntimeManifest
import io.cocode.app.runtime.process.EmbeddedCommandSuite
import kotlinx.serialization.json.Json
import java.io.File

/**
 * [RuntimeInstaller] over the on-disk layout. Reads the active environment's metadata and
 * repairs an interrupted swap on startup. Not thread-safe; the controller serialises calls.
 */
class RuntimeInstallerImpl(
    private val paths: RuntimePaths,
    private val abi: String,
    private val manifest: RuntimeManifest,
    private val suite: () -> EmbeddedCommandSuite.Paths,
    private val downloader: VerifiedRuntimeDownloader,
    private val shimAsset: () -> String,
    private val dnsProvider: io.cocode.app.runtime.network.SystemDnsProvider =
        io.cocode.app.runtime.network.SystemDnsProvider { emptyList() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : RuntimeInstaller {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val installer by lazy {
        RuntimeEnvironmentInstaller(
            paths,
            abi,
            manifest,
            downloader,
            shimAsset,
            dnsProvider,
            nowMillis,
        )
    }

    override fun recover(): Boolean =
        recoverInterruptedRuntimeEnvironment(
            active = paths.environment,
            rollback = paths.rollback,
        )

    override fun installedRuntime(): InstalledRuntime? {
        val metadataFile = paths.metadataFile
        if (!metadataFile.isFile || !paths.rootfs.isDirectory) return null
        normalizeRuntimeSymlinks(paths.environment)
        val metadata =
            runCatching { json.decodeFromString<RuntimeMetadata>(metadataFile.readText()) }
                .getOrNull() ?: return null
        if (metadata.abi != abi) return null
        val servers = installedServerVersions(paths.rootfs, metadata)
        if (servers.isEmpty()) return null
        val agents =
            metadata.agents.filter { (id, _) ->
                manifest.agent(id)?.let { InstalledComponents.agentFilesPresent(paths.rootfs, it) } == true
            }
        val normalized =
            metadata.copy(
                schemaVersion = 2,
                servers = servers.mapKeys { (kind, _) -> kind.id },
                agents = agents,
                t3Version = servers[ServerKind.T3] ?: "",
            )
        if (normalized != metadata) {
            runCatching {
                io.cocode.app.runtime.fs.writeFileReplacing(
                    metadataFile,
                    json.encodeToString(RuntimeMetadata.serializer(), normalized),
                )
            }
        }
        return InstalledRuntime(normalized, paths.rootfs)
    }

    override suspend fun install(
        report: InstallReporter,
        agents: Set<String>,
        servers: Set<ServerKind>,
    ): InstalledRuntime {
        paths.root.mkdirs()
        recover()
        val proot = suite()
        return installer.install(report, proot, agents, servers)
    }

    override suspend fun update(
        report: InstallReporter,
        versions: PackageVersions,
    ): InstalledRuntime {
        paths.root.mkdirs()
        recover()
        val proot = suite()
        return installer.update(report, proot, versions)
    }

    override suspend fun applyAgents(
        report: InstallReporter,
        selected: Set<String>,
    ): InstalledRuntime {
        paths.root.mkdirs()
        recover()
        val proot = suite()
        return installer.applyAgents(report, proot, selected)
    }

    override suspend fun applyServers(
        report: InstallReporter,
        selected: Set<ServerKind>,
        agents: Set<String>,
    ): InstalledRuntime {
        paths.root.mkdirs()
        recover()
        val proot = suite()
        return installer.applyServers(report, proot, selected, agents)
    }

    override fun discardEnvironment() {
        // Only the environment; /root (home) and the workspace are user data and are kept.
        deleteTreeOrThrow(paths.environment)
        deleteTreeOrThrow(paths.rollback)
    }

    override fun removeAll() {
        deleteTreeOrThrow(paths.root)
    }

    internal fun root(): File = paths.root
}
