package io.cocode.app.runtime.install

import io.cocode.app.runtime.RuntimePaths
import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.manifest.AgentSpec
import io.cocode.app.runtime.manifest.OpenChamberSpec
import io.cocode.app.runtime.manifest.RuntimeArchitecture
import io.cocode.app.runtime.manifest.RuntimeManifest
import io.cocode.app.runtime.manifest.T3Spec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RuntimeInstallerImplMigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
            openchamber = OpenChamberSpec(),
            agents = listOf(AgentSpec("opencode", "@opencode/cli", "opencode", "OpenCode")),
            architectures =
                mapOf(
                    "arm64-v8a" to
                        RuntimeArchitecture(
                            alpineUrl = "https://example.com/alpine.tar.gz",
                            alpineSha256 = "a".repeat(64),
                        ),
                ),
        )

    private fun installer(paths: RuntimePaths): RuntimeInstallerImpl =
        RuntimeInstallerImpl(
            paths = paths,
            abi = "arm64-v8a",
            manifest = manifest,
            suite = { error("not used by installedRuntime") },
            downloader = VerifiedRuntimeDownloader(),
            shimAsset = { "" },
        )

    private fun environment(): Pair<RuntimePaths, File> {
        val paths = RuntimePaths(temporaryFolder.newFolder("runtime"))
        val rootfs = paths.rootfs.apply { mkdirs() }
        return paths to rootfs
    }

    private fun t3Assets(rootfs: File) {
        File(rootfs, "opt/t3/bin.mjs").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        File(rootfs, "opt/t3/client/index.html").apply {
            parentFile?.mkdirs()
            writeText("")
        }
    }

    private fun openChamberAssets(rootfs: File) {
        File(rootfs, "usr/local/lib/node_modules/@openchamber/web/bin/cli.js").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        File(rootfs, "usr/local/lib/node_modules/@openchamber/web/dist/index.html").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        File(rootfs, "usr/local/lib/node_modules/@openchamber/web/node_modules/node-pty").mkdirs()
        File(rootfs, "usr/local/lib/node_modules/@openchamber/web/package.json").writeText(
            """{"name":"@openchamber/web","version":"2.5.3"}""",
        )
    }

    private fun writeLegacyMetadata(paths: RuntimePaths) {
        paths.metadataFile.writeText(
            """
            {"runtimeVersion":"r","alpineVersion":"3.24.1","abi":"arm64-v8a","port":3773,
             "t3Version":"0.1.6-pw.5","agents":{},"installedAt":1}
            """.trimIndent(),
        )
    }

    @Test
    fun `legacy metadata is migrated to an explicit T3 server map idempotently`() {
        val (paths, rootfs) = environment()
        t3Assets(rootfs)
        writeLegacyMetadata(paths)

        val first = installer(paths).installedRuntime()
        assertNotNull(first)
        assertEquals(mapOf(ServerKind.T3 to "0.1.6-pw.5"), first?.metadata?.recordedServerVersions())
        val persisted = Json.decodeFromString(RuntimeMetadata.serializer(), paths.metadataFile.readText())
        assertEquals(2, persisted.schemaVersion)
        assertEquals(mapOf("t3" to "0.1.6-pw.5"), persisted.servers)

        val second = installer(paths).installedRuntime()
        assertEquals(first?.metadata, second?.metadata)
    }

    @Test
    fun `an OpenChamber-only environment is detected without opt t3`() {
        val (paths, rootfs) = environment()
        openChamberAssets(rootfs)
        File(rootfs, "usr/local/lib/node_modules/@opencode/cli").mkdirs()
        File(rootfs, "usr/local/bin/opencode").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        paths.metadataFile.writeText(
            """
            {"schemaVersion":2,"runtimeVersion":"r","alpineVersion":"3.24.1","abi":"arm64-v8a","port":3773,
             "t3Version":"0.1.6-pw.5","servers":{"openchamber":"2.5.3"},"agents":{},"installedAt":1}
            """.trimIndent(),
        )

        val runtime = installer(paths).installedRuntime()

        assertEquals(mapOf(ServerKind.OpenChamber to "2.5.3"), runtime?.metadata?.recordedServerVersions())
        assertEquals(mapOf("openchamber" to "2.5.3"), runtime?.metadata?.servers)
    }
}
