package io.cocode.app.runtime.install

import io.cocode.app.runtime.InstallStep
import io.cocode.app.runtime.MessageKey
import io.cocode.app.runtime.RuntimePaths
import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.UserFacingException
import io.cocode.app.runtime.manifest.AgentSpec
import io.cocode.app.runtime.manifest.RuntimeArchitecture
import io.cocode.app.runtime.manifest.RuntimeManifest
import io.cocode.app.runtime.manifest.T3Spec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The real agent-selection apply flow, with a fake guest runner instead of PRoot. Adding an agent
 * must never delete or reinstall an agent that stays selected; deselection must remove exactly the
 * agents that were dropped, after the new set has been verified and committed.
 */
class AgentSelectionApplyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val codex = AgentSpec("codex", "@openai/codex", "codex", "Codex")
    private val opencode = AgentSpec("opencode", "@opencode/cli", "opencode", "OpenCode")

    private val manifest =
        RuntimeManifest(
            schemaVersion = 1,
            runtimeVersion = "r",
            alpineVersion = "3.24.1",
            port = 3773,
            t3 =
                T3Spec(
                    version = "0.1.14",
                ),
            agents = listOf(codex, opencode),
            architectures =
                mapOf(
                    "arm64-v8a" to
                        RuntimeArchitecture(
                            alpineUrl = "https://example.com/alpine.tar.gz",
                            alpineSha256 = "a".repeat(64),
                        ),
                ),
        )

    private lateinit var paths: RuntimePaths
    private lateinit var environment: File
    private lateinit var rootfs: File

    private val installer by lazy {
        RuntimeEnvironmentInstaller(
            paths = paths,
            abi = "arm64-v8a",
            manifest = manifest,
            downloader = VerifiedRuntimeDownloader(),
            shimAsset = { "" },
        )
    }

    private val report =
        object : InstallReporter {
            override fun step(
                step: InstallStep,
                progress: Float?,
                detail: String?,
                messageKey: MessageKey?,
            ) = Unit

            override fun log(line: String) = Unit
        }

    @Before
    fun setUp() {
        paths = RuntimePaths(temporaryFolder.newFolder("runtime"))
        environment = paths.environment.apply { mkdirs() }
        rootfs = paths.rootfs.apply { mkdirs() }
    }

    private fun current(
        agents: Map<String, String>,
        servers: Map<ServerKind, String> = emptyMap(),
    ): RuntimeMetadata =
        RuntimeMetadata(
            runtimeVersion = "r",
            alpineVersion = "3.24.1",
            abi = "arm64-v8a",
            port = 3773,
            t3Version = servers[ServerKind.T3] ?: "0.1.6-pw.5",
            servers = servers.mapKeys { (kind, _) -> kind.id },
            agents = agents,
            installedAt = 1L,
        )

    private fun seedT3Assets() {
        rootfs.resolve("opt/t3/bin.mjs").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        rootfs.resolve("opt/t3/client/index.html").apply {
            parentFile?.mkdirs()
            writeText("")
        }
    }

    private fun seedOpenChamberAssets() {
        rootfs.resolve("usr/local/lib/node_modules/@openchamber/web/bin/cli.js").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        rootfs.resolve("usr/local/lib/node_modules/@openchamber/web/dist/index.html").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        rootfs.resolve("usr/local/lib/node_modules/@openchamber/web/node_modules/node-pty").mkdirs()
    }

    /** Writes the active metadata the way a previous install/apply would have. */
    private fun seedMetadata(metadata: RuntimeMetadata) {
        File(environment, RuntimePaths.METADATA_FILE_NAME)
            .writeText(Json.encodeToString(RuntimeMetadata.serializer(), metadata))
    }

    private fun readMetadata(): RuntimeMetadata =
        Json.decodeFromString(
            RuntimeMetadata.serializer(),
            File(environment, RuntimePaths.METADATA_FILE_NAME).readText(),
        )

    private fun seedInstalled(spec: AgentSpec) {
        rootfs.resolve("usr/local/lib/node_modules/${spec.npmPackage}/bin").mkdirs()
        rootfs.resolve("usr/local/lib/node_modules/${spec.npmPackage}/bin/${spec.bin}").writeText("old")
        rootfs.resolve("usr/local/bin").mkdirs()
        RuntimeEnvironmentInstaller.agentBinNames(spec).forEach { bin ->
            rootfs.resolve("usr/local/bin/$bin").writeText("link")
        }
    }

    private fun packageExists(spec: AgentSpec): Boolean =
        rootfs.resolve("usr/local/lib/node_modules/${spec.npmPackage}").exists()

    private fun binExists(name: String): Boolean = rootfs.resolve("usr/local/bin/$name").exists()

    /** Records npm scripts, answers `--version`, and simulates a successful package install. */
    private class FakeRunner(
        private val outputs: Map<String, String>,
        private val rootfs: File,
        private val packages: Map<String, AgentSpec>,
        private val beforeInstall: () -> Unit = {},
        private val failInstall: Throwable? = null,
    ) : AgentCommandRunner {
        val scripts = mutableListOf<String>()

        override fun mustSucceed(
            script: String,
            failure: MessageKey,
            failureText: String,
            timeoutMinutes: Long,
        ) {
            scripts += script
            beforeInstall()
            failInstall?.let { throw it }
            packages.forEach { (packageName, spec) ->
                if (script.contains(packageName)) {
                    rootfs.resolve("usr/local/lib/node_modules/${spec.npmPackage}/bin").mkdirs()
                    rootfs.resolve("usr/local/lib/node_modules/${spec.npmPackage}/bin/${spec.bin}").writeText("new")
                    rootfs.resolve("usr/local/bin").mkdirs()
                    RuntimeEnvironmentInstaller.agentBinNames(spec).forEach { bin ->
                        rootfs.resolve("usr/local/bin/$bin").writeText("link")
                    }
                }
            }
        }

        override fun output(script: String): String {
            val bin = script.substringBefore(" --version")
            return outputs[bin] ?: error("unexpected verification command: $script")
        }
    }

    private fun runner(
        beforeInstall: () -> Unit = {},
        failInstall: Throwable? = null,
    ): FakeRunner =
        FakeRunner(
            outputs = mapOf("codex" to "codex-cli 1.0.0\n", "opencode" to "opencode v2.0.0\n"),
            rootfs = rootfs,
            packages = mapOf("@openai/codex" to codex, "@opencode/cli" to opencode),
            beforeInstall = beforeInstall,
            failInstall = failInstall,
        )

    @Test
    fun `adding an agent installs only the addition and keeps every installed package`() {
        seedInstalled(codex)
        seedMetadata(current(mapOf("codex" to "1.0.0")))
        val runner = runner()

        val metadata =
            installer.applyAgentSelection(
                report = report,
                environment = environment,
                rootfs = rootfs,
                current = current(mapOf("codex" to "1.0.0")),
                wanted = setOf("codex", "opencode"),
                runner = runner,
            )

        assertTrue("codex package must survive adding opencode", packageExists(codex))
        assertTrue("codex bin must survive adding opencode", binExists("codex"))
        assertTrue("opencode must be installed", packageExists(opencode))
        assertEquals(1, runner.scripts.size)
        assertFalse("a selected agent must never be passed to npm again", runner.scripts.single().contains("@openai/codex"))
        assertTrue(runner.scripts.single().contains("@opencode/cli"))
        assertEquals(mapOf("codex" to "1.0.0", "opencode" to "2.0.0"), metadata.agents)
        assertEquals(metadata.agents, readMetadata().agents)
    }

    @Test
    fun `deselecting removes exactly the dropped agent after the new set is committed`() {
        seedInstalled(codex)
        seedInstalled(opencode)
        seedMetadata(current(mapOf("codex" to "1.0.0", "opencode" to "2.0.0")))
        val runner = runner()

        val metadata =
            installer.applyAgentSelection(
                report = report,
                environment = environment,
                rootfs = rootfs,
                current = current(mapOf("codex" to "1.0.0", "opencode" to "2.0.0")),
                wanted = setOf("codex"),
                runner = runner,
            )

        assertTrue(packageExists(codex))
        assertTrue(binExists("codex"))
        assertFalse("deselected package must be removed", packageExists(opencode))
        assertFalse("deselected bin must be removed", binExists("opencode"))
        assertTrue("no npm work is needed when only removing", runner.scripts.isEmpty())
        assertEquals(mapOf("codex" to "1.0.0"), metadata.agents)
        assertEquals(mapOf("codex" to "1.0.0"), readMetadata().agents)
    }

    @Test
    fun `a failed addition leaves the installed selection and its record intact`() {
        seedInstalled(codex)
        val before = current(mapOf("codex" to "1.0.0"))
        seedMetadata(before)
        val failure = UserFacingException(MessageKey.AgentInstallFailed, "npm failed", detail = "EAI_AGAIN")
        val runner = runner(failInstall = failure)

        assertThrows(UserFacingException::class.java) {
            installer.applyAgentSelection(
                report = report,
                environment = environment,
                rootfs = rootfs,
                current = before,
                wanted = setOf("codex", "opencode"),
                runner = runner,
            )
        }

        assertTrue("codex package must survive a failed addition", packageExists(codex))
        assertTrue(binExists("codex"))
        assertFalse("a failed addition must not leave half an install", packageExists(opencode))
        assertEquals("the old metadata must still describe exactly what works", before.agents, readMetadata().agents)
    }

    @Test
    fun `a lost server asset fails the apply and leaves the active metadata unchanged`() {
        seedInstalled(codex)
        seedT3Assets()
        seedOpenChamberAssets()
        val before =
            current(
                agents = mapOf("codex" to "1.0.0"),
                servers = mapOf(ServerKind.T3 to "0.1.6-pw.5", ServerKind.OpenChamber to "2.5.3"),
            )
        seedMetadata(before)
        val runner =
            runner(
                beforeInstall = {
                    // Simulate npm pruning the other server's package during an agent install.
                    rootfs
                        .resolve("usr/local/lib/node_modules/@openchamber/web/node_modules/node-pty")
                        .deleteRecursively()
                },
            )

        assertThrows(UserFacingException::class.java) {
            installer.applyAgentSelection(
                report = report,
                environment = environment,
                rootfs = rootfs,
                current = before,
                wanted = setOf("codex", "opencode"),
                runner = runner,
                expectedServers = before.recordedServerVersions(),
            )
        }

        assertEquals(
            "a lost expected server must fail, never narrow the committed record",
            before.servers,
            readMetadata().servers,
        )
        assertEquals(before.agents, readMetadata().agents)
        assertTrue("the unrelated T3 assets stay in staging", rootfs.resolve("opt/t3/bin.mjs").isFile)
    }

    @Test
    fun `a selected agent missing after package work is restored at its recorded version`() {
        seedInstalled(codex)
        seedMetadata(current(mapOf("codex" to "1.0.0")))
        var removed = false
        val runner =
            runner(
                beforeInstall = {
                    // Defensive simulation of any loss of the other agent's files during an install.
                    // This is not a reproduced npm behavior; a true addition keeps the agent.
                    if (!removed) {
                        removed = true
                        rootfs.resolve("usr/local/lib/node_modules/@openai/codex").deleteRecursively()
                        rootfs.resolve("usr/local/bin/codex").delete()
                    }
                },
            )

        val metadata =
            installer.applyAgentSelection(
                report = report,
                environment = environment,
                rootfs = rootfs,
                current = current(mapOf("codex" to "1.0.0")),
                wanted = setOf("codex", "opencode"),
                runner = runner,
            )

        assertEquals(2, runner.scripts.size)
        assertTrue(
            "the lost agent is reinstalled at the version the metadata recorded",
            runner.scripts[1].contains("@openai/codex@1.0.0"),
        )
        assertTrue("codex package restored", packageExists(codex))
        assertTrue("codex bin restored", binExists("codex"))
        assertTrue(packageExists(opencode))
        assertEquals(mapOf("codex" to "1.0.0", "opencode" to "2.0.0"), metadata.agents)
        assertEquals(metadata.agents, readMetadata().agents)
    }

    @Test
    fun `a leftover package for an added agent is cleaned before npm installs it`() {
        seedInstalled(codex)
        seedInstalled(opencode)
        seedMetadata(current(mapOf("codex" to "1.0.0")))
        var leftoverSeenAtInstall: Boolean? = null
        val runner =
            runner(
                beforeInstall = {
                    leftoverSeenAtInstall = rootfs.resolve("usr/local/lib/node_modules/@opencode/cli").exists()
                },
            )

        installer.applyAgentSelection(
            report = report,
            environment = environment,
            rootfs = rootfs,
            current = current(mapOf("codex" to "1.0.0")),
            wanted = setOf("codex", "opencode"),
            runner = runner,
        )

        assertEquals("the stale tree must be gone before npm runs", false, leftoverSeenAtInstall)
        assertTrue(packageExists(codex))
        assertTrue(packageExists(opencode))
    }
}
