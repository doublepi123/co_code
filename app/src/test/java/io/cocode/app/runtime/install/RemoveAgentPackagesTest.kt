package io.cocode.app.runtime.install

import io.cocode.app.runtime.manifest.AgentSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

class RemoveAgentPackagesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun rootfs(): File = temporaryFolder.newFolder("rootfs")

    private val opencode = AgentSpec("opencode", "@opencode/cli", "opencode", "OpenCode")
    private val codex = AgentSpec("codex", "@openai/codex", "codex", "Codex")

    @Test
    fun `removes the agent package tree and every bin link`() {
        val rootfs = rootfs()
        rootfs.resolve("usr/local/lib/node_modules/@opencode/cli/bin").mkdirs()
        rootfs.resolve("usr/local/lib/node_modules/@opencode/cli/bin/opencode").writeText("x")
        rootfs.resolve("usr/local/bin").mkdirs()
        listOf("opencode", "opencode2").forEach { bin ->
            Files.createSymbolicLink(
                rootfs.resolve("usr/local/bin/$bin").toPath(),
                Paths.get("../lib/node_modules/@opencode/cli/bin/opencode"),
            )
        }

        RuntimeEnvironmentInstaller.removeAgentPackages(rootfs, listOf(opencode))

        assertFalse(rootfs.resolve("usr/local/lib/node_modules/@opencode/cli").exists())
        listOf("opencode", "opencode2").forEach { bin ->
            assertFalse(Files.exists(rootfs.resolve("usr/local/bin/$bin").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `a bin link into an outside file is unlinked and the outside file survives`() {
        val rootfs = rootfs()
        val outside = temporaryFolder.newFolder("outside").resolve("keep.txt").apply { writeText("precious") }
        rootfs.resolve("usr/local/bin").mkdirs()
        Files.createSymbolicLink(rootfs.resolve("usr/local/bin/codex").toPath(), outside.toPath().toAbsolutePath())

        RuntimeEnvironmentInstaller.removeAgentPackages(rootfs, listOf(codex))

        assertFalse(Files.exists(rootfs.resolve("usr/local/bin/codex").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(outside.isFile)
        assertEquals("precious", outside.readText())
    }

    @Test
    fun `a package directory that is itself a link to outside is unlinked and its target survives`() {
        val rootfs = rootfs()
        val outside = temporaryFolder.newFolder("outside-pkg").apply { resolve("pkg.json").writeText("keep") }
        rootfs.resolve("usr/local/lib/node_modules/@openai").mkdirs()
        Files.createSymbolicLink(
            rootfs.resolve("usr/local/lib/node_modules/@openai/codex").toPath(),
            outside.toPath().toAbsolutePath(),
        )

        RuntimeEnvironmentInstaller.removeAgentPackages(rootfs, listOf(codex))

        assertFalse(Files.exists(rootfs.resolve("usr/local/lib/node_modules/@openai/codex").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals("keep", outside.resolve("pkg.json").readText())
    }

    @Test
    fun `missing entries are a no-op`() {
        val rootfs = rootfs()
        RuntimeEnvironmentInstaller.removeAgentPackages(rootfs, listOf(opencode))
        assertTrue(rootfs.isDirectory)
    }

    @Test
    fun `unrelated files in the rootfs are kept`() {
        val rootfs = rootfs()
        rootfs.resolve("usr/local/bin").mkdirs()
        rootfs.resolve("usr/local/bin/other-tool").writeText("keep")
        rootfs.resolve("usr/local/lib/node_modules/left-pad").mkdirs()

        RuntimeEnvironmentInstaller.removeAgentPackages(rootfs, listOf(opencode))

        assertEquals("keep", rootfs.resolve("usr/local/bin/other-tool").readText())
        assertTrue(rootfs.resolve("usr/local/lib/node_modules/left-pad").isDirectory)
    }
}
