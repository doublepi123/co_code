package io.cocode.app.runtime.install

import io.cocode.app.runtime.manifest.AgentSpec
import io.cocode.app.runtime.manifest.RuntimeManifestReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentInstallationCoverageTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val cursor = AgentSpec("cursor", bin = "", displayName = "Cursor SDK", installation = "bundled")
    private val grok = AgentSpec("grok", "@xai-official/grok", "grok", "Grok")
    private val pi = AgentSpec("pi", "@earendil-works/pi-coding-agent", "pi", "Pi")

    @Test
    fun `packaged manifest exposes verified CLI package identities and bundled Cursor`() {
        val asset = listOf(File("src/main/assets/runtime-manifest.json"), File("app/src/main/assets/runtime-manifest.json"))
            .first { it.isFile }
        val manifest = RuntimeManifestReader(asset::readText).read()
        assertEquals(setOf("codex", "claude", "opencode", "grok", "pi", "cursor"), manifest.agents.map { it.id }.toSet())
        assertEquals("@xai-official/grok", manifest.agent("grok")!!.npmPackage)
        assertEquals("@earendil-works/pi-coding-agent", manifest.agent("pi")!!.npmPackage)
        assertEquals("bundled", manifest.agent("cursor")!!.installation)
        assertEquals("", manifest.agent("cursor")!!.npmPackage)
    }

    @Test
    fun `npm selection includes real Grok and Pi packages but never a fake Cursor package`() {
        val script = RuntimeEnvironmentInstaller.agentInstallScript(listOf(grok, cursor, pi))
        assertTrue(script.contains("@xai-official/grok"))
        assertTrue(script.contains("@earendil-works/pi-coding-agent"))
        assertFalse(script.contains("cursor"))
        assertEquals("true", RuntimeEnvironmentInstaller.agentInstallScript(listOf(cursor)))
        assertEquals(emptyList<String>(), RuntimeEnvironmentInstaller.agentBinNames(cursor))
        assertTrue("uv" in RuntimeEnvironmentInstaller.APK_PACKAGES.split(' '))
        assertTrue("unzip" in RuntimeEnvironmentInstaller.APK_PACKAGES.split(' '))
        assertTrue("bzip2" in RuntimeEnvironmentInstaller.APK_PACKAGES.split(' '))
    }

    @Test
    fun `bundled agent validation never admits arbitrary package or binary deletion`() {
        cursor.validate()
        assertThrows(IllegalArgumentException::class.java) { cursor.copy(id = "other").validate() }
        assertThrows(IllegalArgumentException::class.java) { cursor.copy(npmPackage = "package").validate() }
        assertThrows(IllegalArgumentException::class.java) { grok.copy(npmPackage = "package;unsafe").validate() }
        assertThrows(IllegalArgumentException::class.java) { grok.copy(bin = "../other").validate() }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeEnvironmentInstaller.agentInstallScript(listOf(pi), mapOf("pi" to "1.0.0;unsafe"))
        }
    }

    @Test
    fun `removing bundled selection preserves Cox and other agent packages`() {
        val rootfs = folder.newFolder("rootfs")
        val marker = File(rootfs, "usr/local/lib/node_modules/keep").apply { parentFile?.mkdirs(); writeText("keep") }
        val binary = File(rootfs, "usr/local/bin/keep").apply { parentFile?.mkdirs(); writeText("keep") }
        RuntimeEnvironmentInstaller.removeAgentPackages(rootfs, listOf(cursor))
        assertEquals("keep", marker.readText())
        assertEquals("keep", binary.readText())
    }

    @Test
    fun `bundled SDK availability requires an actual package in the Cox installation`() {
        val rootfs = folder.newFolder("rootfs")
        assertFalse(InstalledComponents.agentFilesPresent(rootfs, cursor))
        File(rootfs, "opt/t3/node_modules/@cursor/sdk/package.json").apply { parentFile?.mkdirs(); writeText("{}") }
        assertTrue(InstalledComponents.agentFilesPresent(rootfs, cursor))
    }

    @Test
    fun `retired cleanup removes only staged program assets and does not follow a home symlink`() {
        val rootfs = folder.newFolder("staged-rootfs")
        val home = folder.newFolder("persistent-home")
        val credentials = File(home, "credentials").apply { writeText("keep") }
        val retired = File(rootfs, "usr/local/lib/node_modules/@openchamber/web").apply { parentFile?.mkdirs() }
        java.nio.file.Files.createSymbolicLink(retired.toPath(), home.toPath())
        val config = File(rootfs, "etc/openchamber/policy.json").apply { parentFile?.mkdirs(); writeText("old") }
        val agent = File(rootfs, "usr/local/lib/node_modules/@opencode/cli/package.json").apply { parentFile?.mkdirs(); writeText("keep") }

        RuntimeEnvironmentInstaller.removeRetiredServerAssets(rootfs)

        assertFalse(java.nio.file.Files.exists(retired.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(config.exists())
        assertEquals("keep", credentials.readText())
        assertEquals("keep", agent.readText())
    }

    @Test
    fun `failed Cox activation restores a retired environment and its metadata atomically`() {
        val parent = folder.newFolder("runtime")
        val active = File(parent, "environment").apply { mkdirs() }
        val staging = File(parent, "environment.staging").apply { mkdirs() }
        val rollback = File(parent, "environment.rollback")
        File(active, "metadata.json").writeText("retired-record")
        File(active, "retired-program").writeText("old")
        File(staging, "metadata.json").writeText("cox-record")
        val credentials = File(parent, "home/keep").apply { parentFile?.mkdirs(); writeText("keep") }

        assertThrows(IllegalStateException::class.java) {
            activateRuntimeEnvironment(active, staging, rollback, moveDirectory = { source, target ->
                if (source == staging) error("activation failed")
                check(source.renameTo(target))
            })
        }

        assertEquals("retired-record", File(active, "metadata.json").readText())
        assertEquals("old", File(active, "retired-program").readText())
        assertEquals("keep", credentials.readText())
        assertFalse(rollback.exists())
    }
}
