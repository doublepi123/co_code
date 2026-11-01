package io.cocode.app.runtime.install

import android.system.Os
import io.cocode.app.runtime.InstallStep
import io.cocode.app.runtime.MessageKey
import io.cocode.app.runtime.RuntimePaths
import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.UserFacingException
import io.cocode.app.runtime.agentSelectionChange
import io.cocode.app.runtime.fs.copyTreeNoFollow
import io.cocode.app.runtime.fs.copyTreeStrict
import io.cocode.app.runtime.fs.deleteTree
import io.cocode.app.runtime.fs.deleteTreeOrThrow
import io.cocode.app.runtime.fs.treeSize
import io.cocode.app.runtime.fs.writeFileReplacing
import io.cocode.app.runtime.manifest.AgentSpec
import io.cocode.app.runtime.manifest.RuntimeManifest
import io.cocode.app.runtime.network.ResolvConf
import io.cocode.app.runtime.network.SystemDnsProvider
import io.cocode.app.runtime.process.EmbeddedCommandSuite
import io.cocode.app.runtime.process.ProcessScanner
import io.cocode.app.runtime.process.ProotCommands
import io.cocode.app.runtime.process.terminateProcessTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/** Bounded wait for a guest command's output reader after its stream is closed. */
internal const val OUTPUT_JOIN_MILLIS = 2_000L

/**
 * The two guest operations agent selection needs: run an install script and capture a CLI's
 * version. [RuntimeEnvironmentInstaller.GuestRunner] is the production implementation; tests use a
 * fake to exercise [RuntimeEnvironmentInstaller.applyAgentSelection] without PRoot.
 */
internal interface AgentCommandRunner {
    fun mustSucceed(
        script: String,
        failure: MessageKey,
        failureText: String,
        timeoutMinutes: Long = RuntimeEnvironmentInstaller.DEFAULT_TIMEOUT_MINUTES,
    )

    fun output(script: String): String
}

/**
 * Builds the Alpine + Node + selected server(s) + agent CLI environment in staging and activates it.
 * Each guest command's output is streamed to [InstallReporter.log] and to `logs/install.log`.
 *
 * The active environment is never touched until the staged one is complete and verified: the swap in
 * [activateRuntimeEnvironment] is the only mutation of it. The guest `/root` lives outside the
 * environment (see [RuntimePaths.home]), so a rebuild does not delete user data. Install/update/apply
 * commands use a disposable home inside staging, so package scripts and version probes never edit the
 * persistent config.
 */
