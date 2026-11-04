package io.cocode.app.runtime.process

import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.install.InstalledComponents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotCommandsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `npm Cox launches its dist entry instead of the legacy bundle`() {
        val npmRootfs = folder.newFolder()
        for (path in listOf(InstalledComponents.COX_CLI, InstalledComponents.COX_UI)) {
            File(npmRootfs, path.removePrefix("/")).apply { parentFile?.mkdirs(); writeText("") }
        }
        val module = File(npmRootfs, InstalledComponents.COX_MODULE.removePrefix("/"))
        File(module, "node_modules/node-pty").mkdirs()
        File(module, "package.json").writeText("""{"name":"@doublepi123/cox","version":"0.1.14"}""")
        val command = ProotCommands.serverCommand(proot, npmRootfs, home, workspace, emptyList())
        assertEquals(listOf("/usr/bin/node", InstalledComponents.COX_CLI), command.takeLast(2))
    }
    private val proot = File("/native/libcocode_proot.so")
    private val rootfs = File("/data/runtime/environment/rootfs")
    private val workspace = File("/data/runtime/workspace")
    private val prootTmp = File("/data/runtime/proot-tmp")
    private val home = File("/data/runtime/home")

    @Test
    fun `guest home is bind-mounted from outside the environment for server and installer`() {
        val server =
            ProotCommands.serverCommand(
                proot = proot,
                rootfs = rootfs,
                home = home,
                workspace = workspace,
                storageArguments = emptyList(),
            )
        val installer = ProotCommands.guestShellCommand(proot, rootfs, home, emptyList(), "true")

        assertTrue(server.containsPair("-b", "/data/runtime/home:/root"))
        assertTrue(installer.containsPair("-b", "/data/runtime/home:/root"))
        assertFalse(server.any { it.startsWith(rootfs.absolutePath + "/root") })
    }

    @Test
    fun `server command line runs the t3 bundle in the foreground`() {
        val command =
            ProotCommands.serverCommand(
                proot = proot,
                rootfs = rootfs,
                home = home,
                workspace = workspace,
                storageArguments = listOf("-b", "/storage/emulated/0:/sdcard"),
            )

        assertEquals(proot.absolutePath, command.first())
        assertTrue("--kill-on-exit" in command)
        assertTrue("--link2symlink" in command)
        assertEquals("-0", command[command.indexOf("-0")])
        assertEquals(rootfs.absolutePath, command[command.indexOf("-r") + 1])
        assertTrue(command.containsPair("-b", "/dev"))
        assertTrue(command.containsPair("-b", "/proc"))
        assertTrue(command.containsPair("-b", "/sys"))
        assertTrue(command.containsPair("-b", "/system"))
        assertTrue(command.containsPair("-b", "${workspace.absolutePath}:/root/projects"))
        assertTrue(command.containsPair("-b", "/storage/emulated/0:/sdcard"))
        assertFalse(command.any { it.endsWith(":/workspace") || it.endsWith(":/workspace-private") })
        assertEquals("/root", command[command.indexOf("-w") + 1])

        val guest = command.takeLast(2)
        assertEquals(listOf("/usr/bin/node", "/opt/t3/bin.mjs"), guest)
    }

    @Test
    fun `a legacy t3 bundle keeps its T3CODE_HOME until it is updated`() {
        val env =
            ProotCommands.serverEnvironment(
                suiteEnvironment = emptyMap(),
                prootTmp = prootTmp,
                port = 3773,
                uiPassword = "secret-password",
                kind = ServerKind.T3,
                coxBundle = false,
            )

        assertEquals("/root/.t3", env["T3CODE_HOME"])
        assertFalse(env.containsKey("COX_HOME"))
        assertFalse(env.containsKey("COX_AUTH_PASSWORD"))
        assertEquals("secret-password", env["T3CODE_AUTH_PASSWORD"])
    }

    @Test
    fun `install environment has no server secrets and no node shim`() {
        val env = ProotCommands.installEnvironment(emptyMap(), prootTmp)

        assertFalse(env.containsKey("T3CODE_AUTH_PASSWORD"))
        assertFalse(env.containsKey("COX_AUTH_PASSWORD"))
        assertFalse(env.containsKey("NODE_OPTIONS"))
        assertEquals("/root", env["HOME"])
    }

    @Test
    fun `guest shell command runs the script through sh -lc inside the rootfs`() {
        val command =
            ProotCommands.guestShellCommand(
                proot = proot,
                rootfs = rootfs,
                home = home,
                extraBinds = listOf("/cache/apk:/var/cache/apk"),
                script = "npm --version",
            )

        assertTrue(command.containsPair("-b", "/cache/apk:/var/cache/apk"))
        assertEquals(listOf("/bin/sh", "-lc", "npm --version"), command.takeLast(3))
        assertEquals("/root", command[command.indexOf("-w") + 1])
    }

    @Test
    fun `bind arguments are emitted as -b pairs`() {
        assertEquals(listOf("-b", "/a:/b", "-b", "/c:/d"), ProotCommands.bindArguments(listOf("/a:/b", "/c:/d")))
    }

    private fun List<String>.containsPair(
        flag: String,
        value: String,
    ): Boolean = zipWithNext().any { (a, b) -> a == flag && b == value }
}
