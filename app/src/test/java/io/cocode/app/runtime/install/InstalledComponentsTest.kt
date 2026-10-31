package io.cocode.app.runtime.install

import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.manifest.AgentSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class InstalledComponentsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun rootfs(): File = temporaryFolder.newFolder("rootfs")

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

    private fun openChamberAssets(rootfs: File, version: String = "2.5.3") {
        File(rootfs, "usr/local/lib/node_modules/@openchamber/web/bin/cli.js").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        File(rootfs, "usr/local/lib/node_modules/@openchamber/web/dist/index.html").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        File(rootfs, "usr/local/lib/node_modules/@openchamber/web/node_modules/node-pty").mkdirs()
        File(rootfs, "usr/local/lib/node_modules/@openchamber/web/package.json").apply {
            parentFile?.mkdirs()
            writeText("""{"name":"@openchamber/web","version":"$version"}""")
        }
    }

    private fun metadata(
        t3Version: String = "0.1.6-pw.5",
        servers: Map<String, String> = emptyMap(),
    ) = RuntimeMetadata(
        schemaVersion = 1,
        runtimeVersion = "r",
        alpineVersion = "3.24.1",
        abi = "arm64-v8a",
        port = 3773,
        t3Version = t3Version,
        servers = servers,
        installedAt = 1L,
    )

    @Test
    fun `legacy T3 metadata migrates to the T3 server while its bundle exists`() {
        val rootfs = rootfs()
        t3Assets(rootfs)
        val legacy = metadata()

        assertEquals(mapOf(ServerKind.T3 to "0.1.6-pw.5"), legacy.recordedServerVersions())
        assertEquals(mapOf(ServerKind.T3 to "0.1.6-pw.5"), installedServerVersions(rootfs, legacy))
    }

    @Test
    fun `legacy T3 metadata without the bundle is not treated as installed`() {
        val rootfs = rootfs()

        assertEquals(mapOf(ServerKind.T3 to "0.1.6-pw.5"), metadata().recordedServerVersions())
        assertTrue(installedServerVersions(rootfs, metadata()).isEmpty())
    }

    @Test
    fun `an OpenChamber-only map is reported without requiring opt t3`() {
        val rootfs = rootfs()
        openChamberAssets(rootfs)
        val onlyOpenChamber = metadata(servers = mapOf("openchamber" to "2.5.3"))

        assertEquals(
            mapOf(ServerKind.OpenChamber to "2.5.3"),
            installedServerVersions(rootfs, onlyOpenChamber),
        )
        assertFalse(ServerKind.T3 in installedServerVersions(rootfs, onlyOpenChamber))
    }

    @Test
    fun `both servers round-trip through the explicit map`() {
        val rootfs = rootfs()
        t3Assets(rootfs)
        openChamberAssets(rootfs)
        val both = metadata(servers = mapOf("t3" to "0.1.6-pw.5", "openchamber" to "2.5.3"))

        assertEquals(
            mapOf(ServerKind.T3 to "0.1.6-pw.5", ServerKind.OpenChamber to "2.5.3"),
            installedServerVersions(rootfs, both),
        )
    }

    @Test
    fun `an agent counts only when its package and bin link exist`() {
        val rootfs = rootfs()
        val codex = AgentSpec("codex", "@openai/codex", "codex", "Codex")

        assertFalse(InstalledComponents.agentFilesPresent(rootfs, codex))
        File(rootfs, "usr/local/lib/node_modules/@openai/codex").mkdirs()
        assertFalse(InstalledComponents.agentFilesPresent(rootfs, codex))
        File(rootfs, "usr/local/bin/codex").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        assertTrue(InstalledComponents.agentFilesPresent(rootfs, codex))
    }

    @Test
    fun `the OpenChamber version is read from its package manifest`() {
        val rootfs = rootfs()
        openChamberAssets(rootfs, version = "2.5.4")

        assertEquals("2.5.4", InstalledComponents.openChamberVersion(rootfs))
    }
}
