package io.cocode.app.runtime.install

import io.cocode.app.runtime.MessageKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CursorSdkRuntimeTest {
    private fun npmRoot(): File {
        val rootfs = temporaryFolder.newFolder()
        for (path in listOf(InstalledComponents.COX_CLI, InstalledComponents.COX_UI)) {
            File(rootfs, path.removePrefix("/")).apply { parentFile?.mkdirs(); writeText("") }
        }
        val module = File(rootfs, InstalledComponents.COX_MODULE.removePrefix("/"))
        File(module, "node_modules/node-pty").mkdirs()
        File(module, "node_modules/@cursor/sdk").mkdirs()
        File(module, "package.json").writeText("""{"name":"@doublepi123/cox","version":"0.1.14"}""")
        return rootfs
    }

    @Test
    fun `npm Cox retains the full offline SDK smoke without a bundle supplied script`() {
        val root = npmRoot()
        val runner = Runner()
        RuntimeEnvironmentInstaller.verifyCursorSdkRuntime(runner, root)
        val smoke = "${InstalledComponents.COX_MODULE}/cursor-sdk-smoke.cjs"
        assertEquals("set -e; node $smoke", runner.scripts[1])
        val script = File(root, smoke.removePrefix("/")).readText()
        assertTrue(script.contains("prewarmLocalWorkspace"))
        assertTrue(script.contains("[true, false, true]"))
        assertTrue(script.contains("process.env.HOME = directory"))
    }

    @Test
    fun `npm native dependency failure prevents activation`() {
        val runner = Runner(failAt = 1)
        assertThrows(IllegalStateException::class.java) {
            RuntimeEnvironmentInstaller.verifyCoxNativeModules(runner, npmRoot())
        }
        assertTrue(runner.scripts.single().contains("load('@napi-rs/keyring'); load('@ff-labs/fff-node')"))
    }
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class Runner(private val failAt: Int? = null) : AgentCommandRunner {
        val scripts = mutableListOf<String>()

        override fun mustSucceed(script: String, failure: MessageKey, failureText: String, timeoutMinutes: Long) {
            scripts += script
            if (scripts.size == failAt) error("guest failure")
        }

        override fun output(script: String): String = error("Unexpected output command")
    }

    private fun sdkRoot(): File = temporaryFolder.newFolder().also {
        File(it, "opt/t3/node_modules/@cursor/sdk").mkdirs()
    }

    @Test
    fun `legacy bundles without Cursor SDK need no migration`() {
        val runner = Runner()
        RuntimeEnvironmentInstaller.verifyCursorSdkRuntime(runner, temporaryFolder.newFolder())
        assertTrue(runner.scripts.isEmpty())
    }

    @Test
    fun `existing environments install gcompat before loading both native bindings`() {
        val runner = Runner()
        RuntimeEnvironmentInstaller.verifyCursorSdkRuntime(runner, sdkRoot())
        assertEquals(2, runner.scripts.size)
        assertTrue(runner.scripts[0].contains("apk info -e gcompat >/dev/null 2>&1 || apk --cache-dir /var/cache/apk add gcompat"))
        assertTrue(runner.scripts[1].contains("require(base + 'tree-sitter')"))
        assertTrue(runner.scripts[1].contains("require(base + 'tree-sitter-bash')"))
    }

    @Test
    fun `new bundles use their complete offline SDK smoke`() {
        val root = sdkRoot()
        File(root, "opt/t3/cursor-sdk-smoke.cjs").writeText("")
        val runner = Runner()
        RuntimeEnvironmentInstaller.verifyCursorSdkRuntime(runner, root)
        assertEquals("set -e; node /opt/t3/cursor-sdk-smoke.cjs", runner.scripts[1])
    }

    @Test
    fun `package failure prevents SDK verification and activation`() {
        val runner = Runner(failAt = 1)
        assertThrows(IllegalStateException::class.java) {
            RuntimeEnvironmentInstaller.verifyCursorSdkRuntime(runner, sdkRoot())
        }
        assertEquals(1, runner.scripts.size)
    }

    @Test
    fun `SDK verification failures propagate`() {
        val runner = Runner(failAt = 2)
        assertThrows(IllegalStateException::class.java) {
            RuntimeEnvironmentInstaller.verifyCursorSdkRuntime(runner, sdkRoot())
        }
        assertEquals(2, runner.scripts.size)
    }
}