class RuntimeEnvironmentInstaller(
    private val paths: RuntimePaths,
    private val abi: String,
    private val manifest: RuntimeManifest,
    private val downloader: VerifiedRuntimeDownloader,
    private val releaseSource: T3ReleaseSource,
    private val shimAsset: () -> String,
    private val dnsProvider: SystemDnsProvider = SystemDnsProvider { emptyList() },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val killPid: (Long) -> Unit = { pid -> android.os.Process.killProcess(pid.toInt()) },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Full install: Alpine, packages, selected servers, PTY checks, effective agents, verify, activate. */
    suspend fun install(
        report: InstallReporter,
        proot: EmbeddedCommandSuite.Paths,
        agents: Set<String>,
        servers: Set<ServerKind>,
    ): InstalledRuntime =
        withContext(Dispatchers.IO) {
            val wantedAgents = requireAgents(agents)
            val wantedServers = requireServers(servers)
            val architecture = manifest.architecture(abi)
            paths.cache.mkdirs()
            paths.logs.mkdirs()
            paths.prootTmp.mkdirs()
            paths.home.mkdirs()
            paths.installLog.writeText("")
            deleteTreeOrThrow(paths.staging)
            val staging = paths.staging.apply { mkdirs() }
            try {
                val archive = File(paths.cache, "alpine-${manifest.alpineVersion}-$abi.tar.gz")
                report.step(InstallStep.DownloadRootfs, 0f, "Alpine ${manifest.alpineVersion}")
                downloader.download(
                    url = architecture.alpineUrl,
                    destination = archive,
                    expectedSha256 = architecture.alpineSha256,
                ) { fraction ->
                    report.step(InstallStep.DownloadRootfs, fraction, "Alpine ${manifest.alpineVersion}")
                }

                report.step(InstallStep.ExtractRootfs, null, null, MessageKey.ProgressUnpacking)
                val rootfs = File(staging, "rootfs").apply { mkdirs() }
                archive.inputStream().use { RuntimeArchive.extractTarGz(it, rootfs) }
                configureRootfs(rootfs)
                seedHome(rootfs)

                val runner = runnerFor(proot, rootfs, report, installHome(staging))

                report.step(InstallStep.InstallPackages, null, "apk add nodejs npm git ...", MessageKey.ProgressInstallingPackages)
                // set -e: any failing command fails the step, even inside a chain.
                runner.mustSucceed(
                    "set -e; export PATH=${ProotCommands.GUEST_PATH}; " +
                        "apk --cache-dir /var/cache/apk add ${apkPackages(ServerKind.OpenChamber in wantedServers)}; " +
                        "update-ca-certificates",
                    MessageKey.PackagesFailed,
                    "Unable to install Linux packages. Check the network and retry.",
                    timeoutMinutes = APK_TIMEOUT_MINUTES,
                )

                // Shim before npm: install-time scripts also run under node and benefit from it.
                writeShim(rootfs)
                val versions = mutableMapOf<ServerKind, String>()
                if (ServerKind.T3 in wantedServers) {
                    val release = resolveRelease()
                    installServer(report, rootfs, release)
                    versions[ServerKind.T3] = release.version
                }
                if (ServerKind.OpenChamber in wantedServers) {
                    installOpenChamber(report, runner, rootfs, version = null)
                    versions[ServerKind.OpenChamber] =
                        InstalledComponents.openChamberVersion(rootfs) ?: manifest.openchamber.version
                }

                report.step(InstallStep.InstallAgents, null, wantedAgents.joinToString(" "), MessageKey.ProgressInstallingAgents)
                installAgentPackages(runner, wantedAgents)
                val agentVersions = verifyAgents(runner, wantedAgents)

                report.step(InstallStep.Verify, null, "node, servers, agents", MessageKey.ProgressVerifying)
                verifyServers(runner, rootfs, versions.keys)
                val nodeVersion = nodeVersion(runner)
                requireNodeForServers(nodeVersion, versions.keys)

                val metadata =
                    RuntimeMetadata(
                        schemaVersion = 2,
                        runtimeVersion = manifest.runtimeVersion,
                        alpineVersion = manifest.alpineVersion,
                        abi = abi,
                        port = manifest.port,
                        t3Version = versions[ServerKind.T3] ?: "",
                        servers = versions.mapKeys { (kind, _) -> kind.id },
                        agents = agentVersions,
                        nodeVersion = nodeVersion,
                        installedAt = nowMillis(),
                    )
                writeMetadata(staging, metadata)
                activate(report, staging)
                InstalledRuntime(metadata, paths.rootfs)
            } catch (error: Throwable) {
                // Best effort: a leftover staging tree is removed before the next install anyway.
                deleteTree(staging)
                throw error
            }
        }

    /**
     * Updates the installed servers and effective agents of the active environment to [versions]
     * without a new Alpine base. The environment is copied into staging (symlinks and modes kept),
     * installed servers are updated/repaired, changed agent packages are reinstalled there, then the
     * same verification and swap as [install] run. Any failure deletes staging; the active
     * environment is untouched.
     */
    suspend fun update(
        report: InstallReporter,
        proot: EmbeddedCommandSuite.Paths,
        versions: PackageVersions,
    ): InstalledRuntime =
        withContext(Dispatchers.IO) {
            val current =
                checkNotNull(installedMetadataOrNull()) { "No active runtime environment to update" }
            val selectedAgents = requireAgents(versions.agents.keys)
            val installedServers = installedServerVersions(paths.rootfs, current)
            paths.cache.mkdirs()
            paths.logs.mkdirs()
            paths.prootTmp.mkdirs()
            paths.home.mkdirs()
            paths.installLog.writeText("")
            deleteTreeOrThrow(paths.staging)
            val staging = paths.staging
            try {
                val needed = treeSize(paths.environment) + UPDATE_FREE_SPACE_MARGIN_BYTES
                val free = paths.root.usableSpace
                if (free < needed) {
                    throw UserFacingException(
                        MessageKey.UpdateNotEnoughSpace,
                        "Not enough free space to update: need $needed bytes, have $free",
                    )
                }

                report.step(InstallStep.CopyEnvironment, null, null, MessageKey.ProgressCopyingEnvironment)
                copyTreeStrict(paths.environment, staging)
                // The copy's links still name the live environment. Point them into staging now, so npm
                // never writes through them into the active files. No marker is written here; the
                // post-commit pass runs on the new environment.
                clearSymlinkMarker(staging)
                normalizeRuntimeSymlinks(staging, writeMarker = false)
                val rootfs = File(staging, "rootfs")
                check(rootfs.isDirectory) { "Copied environment has no rootfs" }
                ResolvConf.write(rootfs, dnsProvider.dnsServers())

                val runner = runnerFor(proot, rootfs, report, installHome(staging))
                writeShim(rootfs)

                val serverVersions = mutableMapOf<ServerKind, String>()
                val serverTargets =
                    installedServers.mapValues { (kind, currentVersion) ->
                        when (kind) {
                            ServerKind.T3 -> versions.t3
                            ServerKind.OpenChamber -> versions.servers[kind] ?: currentVersion
                        }
                    }
                for ((kind, target) in serverTargets) {
                    val currentVersion = installedServers[kind]
                    if (currentVersion != target || !InstalledComponents.serverAssetsPresent(rootfs, kind)) {
                        when (kind) {
                            ServerKind.T3 -> {
                                removeServerAssets(rootfs, ServerKind.T3)
                                installServer(report, rootfs, releaseFor(target))
                            }
                            ServerKind.OpenChamber -> {
                                ensureBuildTools(report, runner)
                                installOpenChamber(report, runner, rootfs, version = target)
                            }
                        }
                    }
                    serverVersions[kind] =
                        if (kind == ServerKind.OpenChamber) {
                            InstalledComponents.openChamberVersion(rootfs) ?: target
                        } else {
                            target
                        }
                }

                report.step(InstallStep.InstallAgents, null, null, MessageKey.ProgressInstallingAgents)
                val metadataAfterAgents =
                    applyAgentSelection(
                        report = report,
                        environment = staging,
                        rootfs = rootfs,
                        current = current,
                        wanted = selectedAgents,
                        runner = runner,
                        targetVersions = versions.agents,
                        expectedServers = serverVersions,
                    )

                report.step(InstallStep.Verify, null, "node, servers, agents", MessageKey.ProgressVerifying)
                verifyServers(runner, rootfs, serverVersions.keys)
                val nodeVersion = nodeVersion(runner)
                requireNodeForServers(nodeVersion, serverVersions.keys)
                val metadata =
                    metadataAfterAgents.copy(
                        schemaVersion = 2,
                        port = manifest.port,
                        t3Version = serverVersions[ServerKind.T3] ?: "",
                        servers = serverVersions.mapKeys { (kind, _) -> kind.id },
                        nodeVersion = nodeVersion,
                        installedAt = nowMillis(),
                    )
                writeMetadata(staging, metadata)
                activate(report, staging)
                InstalledRuntime(metadata, paths.rootfs)
            } catch (error: Throwable) {
                deleteTree(staging)
                throw error
            }
        }

    /** Applies an effective agent selection through a staging copy and an atomic swap. */
    suspend fun applyAgents(
        report: InstallReporter,
        proot: EmbeddedCommandSuite.Paths,
        selected: Set<String>,
    ): InstalledRuntime =
        withContext(Dispatchers.IO) {
            val current = checkNotNull(installedMetadataOrNull()) { "No active runtime environment" }
            val wanted = requireAgents(selected)
            // Capture survivors before any package work; never learn the expected set from the
            // filesystem after npm may have pruned something.
            val expectedServers = installedServerVersions(paths.rootfs, current)
            val staging = copyActiveToStaging(report)
            try {
                val rootfs = File(staging, "rootfs")
                check(rootfs.isDirectory) { "Copied environment has no rootfs" }
                writeShim(rootfs)
                ResolvConf.write(rootfs, dnsProvider.dnsServers())
                val runner = runnerFor(proot, rootfs, report, installHome(staging))
                val metadata =
                    applyAgentSelection(
                        report = report,
                        environment = staging,
                        rootfs = rootfs,
                        current = current,
                        wanted = wanted,
                        runner = runner,
                        expectedServers = expectedServers,
                    )
                report.step(InstallStep.Verify, null, "servers, agents", MessageKey.ProgressVerifying)
                verifyServers(runner, rootfs, expectedServers.keys)
                writeMetadata(staging, metadata)
                activate(report, staging)
                InstalledRuntime(metadata, paths.rootfs)
            } catch (error: Throwable) {
                deleteTree(staging)
                throw error
            }
        }

    /** Applies a server selection through a staging copy and an atomic swap. */
    suspend fun applyServers(
        report: InstallReporter,
        proot: EmbeddedCommandSuite.Paths,
        selected: Set<ServerKind>,
        agents: Set<String>,
    ): InstalledRuntime =
        withContext(Dispatchers.IO) {
            val current = checkNotNull(installedMetadataOrNull()) { "No active runtime environment" }
            val wantedServers = requireServers(selected)
            val wantedAgents = requireAgents(agents)
            val staging = copyActiveToStaging(report)
            try {
                val rootfs = File(staging, "rootfs")
                check(rootfs.isDirectory) { "Copied environment has no rootfs" }
                writeShim(rootfs)
                ResolvConf.write(rootfs, dnsProvider.dnsServers())
                val runner = runnerFor(proot, rootfs, report, installHome(staging))
                val currentServers = installedServerVersions(rootfs, current)
                val versions = mutableMapOf<ServerKind, String>()
                for (kind in wantedServers) {
                    val currentVersion = currentServers[kind]
                    if (currentVersion != null && InstalledComponents.serverAssetsPresent(rootfs, kind)) {
                        versions[kind] = currentVersion
                    } else {
                        removeServerAssets(rootfs, kind)
                        when (kind) {
                            ServerKind.T3 -> {
                                val release = resolveRelease()
                                installServer(report, rootfs, release)
                                versions[kind] = release.version
                            }
                            ServerKind.OpenChamber -> {
                                ensureBuildTools(report, runner)
                                installOpenChamber(report, runner, rootfs, version = null)
                                versions[kind] =
                                    InstalledComponents.openChamberVersion(rootfs) ?: manifest.openchamber.version
                            }
                        }
                    }
                }
                for (kind in currentServers.keys - wantedServers) removeServerAssets(rootfs, kind)

                report.step(InstallStep.InstallAgents, null, null, MessageKey.ProgressInstallingAgents)
                val metadataAfterAgents =
                    applyAgentSelection(
                        report = report,
                        environment = staging,
                        rootfs = rootfs,
                        current = current,
                        wanted = wantedAgents,
                        runner = runner,
                        expectedServers = versions,
                    )
                report.step(InstallStep.Verify, null, "servers, agents", MessageKey.ProgressVerifying)
                verifyServers(runner, rootfs, versions.keys)
                val nodeVersion = nodeVersion(runner)
                requireNodeForServers(nodeVersion, versions.keys)
                val metadata =
                    metadataAfterAgents.copy(
                        schemaVersion = 2,
                        port = manifest.port,
                        t3Version = versions[ServerKind.T3] ?: "",
                        servers = versions.mapKeys { (kind, _) -> kind.id },
                        nodeVersion = nodeVersion,
                        installedAt = nowMillis(),
                    )
                writeMetadata(staging, metadata)
                activate(report, staging)
                InstalledRuntime(metadata, paths.rootfs)
            } catch (error: Throwable) {
                deleteTree(staging)
                throw error
            }
        }

    /** Copies the active environment and refreshes its guest links for safe staging writes. */
    private fun copyActiveToStaging(report: InstallReporter): File {
        val needed = treeSize(paths.environment) + UPDATE_FREE_SPACE_MARGIN_BYTES
        val free = paths.root.usableSpace
        if (free < needed) {
            throw UserFacingException(
                MessageKey.UpdateNotEnoughSpace,
                "Not enough free space to change the environment: need $needed bytes, have $free",
            )
        }
        paths.cache.mkdirs()
        paths.logs.mkdirs()
        paths.prootTmp.mkdirs()
        paths.home.mkdirs()
        paths.installLog.writeText("")
        deleteTreeOrThrow(paths.staging)
        report.step(InstallStep.CopyEnvironment, null, null, MessageKey.ProgressCopyingEnvironment)
        copyTreeStrict(paths.environment, paths.staging)
        clearSymlinkMarker(paths.staging)
        normalizeRuntimeSymlinks(paths.staging, writeMarker = false)
        return paths.staging
    }

    /**
     * The agent-only selection diff against [current], run through [runner]. It touches only agent
     * packages and the `agents` map of the metadata; every other metadata field is copied unchanged,
     * so a future server choice cannot be confused with an agent choice. Public [applyAgents]
     * supplies the real guest runner; tests supply a fake one to prove the package-removal contract.
     */
    internal fun applyAgentSelection(
        report: InstallReporter,
        environment: File,
        rootfs: File,
        current: RuntimeMetadata,
        wanted: Set<String>,
        runner: AgentCommandRunner,
        targetVersions: Map<String, String> = current.agents,
        expectedServers: Map<ServerKind, String> = installedServerVersions(rootfs, current),
        prepare: () -> Unit = {},
    ): RuntimeMetadata {
        val removals = agentSelectionChange(wanted, current.agents.keys).removals
        val install =
            wanted.filter { id ->
                current.agents[id] == null || current.agents[id] != targetVersions[id]
            }.toSet()
        prepare()

        // A changed or newly added package is removed host-side before npm runs: npm's in-place
        // upgrade over PRoot's faked hard links can leave a dangling .l2s chain. Agents that stay
        // selected at the same version are never touched.
        removeAgentPackages(rootfs, (install + removals).sorted().mapNotNull(manifest::agent))
        report.step(InstallStep.InstallAgents, null, null, MessageKey.ProgressInstallingAgents)
        installAgentPackages(runner, install, targetVersions)

        // Defensive check: an agent that stays selected is not ours to lose. If its package or bin
        // link is missing after the package work above (for any reason, e.g. an interrupted
        // operation), restore it at its recorded version before verifying.
        val lost =
            current.agents.keys
                .filter { it in wanted }
                .sorted()
                .filter { id -> manifest.agent(id)?.let { !InstalledComponents.agentFilesPresent(rootfs, it) } == true }
        if (lost.isNotEmpty()) {
            report.log("Agent packages are missing after install; restoring ${lost.joinToString(" ")}")
            removeAgentPackages(rootfs, lost.mapNotNull(manifest::agent))
            installAgentPackages(runner, lost.toSet(), current.agents)
        }

        report.step(InstallStep.Verify, null, null, MessageKey.ProgressVerifying)
        val versions = verifyAgents(runner, wanted)

        // The expected server set is captured before any package work. Never narrow metadata to
        // whatever happens to survive an npm run: a missing expected server fails the whole staging
        // transaction, so the active environment and its record stay untouched.
        val lostServers =
            expectedServers.keys
                .sorted()
                .filter { kind -> !InstalledComponents.serverAssetsPresent(rootfs, kind) }
        if (lostServers.isNotEmpty()) {
            throw UserFacingException(
                MessageKey.ServerInstallFailed,
                "Server assets disappeared during the operation: ${lostServers.joinToString(" ")}",
            )
        }
        val servers = expectedServers.mapKeys { (kind, _) -> kind.id }
        val metadata =
            current.copy(
                schemaVersion = 2,
                servers = servers,
                t3Version = servers[ServerKind.T3.id] ?: "",
                agents = versions,
            )
        writeMetadata(environment, metadata)
        return metadata
    }

    // --- Steps -----------------------------------------------------------------------------------

    private suspend fun installServer(
        report: InstallReporter,
        rootfs: File,
        release: T3Release,
    ) {
        val arch = manifest.t3Arch(abi)
        val assetName = manifest.t3.assetName(release.version, arch)
        report.step(InstallStep.InstallServer, null, assetName, MessageKey.ProgressInstallingServer)

        // Fail closed: without the checksum list the bundle is never unpacked.
        val expected =
            try {
                releaseSource.sha256Sums(release)[assetName]
            } catch (error: Throwable) {
                throw UserFacingException(
                    MessageKey.ServerInstallFailed,
                    "Unable to read the Cox checksum list: ${error.message}",
                )
            } ?: throw UserFacingException(
                MessageKey.ServerInstallFailed,
                "SHA256SUMS does not list $assetName",
            )

        val archive = File(paths.cache, assetName)
        downloader.download(
            url = release.assetUrl(assetName),
            destination = archive,
            expectedSha256 = expected,
        ) { fraction ->
            report.step(InstallStep.InstallServer, fraction, assetName, MessageKey.ProgressInstallingServer)
        }

        val target = File(rootfs, T3_GUEST.removePrefix("/"))
        deleteTreeOrThrow(target)
        target.mkdirs()
        archive.inputStream().use { RuntimeArchive.extractTarGz(it, target, stripComponents = 1) }
        verifyServerBundle(target, arch, release.version)
    }

    private fun verifyServerBundle(
        target: File,
        arch: String,
        version: String,
    ) {
        require(File(target, "bin.mjs").isFile) { "Cox bundle has no bin.mjs" }
        require(File(target, "client/index.html").isFile) { "Cox bundle has no client/index.html" }
        val info =
            InstalledComponents.bundleInDirectory(target)
                ?: error("Cox bundle has no readable ANDROID-BUNDLE.json")
        require(info.server == InstalledComponents.COX_SERVER) {
            "Cox bundle does not identify itself as ${InstalledComponents.COX_SERVER} (found ${info.server})"
        }
        require(info.arch == arch) { "Cox bundle is for ${info.arch}, expected $arch" }
        require(info.version == version) { "Cox bundle is ${info.version}, expected $version" }
    }

    private fun verifyPty(
        runner: GuestRunner,
        rootfs: File,
    ) {
        require(File(rootfs, PTY_MODULE_GUEST.removePrefix("/")).isDirectory) {
            "Cox bundle has no node-pty module"
        }
        File(rootfs, PTY_SMOKE_GUEST_PATH.removePrefix("/")).apply {
            parentFile?.mkdirs()
            writeText(PTY_SMOKE_SCRIPT)
        }
        runner.mustSucceed(
            "set -e; node $PTY_SMOKE_GUEST_PATH",
            MessageKey.PtySmokeFailed,
            "The bundled node-pty does not work in the guest. The install is incomplete.",
            timeoutMinutes = PTY_SMOKE_TIMEOUT_MINUTES,
        )
    }

    /** Installs OpenChamber from npm at [version], or latest when null, then rebuilds its node-pty. */
    private fun installOpenChamber(
        report: InstallReporter,
        runner: GuestRunner,
        rootfs: File,
        version: String?,
    ) {
        val packageSpecs =
            if (version != null && version.isNotBlank()) {
                listOf("${manifest.openchamber.npmPackage}@$version")
            } else {
                listOf(
                    manifest.openchamber.npmPackage,
                    "${manifest.openchamber.npmPackage}@${manifest.openchamber.version}",
                )
            }
        report.step(InstallStep.InstallServer, null, packageSpecs.first(), MessageKey.ProgressInstallingServer)
        var failure: Throwable? = null
        for (spec in packageSpecs) {
            removeServerAssets(rootfs, ServerKind.OpenChamber)
            try {
                runner.mustSucceed(
                    "set -e; export PATH=${ProotCommands.GUEST_PATH}; " +
                        "npm i -g --no-fund --no-audit $spec",
                    MessageKey.ServerInstallFailed,
                    "Unable to install OpenChamber from npm.",
                    timeoutMinutes = NPM_TIMEOUT_MINUTES,
                )
                failure = null
                break
            } catch (error: Exception) {
                failure = error
                report.log("OpenChamber npm install failed for $spec: ${error.message}")
            }
        }
        failure?.let { throw it }
        runner.mustSucceed(
            "set -e; export PATH=${ProotCommands.GUEST_PATH}; " +
                "cd $OPENCHAMBER_MODULE_GUEST/node_modules/node-pty && npx --yes node-gyp rebuild",
            MessageKey.ServerInstallFailed,
            "OpenChamber's terminal module could not be built.",
            timeoutMinutes = NPM_TIMEOUT_MINUTES,
        )
        verifyOpenChamber(rootfs)
    }

    /** Adds the native build toolchain OpenChamber's node-pty needs; idempotent. */
    private fun ensureBuildTools(
        report: InstallReporter,
        runner: GuestRunner,
    ) {
        report.step(InstallStep.InstallPackages, null, "apk add make g++ ...", MessageKey.ProgressInstallingPackages)
        runner.mustSucceed(
            "set -e; export PATH=${ProotCommands.GUEST_PATH}; " +
                "apk --cache-dir /var/cache/apk add python3 make g++ linux-headers",
            MessageKey.PackagesFailed,
            "Unable to install the build tools OpenChamber needs.",
            timeoutMinutes = APK_TIMEOUT_MINUTES,
        )
    }

    private fun verifyOpenChamber(rootfs: File) {
        require(File(rootfs, InstalledComponents.OPENCHAMBER_CLI).isFile) {
            "OpenChamber install has no bin/cli.js"
        }
        require(File(rootfs, InstalledComponents.OPENCHAMBER_UI).isFile) {
            "OpenChamber install has no dist/index.html"
        }
        require(File(rootfs, "$OPENCHAMBER_MODULE_GUEST/node_modules/node-pty").isDirectory) {
            "OpenChamber install has no node-pty module"
        }
    }

    private fun verifyOpenChamberPty(
        runner: GuestRunner,
        rootfs: File,
    ) {
        File(rootfs, OPENCHAMBER_PTY_SMOKE_GUEST_PATH.removePrefix("/")).apply {
            parentFile?.mkdirs()
            writeText(openChamberPtySmokeScript())
        }
        runner.mustSucceed(
            "set -e; node $OPENCHAMBER_PTY_SMOKE_GUEST_PATH",
            MessageKey.PtySmokeFailed,
            "OpenChamber's bundled node-pty does not work in the guest.",
            timeoutMinutes = PTY_SMOKE_TIMEOUT_MINUTES,
        )
    }

    /** Verifies every installed server's assets and terminal module; no server is left half-built. */
    private fun verifyServers(
        runner: GuestRunner,
        rootfs: File,
        kinds: Set<ServerKind>,
    ) {
        if (ServerKind.T3 in kinds) {
            require(InstalledComponents.serverAssetsPresent(rootfs, ServerKind.T3)) {
                "Cox install is incomplete"
            }
            verifyCursorSdkRuntime(runner, rootfs)
            verifyPty(runner, rootfs)
        }
        if (ServerKind.OpenChamber in kinds) {
            verifyOpenChamber(rootfs)
            verifyOpenChamberPty(runner, rootfs)
        }
    }

    /** Removes one server's program tree only; home, workspace, agents and the other server stay. */
    private fun removeServerAssets(
        rootfs: File,
        kind: ServerKind,
    ) {
        when (kind) {
            ServerKind.T3 -> deleteTreeOrThrow(File(rootfs, T3_GUEST.removePrefix("/")))
            ServerKind.OpenChamber -> {
                deleteTreeOrThrow(File(rootfs, OPENCHAMBER_MODULE_GUEST.removePrefix("/")))
                deleteTreeOrThrow(File(rootfs, "etc/openchamber"))
                deleteTreeOrThrow(File(rootfs, "usr/local/bin/openchamber"))
                deleteTreeOrThrow(File(rootfs, "usr/local/bin/openchamber2"))
            }
        }
    }

    private fun requireServers(selected: Set<ServerKind>): Set<ServerKind> {
        if (selected.isEmpty()) throw UserFacingException(MessageKey.Generic, "Select at least one server")
        return selected
    }

    private fun openChamberPtySmokeScript(): String =
        PTY_SMOKE_SCRIPT.replace(PTY_MODULE_GUEST, "$OPENCHAMBER_MODULE_GUEST/node_modules/node-pty")

    /** Installs [ids] from npm at their latest versions, or at [versions] when a version is known. */
    private fun installAgentPackages(
        runner: AgentCommandRunner,
        ids: Set<String>,
        versions: Map<String, String> = emptyMap(),
    ) {
        if (ids.isEmpty()) return
        val specs = ids.map(::spec)
        val script = agentInstallScript(specs, versions)
        runner.mustSucceed(
            script,
            MessageKey.AgentInstallFailed,
            "Unable to install the selected agent CLIs from npm.",
            timeoutMinutes = NPM_TIMEOUT_MINUTES,
        )
    }

    private fun verifyAgents(
        runner: AgentCommandRunner,
        ids: Set<String>,
    ): Map<String, String> =
        ids.associate { id ->
            val spec = spec(id)
            // e.g. `codex-cli 0.166.0`, `opencode v2.2.5`; parse the number, never the raw line.
            val output = runner.output("${spec.bin} --version")
            val version = extractVersion(output)
            require(version.isNotBlank()) { "${spec.displayName} did not report a version" }
            id to version.removePrefix("v")
        }

    private fun nodeVersion(runner: GuestRunner): String =
        extractVersion(runner.output("node --version")).removePrefix("v")

    /** OpenChamber's current npm package requires Node 24.14 or newer. */
    private fun requireNodeForServers(
        nodeVersion: String,
        kinds: Set<ServerKind>,
    ) {
        if (ServerKind.OpenChamber !in kinds) return
        if (compareVersions(nodeVersion, OPENCHAMBER_MIN_NODE) >= 0) return
        throw UserFacingException(
            MessageKey.ServerInstallFailed,
            "OpenChamber needs Node $OPENCHAMBER_MIN_NODE or newer (found $nodeVersion)",
        )
    }

    private fun spec(id: String): AgentSpec =
        requireNotNull(manifest.agent(id)) { "Unknown agent: $id" }

    private fun requireAgents(selected: Set<String>): Set<String> {
        if (selected.isEmpty()) throw UserFacingException(MessageKey.AgentsRequired, "Select at least one agent")
        selected.forEach(::spec)
        return selected
    }

    /**
     * Removes agent package trees and their bin links host-side, without following links.
     *
     * Why: `npm i -g` over an existing global install replaces files that PRoot's `--link2symlink`
     * has faked as hard links (`.l2s.*` chains). npm's unlink and rename of those leaves a dangling
     * chain: the binary reports "Operation not permitted" or "not found". A clean install does not hit
     * this. Missing entries are fine. Only the given rootfs is touched.
     */
    private fun removeAgentPackages(
        rootfs: File,
        specs: List<AgentSpec>,
    ) {
        for (spec in specs) {
            deleteTreeOrThrow(File(rootfs, "usr/local/lib/node_modules/${spec.npmPackage}"))
            for (bin in agentBinNames(spec)) deleteTreeOrThrow(File(rootfs, "usr/local/bin/$bin"))
        }
    }

    private suspend fun resolveRelease(): T3Release =
        try {
            releaseSource.latest() ?: releaseFor(manifest.t3.version)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Throwable) {
            releaseFor(manifest.t3.version)
        }

    private fun releaseFor(version: String): T3Release =
        T3Release(
            version = version,
            tag = "v$version",
            downloadBaseUrl = "https://github.com/${manifest.t3.repository}/releases/download/v$version",
        )

    private fun installedMetadataOrNull(): RuntimeMetadata? {
        val file = paths.metadataFile
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<RuntimeMetadata>(file.readText()) }.getOrNull()
    }

    private fun writeMetadata(
        environment: File,
        metadata: RuntimeMetadata,
    ) {
        writeFileReplacing(File(environment, RuntimePaths.METADATA_FILE_NAME), json.encodeToString(metadata))
    }

    private fun activate(
        report: InstallReporter,
        staging: File,
    ) {
        report.step(InstallStep.Verify, null, null, MessageKey.ProgressActivating)
        activateRuntimeEnvironment(
            active = paths.environment,
            staging = staging,
            rollback = paths.rollback,
        )
        report.step(InstallStep.Verify, 1f, null, MessageKey.ProgressDone)
    }

    private fun runnerFor(
        proot: EmbeddedCommandSuite.Paths,
        rootfs: File,
        report: InstallReporter,
        home: File,
    ): GuestRunner {
        home.mkdirs()
        val env = ProotCommands.installEnvironment(proot.environment(), paths.prootTmp)
        return GuestRunner(paths, proot, rootfs, home, env, report, killPid = killPid)
    }

    /** Disposable guest /root for package/probe commands, so installs never edit the user's home. */
    private fun installHome(staging: File): File = File(staging, "install-home").apply { mkdirs() }

    private fun writeShim(rootfs: File) {
        File(rootfs, SHIM_GUEST_PATH.removePrefix("/")).apply {
            parentFile?.mkdirs()
            writeText(shimAsset())
        }
    }

    /**
     * Copies the previous /root (legacy layout, inside the environment) into the persistent home,
     * or the fresh Alpine skeleton, the first time. Runs before activation, while the old
     * environment is still in place. Never overwrites a home that already has content.
     */
    private fun seedHome(rootfs: File) {
        if (paths.home.list()?.isNotEmpty() == true) return
        val legacy = File(paths.rootfs, "root")
        val skeleton = File(rootfs, "root")
        val source = listOf(legacy, skeleton).firstOrNull { it.isDirectory } ?: return
        copyTreeNoFollow(source, paths.home)
    }

    private fun configureRootfs(rootfs: File) {
        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").apply {
            mkdirs()
            setWritable(true, false)
            setExecutable(true, false)
        }
        ResolvConf.write(rootfs, dnsProvider.dnsServers())
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        // The compact Alpine archive omits a SONAME symlink that apk loads by name.
        val libApk = File(rootfs, "usr/lib/libapk.so.3")
        if (!libApk.exists() && File(rootfs, "usr/lib/libapk.so.3.0.0").isFile) {
            Os.symlink("libapk.so.3.0.0", libApk.absolutePath)
        }
    }

    /** Runs `/bin/sh -lc` commands inside one rootfs, streaming output to the install log. */
    internal class GuestRunner(
        private val paths: RuntimePaths,
        private val proot: EmbeddedCommandSuite.Paths,
        private val rootfs: File,
        private val guestHome: File,
        private val environment: Map<String, String>,
        private val report: InstallReporter,
        private val procRoot: File = File("/proc"),
        private val killPid: (Long) -> Unit = { pid -> android.os.Process.killProcess(pid.toInt()) },
    ) : AgentCommandRunner {
        /**
         * Returns the exit code. When [timeoutMinutes] passes, or the calling thread is interrupted,
         * the whole PRoot tree is killed and the call throws only after the process has exited.
         */
        fun run(
            script: String,
            timeoutMinutes: Long = DEFAULT_TIMEOUT_MINUTES,
            observe: (String) -> Unit = {},
        ): Int {
            val apkCache = File(paths.cache, "apk").apply { mkdirs() }
            File(rootfs, "var/cache/apk").mkdirs()
            val command =
                ProotCommands.guestShellCommand(
                    proot = proot.proot,
                    rootfs = rootfs,
                    home = guestHome,
                    extraBinds = listOf("${apkCache.absolutePath}:/var/cache/apk"),
                    script = script,
                )
            val process = start(command, redirectToLog = true)
            val pump =
                GuestOutputPump(
                    input = process.inputStream,
                    childAlive = { process.isAlive },
                    onLine = { line ->
                        report.log(line)
                        observe(line)
                        runCatching { paths.installLog.appendText(line + "\n") }
                    },
                )
            pump.start()
            // The teardown deadline starts inside awaitAndTearDown, after the command has finished.
            pump.awaitAndTearDown(
                awaitCommand = { awaitExit(process, timeoutMinutes, TimeUnit.MINUTES) },
                teardownBudgetNanos = OUTPUT_JOIN_MILLIS * 1_000_000L,
                timeoutFailure = {
                    IllegalStateException("Command timed out after $timeoutMinutes minutes: ${script.take(120)}")
                },
                readerFailure = { cause ->
                    IllegalStateException("Output reader failed: ${cause.message}", cause)
                },
            )
            return process.exitValue()
        }

        /**
         * Like [run], but a non-zero exit fails the step. When the output shows the mirrors were
         * unreachable, the reason says so instead of the generic message, and stays recoverable.
         */
        override fun mustSucceed(
            script: String,
            failure: MessageKey,
            failureText: String,
            timeoutMinutes: Long,
        ) {
            // Lines arrive on the output reader thread. The reader may still be running (abandoned on a
            // deadline), so the consumer works on a thread-safe snapshot, not the live list.
            val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
            val exit = run(script, timeoutMinutes) { line -> lines += line }
            if (exit == 0) return
            val snapshot = synchronized(lines) { lines.toList() }
            // Mirror problems are the common cause on a phone; name them instead of a generic failure.
            val unreachable = snapshot.any(NetworkFailure::isNetworkOutput)
            val key = if (unreachable) MessageKey.MirrorsUnreachable else failure
            val text = if (unreachable) NetworkFailure.MESSAGE else failureText
            throw UserFacingException(key, "$text (exit $exit)", detail = snapshot.takeLast(MAX_DETAIL_LINES).joinToString("\n"))
        }

        /** Captures stdout of a short, quiet guest command. Timeouts kill the tree, like [run]. */
        override fun output(script: String): String {
            val capture = File.createTempFile("verify-", ".out", paths.logs)
            try {
                val command =
                    ProotCommands.guestShellCommand(proot.proot, rootfs, guestHome, emptyList(), script)
                val process = start(command, redirectToLog = false, out = capture)
                require(awaitExit(process, VERIFY_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                    "Verification timed out: $script"
                }
                require(process.exitValue() == 0) { "Verification failed: $script" }
                return capture.readText()
            } finally {
                capture.delete()
            }
        }

        /**
         * Waits for [process]. If it does not exit in time, or waiting is interrupted, kills its whole
         * tree first, so no guest process is left writing into a directory the caller then deletes.
         */
        private fun awaitExit(
            process: Process,
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            var exited = false
            try {
                exited = process.waitFor(timeout, unit)
                return exited
            } finally {
                if (!exited) {
                    // Owned launchers for this rootfs (uid + argv guarded by the scanner). The start time
                    // comes from /proc, so a recycled pid is never signalled. terminateProcessTree waits
                    // for the kills to land before returning.
                    val scanner =
                        ProcessScanner(
                            procRoot = procRoot,
                            myUid = android.os.Process.myUid(),
                            myPid = android.os.Process.myPid().toLong(),
                        )
                    val roots = scanner.ownedRoots(proot.proot.absolutePath, rootfs.absolutePath)
                    terminateProcessTree(process, scanner, roots, killPid)
                }
            }
        }

        private fun start(
            command: List<String>,
            redirectToLog: Boolean,
            out: File? = null,
        ): Process =
            ProcessBuilder(command)
                .directory(paths.root)
                .redirectErrorStream(true)
                .apply {
                    if (out != null) redirectOutput(out)
                    else if (!redirectToLog) redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))

                    environment().clear()
                    environment().putAll(this@GuestRunner.environment)
                }
                .start()
    }

    companion object {
        /** Alpine packages. node-pty in the T3 bundle is already built for musl, so no toolchain. */
        val APK_PACKAGES =
            listOf(
                "nodejs", "npm", "bash", "git", "curl", "ripgrep", "openssh-client",
                "ca-certificates", "libstdc++", "python3", "gcompat",
            ).joinToString(" ")

        /** Extra packages needed to rebuild OpenChamber's node-pty in the guest. */
        val NATIVE_BUILD_PACKAGES = listOf("make", "g++", "linux-headers")

        fun apkPackages(needsNativeBuild: Boolean): String =
            if (!needsNativeBuild) {
                APK_PACKAGES
            } else {
                (APK_PACKAGES.split(" ") + NATIVE_BUILD_PACKAGES).distinct().joinToString(" ")
            }

        internal fun verifyCursorSdkRuntime(
            runner: AgentCommandRunner,
            rootfs: File,
        ) {
            if (!File(rootfs, "opt/t3/node_modules/@cursor/sdk").isDirectory) return
            runner.mustSucceed(
                "set -e; export PATH=${ProotCommands.GUEST_PATH}; " +
                    "apk info -e gcompat >/dev/null 2>&1 || " +
                    "apk --cache-dir /var/cache/apk add gcompat",
                MessageKey.PackagesFailed,
                "Unable to install the compatibility libraries Cursor SDK needs.",
                timeoutMinutes = APK_TIMEOUT_MINUTES,
            )
            val smoke = "$T3_GUEST/cursor-sdk-smoke.cjs"
            val script =
                if (File(rootfs, smoke.removePrefix("/")).isFile) {
                    "node $smoke"
                } else {
                    "node -e \"const base = '$T3_GUEST/node_modules/@cursor/sdk-linux-' + process.arch + '/vendor/'; " +
                        "require(base + 'tree-sitter'); require(base + 'tree-sitter-bash');\""
                }
            runner.mustSucceed(
                "set -e; $script",
                MessageKey.ServerInstallFailed,
                "Cox's Cursor SDK does not work in the guest.",
                timeoutMinutes = VERIFY_TIMEOUT_MINUTES,
            )
        }

        /** How many trailing output lines travel with a failure, for the technical detail. */
        const val MAX_DETAIL_LINES = 40

        const val T3_GUEST = "/opt/t3"
        const val T3_CLI_GUEST = "/opt/t3/bin.mjs"
        const val T3_BUNDLE_INFO_GUEST = "/opt/t3/ANDROID-BUNDLE.json"
        const val PTY_MODULE_GUEST = "/opt/t3/node_modules/node-pty"
        const val OPENCHAMBER_MODULE_GUEST = "/usr/local/lib/node_modules/@openchamber/web"
        const val SHIM_GUEST_PATH = "/opt/cocode/android-shim.cjs"
        const val PTY_SMOKE_GUEST_PATH = "/opt/cocode/pty-smoke.cjs"
        const val OPENCHAMBER_PTY_SMOKE_GUEST_PATH = "/opt/cocode/openchamber-pty-smoke.cjs"

        const val DEFAULT_TIMEOUT_MINUTES = 20L
        const val APK_TIMEOUT_MINUTES = 20L
        const val NPM_TIMEOUT_MINUTES = 30L
        const val PTY_SMOKE_TIMEOUT_MINUTES = 2L
        const val VERIFY_TIMEOUT_MINUTES = 2L

        /** OpenChamber's npm package requires at least this Node version. */
        const val OPENCHAMBER_MIN_NODE = "24.14.0"

        /** Free space an update needs beyond the copy: extracted bundles and agent packages grow the tree. */
        const val UPDATE_FREE_SPACE_MARGIN_BYTES = 512L * 1024 * 1024

        /**
         * Spawns `/bin/sh -c 'echo PTYOK'` through the bundled node-pty and requires the output and
         * exit 0. Its own timer bounds the run so a hung PTY cannot stall the install.
         */
        val PTY_SMOKE_SCRIPT =
            """
            const pty = require('$PTY_MODULE_GUEST');
            let out = '';
            const timer = setTimeout(() => { console.error('PTY smoke timed out: ' + out); process.exit(1); }, 20000);
            const term = pty.spawn('/bin/sh', ['-c', 'echo PTYOK'], { cols: 80, rows: 24, cwd: '/root', env: process.env });
            term.onData((data) => { out += data; });
            term.onExit(({ exitCode }) => {
              clearTimeout(timer);
              if (exitCode === 0 && out.includes('PTYOK')) { console.log('PTYOK'); process.exit(0); }
              console.error('PTY smoke failed: exit ' + exitCode + ' output ' + out);
              process.exit(1);
            });
            """.trimIndent() + "\n"

        private val VERSION_PATTERN = Regex("""\d+(?:\.\d+)+""")

        /** First dotted version in a tool's output: `opencode v2.2.5` -> `2.2.5`, `v24.18.1` -> `24.18.1`. */
        fun extractVersion(output: String): String = VERSION_PATTERN.find(output)?.value ?: output.trim().removePrefix("v")

        /** Dotted numeric comparison of the versions found in each string; missing segments count as 0. */
        fun compareVersions(
            left: String,
            right: String,
        ): Int {
            val a = extractVersion(left).split('.').map { it.toIntOrNull() ?: 0 }
            val b = extractVersion(right).split('.').map { it.toIntOrNull() ?: 0 }
            for (index in 0 until maxOf(a.size, b.size)) {
                val diff = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 })
                if (diff != 0) return diff
            }
            return 0
        }

        /**
         * `npm i -g` line for [specs]. [versions] pins an agent when an update knows its target;
         * otherwise npm takes the latest.
         */
        fun agentInstallScript(
            specs: List<AgentSpec>,
            versions: Map<String, String> = emptyMap(),
        ): String {
            val packages =
                specs.map { spec ->
                    versions[spec.id]?.takeIf(String::isNotBlank)?.let { "${spec.npmPackage}@$it" } ?: spec.npmPackage
                }
            return "set -e; npm i -g --no-fund --no-audit ${packages.joinToString(" ")}"
        }

        /** Bin link names an agent package owns. OpenCode ships a second compatibility binary. */
        fun agentBinNames(spec: AgentSpec): List<String> =
            buildList {
                add(spec.bin)
                if (spec.id == "opencode") add("opencode2")
            }

        /** Removes the given agents' packages and bin links from [rootfs] (see the instance method). */
        internal fun removeAgentPackages(
            rootfs: File,
            specs: List<AgentSpec>,
        ) {
            for (spec in specs) {
                deleteTreeOrThrow(File(rootfs, "usr/local/lib/node_modules/${spec.npmPackage}"))
                for (bin in agentBinNames(spec)) deleteTreeOrThrow(File(rootfs, "usr/local/bin/$bin"))
            }
        }
    }
}
