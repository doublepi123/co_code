package io.cocode.app.runtime.install

import android.system.Os
import io.cocode.app.runtime.InstallStep
import io.cocode.app.runtime.MessageKey
import io.cocode.app.runtime.RuntimePaths
import io.cocode.app.runtime.UserFacingException
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.TimeUnit

/** Bounded wait for a guest command's output reader after its stream is closed. */
internal const val OUTPUT_JOIN_MILLIS = 2_000L

/**
 * Builds the Alpine + Node + T3 Code + agent CLI environment in staging and activates it. Each guest
 * command's output is streamed to [InstallReporter.log] and to `logs/install.log`.
 *
 * The active environment is never touched until the staged one is complete and verified: the swap in
 * [activateRuntimeEnvironment] is the only mutation of it. The guest `/root` lives outside the
 * environment (see [RuntimePaths.home]), so a rebuild does not delete user data.
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

    /** Full install: Alpine, packages, T3 Code, pty check, the selected agents, verify, activate. */
    suspend fun install(
        report: InstallReporter,
        proot: EmbeddedCommandSuite.Paths,
        agents: Set<String>,
    ): InstalledRuntime =
        withContext(Dispatchers.IO) {
            val selected = requireAgents(agents)
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

                val runner = runnerFor(proot, rootfs, report)

                report.step(InstallStep.InstallPackages, null, "apk add nodejs npm git ...", MessageKey.ProgressInstallingPackages)
                // set -e: any failing command fails the step, even inside a chain.
                runner.mustSucceed(
                    "set -e; export PATH=${ProotCommands.GUEST_PATH}; " +
                        "apk --cache-dir /var/cache/apk add $APK_PACKAGES; " +
                        "update-ca-certificates",
                    MessageKey.PackagesFailed,
                    "Unable to install Linux packages. Check the network and retry.",
                    timeoutMinutes = APK_TIMEOUT_MINUTES,
                )

                // Shim before npm: install-time scripts also run under node and benefit from it.
                writeShim(rootfs)

                val release = resolveRelease()
                installServer(report, rootfs, release)
                verifyPty(runner, rootfs)

                report.step(InstallStep.InstallAgents, null, selected.joinToString(" "), MessageKey.ProgressInstallingAgents)
                installAgentPackages(runner, selected)
                val agentVersions = verifyAgents(runner, selected)

                report.step(InstallStep.Verify, null, "node, t3, agents", MessageKey.ProgressVerifying)
                val nodeVersion = nodeVersion(runner)

                val metadata =
                    RuntimeMetadata(
                        runtimeVersion = manifest.runtimeVersion,
                        alpineVersion = manifest.alpineVersion,
                        abi = abi,
                        port = manifest.port,
                        t3Version = release.version,
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
     * Updates T3 Code and the selected agents of the active environment to [versions] without a new
     * Alpine base. The environment is copied into staging (symlinks and modes kept), the T3 bundle is
     * replaced, changed agent packages are removed and installed again there, then the same
     * verification and swap as [install] run. Any failure deletes staging; the active environment is
     * untouched.
     */
    suspend fun update(
        report: InstallReporter,
        proot: EmbeddedCommandSuite.Paths,
        versions: PackageVersions,
    ): InstalledRuntime =
        withContext(Dispatchers.IO) {
            val current =
                checkNotNull(installedMetadataOrNull()) { "No active runtime environment to update" }
            val selected = requireAgents(versions.agents.keys)
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

                // npm's in-place upgrade breaks PRoot's faked hard links (see removeAgentPackages), so
                // packages that change are removed host-side and installed again from scratch.
                removeAgentPackages(rootfs, agentsToReplace(selected, current.agents, versions.agents))
                ResolvConf.write(rootfs, dnsProvider.dnsServers())

                val runner = runnerFor(proot, rootfs, report)
                writeShim(rootfs)
                installServer(
                    report,
                    rootfs,
                    releaseFor(versions.t3),
                )
                verifyPty(runner, rootfs)

                report.step(InstallStep.InstallAgents, null, null, MessageKey.ProgressInstallingAgents)
                installAgentPackages(
                    runner,
                    selected.filter { id -> current.agents[id] != versions.agents[id] }.toSet(),
                    versions.agents,
                )
                val agentVersions = verifyAgents(runner, selected)

                report.step(InstallStep.Verify, null, "node, t3, agents", MessageKey.ProgressVerifying)
                val nodeVersion = nodeVersion(runner)
                val metadata =
                    RuntimeMetadata(
                        runtimeVersion = current.runtimeVersion,
                        alpineVersion = current.alpineVersion,
                        abi = abi,
                        port = manifest.port,
                        t3Version = versions.t3,
                        agents = agentVersions,
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

    /**
     * Applies an agent selection to the active environment in place. The controller stops the server
     * first. Removed agents are deleted host-side (the copy-to-staging update path is unnecessary for
     * a few packages), new ones are installed from npm, then every selected agent is verified and the
     * metadata is rewritten atomically. A failure before the metadata write leaves the previous
     * record intact; the next install or selection change repairs the files.
     */
    suspend fun applyAgents(
        report: InstallReporter,
        proot: EmbeddedCommandSuite.Paths,
        selected: Set<String>,
    ): InstalledRuntime =
        withContext(Dispatchers.IO) {
            val current = checkNotNull(installedMetadataOrNull()) { "No active runtime environment" }
            val wanted = requireAgents(selected)
            val rootfs = paths.rootfs
            check(rootfs.isDirectory) { "Active environment has no rootfs" }

            removeAgentPackages(rootfs, agentsToReplace(wanted, current.agents, current.agents))
            writeShim(rootfs)
            ResolvConf.write(rootfs, dnsProvider.dnsServers())

            val runner = runnerFor(proot, rootfs, report)
            report.step(InstallStep.InstallAgents, null, null, MessageKey.ProgressInstallingAgents)
            installAgentPackages(runner, wanted - current.agents.keys)
            report.step(InstallStep.Verify, null, null, MessageKey.ProgressVerifying)
            val versions = verifyAgents(runner, wanted)

            val metadata = current.copy(agents = versions)
            writeMetadata(paths.environment, metadata)
            InstalledRuntime(metadata, rootfs)
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
                    "Unable to read the T3 Code checksum list: ${error.message}",
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
        require(File(target, "bin.mjs").isFile) { "T3 Code bundle has no bin.mjs" }
        require(File(target, "client/index.html").isFile) { "T3 Code bundle has no client/index.html" }
        val info =
            runCatching {
                json.decodeFromString<T3BundleInfo>(File(target, "ANDROID-BUNDLE.json").readText())
            }.getOrNull() ?: error("T3 Code bundle has no readable ANDROID-BUNDLE.json")
        require(info.arch == arch) { "T3 Code bundle is for ${info.arch}, expected $arch" }
        require(info.version == version) { "T3 Code bundle is ${info.version}, expected $version" }
    }

    private fun verifyPty(
        runner: GuestRunner,
        rootfs: File,
    ) {
        require(File(rootfs, PTY_MODULE_GUEST.removePrefix("/")).isDirectory) {
            "T3 Code bundle has no node-pty module"
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

    /** Installs [ids] from npm at their latest versions, or at [versions] when a version is known. */
    private fun installAgentPackages(
        runner: GuestRunner,
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
        runner: GuestRunner,
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

    private fun spec(id: String): AgentSpec =
        requireNotNull(manifest.agent(id)) { "Unknown agent: $id" }

    private fun requireAgents(selected: Set<String>): Set<String> {
        if (selected.isEmpty()) throw UserFacingException(MessageKey.AgentsRequired, "Select at least one agent")
        selected.forEach(::spec)
        return selected
    }

    /**
     * Agents whose package must be deleted before npm installs again. With [targets] empty this is
     * the set that is no longer selected. Otherwise it is every selected agent whose installed
     * version differs from the target, plus every removed agent.
     */
    private fun agentsToReplace(
        selected: Set<String>,
        installed: Map<String, String>,
        targets: Map<String, String>,
    ): List<AgentSpec> {
        val changed =
            if (targets.isEmpty()) {
                selected.filter { installed[it] == null && false }
            } else {
                selected.filter { installed[it] != targets[it] }
            }
        val removed = installed.keys - selected
        return (changed + removed).distinct().mapNotNull { manifest.agent(it) }
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

    private fun resolveRelease(): T3Release = releaseFor(manifest.t3.version)

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
    ): GuestRunner {
        val env = ProotCommands.installEnvironment(proot.environment(), paths.prootTmp)
        return GuestRunner(paths, proot, rootfs, env, report, killPid = killPid)
    }

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
        private val environment: Map<String, String>,
        private val report: InstallReporter,
        private val procRoot: File = File("/proc"),
        private val killPid: (Long) -> Unit = { pid -> android.os.Process.killProcess(pid.toInt()) },
    ) {
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
                    home = paths.home,
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
        fun mustSucceed(
            script: String,
            failure: MessageKey,
            failureText: String,
            timeoutMinutes: Long = DEFAULT_TIMEOUT_MINUTES,
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
        fun output(script: String): String {
            val capture = File.createTempFile("verify-", ".out", paths.logs)
            try {
                val command =
                    ProotCommands.guestShellCommand(proot.proot, rootfs, paths.home, emptyList(), script)
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

    @Serializable
    internal data class T3BundleInfo(
        val version: String? = null,
        val arch: String? = null,
        val libc: String? = null,
    )

    companion object {
        /** Alpine packages. node-pty in the T3 bundle is already built for musl, so no toolchain. */
        val APK_PACKAGES =
            listOf(
                "nodejs", "npm", "bash", "git", "curl", "ripgrep", "openssh-client",
                "ca-certificates", "libstdc++", "python3",
            ).joinToString(" ")

        /** How many trailing output lines travel with a failure, for the technical detail. */
        const val MAX_DETAIL_LINES = 40

        const val T3_GUEST = "/opt/t3"
        const val T3_CLI_GUEST = "/opt/t3/bin.mjs"
        const val T3_BUNDLE_INFO_GUEST = "/opt/t3/ANDROID-BUNDLE.json"
        const val PTY_MODULE_GUEST = "/opt/t3/node_modules/node-pty"
        const val SHIM_GUEST_PATH = "/opt/cocode/android-shim.cjs"
        const val PTY_SMOKE_GUEST_PATH = "/opt/cocode/pty-smoke.cjs"

        const val DEFAULT_TIMEOUT_MINUTES = 20L
        const val APK_TIMEOUT_MINUTES = 20L
        const val NPM_TIMEOUT_MINUTES = 30L
        const val PTY_SMOKE_TIMEOUT_MINUTES = 2L
        const val VERIFY_TIMEOUT_MINUTES = 2L

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
