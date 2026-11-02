package io.cocode.app.runtime.install

import io.cocode.app.runtime.manifest.AgentSpec
import io.cocode.app.runtime.install.RuntimeEnvironmentInstaller.Companion.APK_PACKAGES
import io.cocode.app.runtime.install.RuntimeEnvironmentInstaller.Companion.PTY_MODULE_GUEST
import io.cocode.app.runtime.install.RuntimeEnvironmentInstaller.Companion.SHIM_GUEST_PATH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The installer's guest command strings and the T3 bundle contract. The real commands are verified
 * on a device; these pin the parts a regression would silently break.
 */
class RuntimeEnvironmentInstallerTest {
    @Test
    fun `Cox install pins npm and retains dependency lifecycle scripts`() {
        val script = RuntimeEnvironmentInstaller.coxInstallScript("0.1.14")
        assertTrue(script.contains("npm i -g --no-fund --no-audit @doublepi123/cox@0.1.14"))
        assertFalse(script.contains("--ignore-scripts"))
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeEnvironmentInstaller.coxInstallScript("0.1.14; exit 0")
        }
    }

    @Test
    fun `Cox rebuild checks the musl addon before removing incompatible prebuilds`() {
        val script = RuntimeEnvironmentInstaller.coxPtyBuildScript()
        assertTrue(script.startsWith("set -e;"))
        assertTrue(script.contains("cd $PTY_MODULE_GUEST; npx --yes node-gyp rebuild"))
        assertTrue(script.contains("test -f build/Release/pty.node; rm -rf prebuilds"))
    }
    private val codex = AgentSpec("codex", "@openai/codex", "codex", "Codex")
    private val opencode = AgentSpec("opencode", "@opencode/cli", "opencode", "OpenCode")

    @Test
    fun `apk installs the runtime packages and native build toolchain for both npm servers`() {
        val packages = APK_PACKAGES.split(" ")
        listOf("nodejs", "npm", "bash", "git", "curl", "ripgrep", "openssh-client", "ca-certificates", "libstdc++", "python3", "gcompat").forEach {
            assertTrue("$it missing from apk packages", it in packages)
        }
        listOf("make", "g++", "linux-headers").forEach {
            assertTrue("$it missing from the native build packages", it in packages)
        }
    }

    @Test
    fun `agents install from npm at their latest version`() {
        val script = RuntimeEnvironmentInstaller.agentInstallScript(listOf(codex, opencode))

        assertEquals("set -e; npm i -g --no-fund --no-audit @openai/codex @opencode/cli", script)
    }

    @Test
    fun `an update pins the target agent versions`() {
        val script =
            RuntimeEnvironmentInstaller.agentInstallScript(
                listOf(codex),
                versions = mapOf("codex" to "0.167.0"),
            )

        assertEquals("set -e; npm i -g --no-fund --no-audit @openai/codex@0.167.0", script)
    }

    @Test
    fun `a blank target falls back to latest`() {
        val script =
            RuntimeEnvironmentInstaller.agentInstallScript(
                listOf(codex),
                versions = mapOf("codex" to ""),
            )

        assertEquals("set -e; npm i -g --no-fund --no-audit @openai/codex", script)
    }

    @Test
    fun `pty smoke test uses the node-pty bundled with t3`() {
        val script = RuntimeEnvironmentInstaller.PTY_SMOKE_SCRIPT
        assertTrue(script.contains("require('$PTY_MODULE_GUEST')"))
        assertEquals("/usr/local/lib/node_modules/@doublepi123/cox/node_modules/node-pty", PTY_MODULE_GUEST)
        assertTrue(script.contains("pty.spawn('/bin/sh', ['-c', 'echo PTYOK']"))
        assertTrue(script.contains("exitCode === 0 && out.includes('PTYOK')"))
        assertTrue(script.contains("process.exit(1)"))
        assertTrue(RuntimeEnvironmentInstaller.PTY_SMOKE_GUEST_PATH.startsWith("/opt/cocode/"))
    }

    @Test
    fun `t3 runs from its own directory and the shim path is unchanged`() {
        assertEquals("/usr/local/lib/node_modules/@doublepi123/cox/dist/bin.mjs", InstalledComponents.COX_CLI)
        assertEquals("/opt/cocode/android-shim.cjs", SHIM_GUEST_PATH)
    }

    @Test
    fun `opencode owns a second bin link`() {
        assertEquals(listOf("opencode", "opencode2"), RuntimeEnvironmentInstaller.agentBinNames(opencode))
        assertEquals(listOf("codex"), RuntimeEnvironmentInstaller.agentBinNames(codex))
    }

    @Test
    fun `version comparison parses real cli output`() {
        assertEquals("2.2.5", RuntimeEnvironmentInstaller.extractVersion("opencode v2.2.5\n"))
        assertEquals("0.166.0", RuntimeEnvironmentInstaller.extractVersion("codex-cli 0.166.0\n"))
        assertEquals("2.1.316", RuntimeEnvironmentInstaller.extractVersion("2.1.316 (Claude Code)"))
        assertEquals("24.18.1", RuntimeEnvironmentInstaller.extractVersion("v24.18.1\n"))
        assertTrue(RuntimeEnvironmentInstaller.compareVersions("2.2.5", "2.2.4") > 0)
        assertTrue(RuntimeEnvironmentInstaller.compareVersions("2.1.316", "2.1.316") == 0)
        assertTrue(RuntimeEnvironmentInstaller.compareVersions("0.9.0", "0.10.0") < 0)
    }

    @Test
    fun `tar entries can drop the release top level directory`() {
        assertEquals("bin.mjs", io.cocode.app.runtime.install.RuntimeArchive.stripLeadingSegments("t3-0.1.6-pw.5-arm64/bin.mjs", 1))
        assertEquals("", io.cocode.app.runtime.install.RuntimeArchive.stripLeadingSegments("t3-0.1.6-pw.5-arm64/", 1))
        assertEquals("client/index.html", io.cocode.app.runtime.install.RuntimeArchive.stripLeadingSegments("/t3/client/index.html", 1))
    }
}
