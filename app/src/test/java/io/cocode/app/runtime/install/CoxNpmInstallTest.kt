package io.cocode.app.runtime.install

import io.cocode.app.runtime.InstallStep
import io.cocode.app.runtime.MessageKey
import io.cocode.app.runtime.RuntimePaths
import io.cocode.app.runtime.manifest.AgentSpec
import io.cocode.app.runtime.manifest.RuntimeArchitecture
import io.cocode.app.runtime.manifest.RuntimeManifest
import io.cocode.app.runtime.manifest.T3Spec
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CoxNpmInstallTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val report = object : InstallReporter {
        override fun step(step: InstallStep, progress: Float?, detail: String?, messageKey: MessageKey?) = Unit
        override fun log(line: String) = Unit
    }

    private fun installer() = RuntimeEnvironmentInstaller(
        paths = RuntimePaths(folder.newFolder()),
        abi = "x86_64",
        manifest = RuntimeManifest(
            schemaVersion = 1,
            runtimeVersion = "r",
            alpineVersion = "3.24.1",
            port = 3773,
            t3 = T3Spec(version = "0.1.14"),
            agents = listOf(AgentSpec("codex", "@openai/codex", "codex", "Codex")),
            architectures = mapOf("x86_64" to RuntimeArchitecture("https://example.com/alpine.tar.gz", "a".repeat(64))),
        ),
        downloader = VerifiedRuntimeDownloader(),
        shimAsset = { "" },
    )

    private class Runner(
        private val rootfs: File,
        private val latest: String = "0.1.15",
        private val lookupError: Exception? = null,
        private val failBuild: Boolean = false,
        private val installedVersion: String? = null,
    ) : AgentCommandRunner {
        val scripts = mutableListOf<String>()
        var lookups = 0

        override fun output(script: String): String {
            lookups++
            assertEquals("npm view @doublepi123/cox dist-tags.latest", script)
            lookupError?.let { throw it }
            return latest
        }

        override fun mustSucceed(script: String, failure: MessageKey, failureText: String, timeoutMinutes: Long) {
            scripts += script
            if (script.contains("npm i -g")) {
                val version = installedVersion ?: script.substringAfter("@doublepi123/cox@")
                val module = File(rootfs, InstalledComponents.COX_MODULE.removePrefix("/"))
                File(module, "dist/client").mkdirs()
                File(module, "dist/bin.mjs").writeText("")
                File(module, "dist/client/index.html").writeText("")
                File(module, "node_modules/node-pty").mkdirs()
                File(module, "package.json").writeText("""{"name":"@doublepi123/cox","version":"$version"}""")
            }
            if (failBuild && script.contains("node-gyp rebuild")) error("build failed")
        }
    }

    @Test
    fun `fresh install resolves npm latest then pins installs rebuilds and verifies that version`() {
        val rootfs = folder.newFolder()
        val runner = Runner(rootfs)
        assertEquals("0.1.15", installer().installCox(report, runner, rootfs, null))
        assertEquals(1, runner.lookups)
        assertTrue(runner.scripts[0].contains("apk --cache-dir /var/cache/apk add python3 make g++ linux-headers"))
        assertTrue(runner.scripts[1].contains("@doublepi123/cox@0.1.15"))
        assertEquals(RuntimeEnvironmentInstaller.coxPtyBuildScript(), runner.scripts[2])
        assertTrue(InstalledComponents.isCoxNpmInstall(rootfs))
    }

    @Test
    fun `same version migration removes the old program but preserves other server and user data`() {
        val rootfs = folder.newFolder()
        val old = File(rootfs, "opt/t3/bin.mjs").apply { parentFile?.mkdirs(); writeText("old") }
        val survivor = File(rootfs, "usr/local/lib/node_modules/@openchamber/web/keep").apply {
            parentFile?.mkdirs()
            writeText("keep")
        }
        val home = File(rootfs, "root/.cox/data").apply { parentFile?.mkdirs(); writeText("userdata") }
        val runner = Runner(rootfs)
        assertEquals("0.1.14", installer().installCox(report, runner, rootfs, "0.1.14"))
        assertEquals(0, runner.lookups)
        assertFalse(old.exists())
        assertEquals("keep", survivor.readText())
        assertEquals("userdata", home.readText())
    }

    @Test
    fun `unreachable latest metadata uses the manifest fallback`() {
        val rootfs = folder.newFolder()
        val runner = Runner(rootfs, lookupError = java.io.IOException("offline"))
        assertEquals("0.1.14", installer().installCox(report, runner, rootfs, null))
    }

    @Test
    fun `cancelled lookup is not converted into a fallback install`() {
        val rootfs = folder.newFolder()
        val runner = Runner(rootfs, lookupError = CancellationException("cancelled"))
        assertThrows(CancellationException::class.java) { installer().installCox(report, runner, rootfs, null) }
        assertEquals(1, runner.scripts.size)
    }

    @Test
    fun `invalid registry version cannot reach a shell install`() {
        val rootfs = folder.newFolder()
        val runner = Runner(rootfs, latest = "0.1.14; echo unsafe")
        assertThrows(IllegalArgumentException::class.java) { installer().installCox(report, runner, rootfs, null) }
        assertEquals(1, runner.scripts.size)
    }

    @Test
    fun `native build failures prevent a successful install result`() {
        val rootfs = folder.newFolder()
        val runner = Runner(rootfs, failBuild = true)
        assertThrows(IllegalStateException::class.java) { installer().installCox(report, runner, rootfs, "0.1.14") }
    }

    @Test
    fun `unexpected installed version fails verification`() {
        val rootfs = folder.newFolder()
        val runner = Runner(rootfs, installedVersion = "0.1.13")
        assertThrows(IllegalArgumentException::class.java) { installer().installCox(report, runner, rootfs, "0.1.14") }
    }
}
