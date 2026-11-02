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
    @Test
    fun `npm Cox identity replaces bundle identity without opt t3`() {
        val rootfs = rootfs()
        for (path in listOf(InstalledComponents.COX_CLI, InstalledComponents.COX_UI)) {
            File(rootfs, path.removePrefix("/")).apply { parentFile?.mkdirs(); writeText("") }
        }
        val module = File(rootfs, InstalledComponents.COX_MODULE.removePrefix("/"))
        File(module, "node_modules/node-pty").mkdirs()
        File(module, "package.json").writeText("""{"name":"@doublepi123/cox","version":"0.1.14"}""")
        assertTrue(InstalledComponents.isCoxNpmInstall(rootfs))
        assertTrue(InstalledComponents.isCoxServerBundle(rootfs))
        assertFalse(InstalledComponents.isLegacyT3Bundle(rootfs, "0.1.14"))
        assertEquals(InstalledComponents.COX_MODULE, InstalledComponents.coxModuleGuest(rootfs))
        assertEquals(mapOf(ServerKind.T3 to "0.1.14"), installedServerVersions(rootfs, metadata(t3Version = "0.1.14")))
        File(module, "package.json").writeText("""{"name":"other","version":"0.1.14"}""")
        assertFalse(InstalledComponents.isCoxNpmInstall(rootfs))
        assertFalse(InstalledComponents.serverAssetsPresent(rootfs, ServerKind.T3))
    }
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

    private fun bundle(rootfs: File, json: String) {
        File(rootfs, InstalledComponents.T3_BUNDLE_INFO).apply {
            parentFile?.mkdirs()
            writeText(json)
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

    @Test
    fun `a cox bundle is identified by its server field`() {
        val rootfs = rootfs()
        bundle(rootfs, """{"version":"0.1.1","arch":"arm64","libc":"musl","server":"cox"}""")

        val identity = InstalledComponents.t3Bundle(rootfs)!!
        assertEquals("0.1.1", identity.version)
        assertEquals("arm64", identity.arch)
        assertEquals("musl", identity.libc)
        assertEquals("cox", identity.server)
        assertTrue(InstalledComponents.isCoxServerBundle(rootfs))
        assertFalse(InstalledComponents.isLegacyT3Bundle(rootfs, "0.1.1"))
    }

    @Test
    fun `a bundle without the cox server field is the legacy t3 build`() {
        val rootfs = rootfs()
        bundle(rootfs, """{"version":"0.1.6-pw.6","arch":"arm64","libc":"musl"}""")

        assertFalse(InstalledComponents.isCoxServerBundle(rootfs))
        assertTrue(InstalledComponents.isLegacyT3Bundle(rootfs, "0.1.6-pw.6"))
    }

    @Test
    fun `an extracted bundle directory is read directly, not as a rootfs`() {
        // The installer verifies the freshly extracted `opt/t3` directory itself.
        val extracted = temporaryFolder.newFolder("extracted-opt-t3")
        File(extracted, "ANDROID-BUNDLE.json").writeText(
            """{"version":"0.1.1","arch":"arm64","libc":"musl","server":"cox"}""",
        )

        val identity = InstalledComponents.bundleInDirectory(extracted)!!
        assertEquals("cox", identity.server)
        assertEquals("0.1.1", identity.version)
        assertEquals(null, InstalledComponents.t3Bundle(extracted))
    }

    @Test
    fun `without bundle metadata the legacy version suffix decides`() {
        val rootfs = rootfs()

        assertTrue(InstalledComponents.isLegacyT3Bundle(rootfs, "0.1.6-pw.6"))
        assertFalse(InstalledComponents.isLegacyT3Bundle(rootfs, "0.1.1"))
        assertFalse(InstalledComponents.isLegacyT3Bundle(rootfs, null))
    }
}
