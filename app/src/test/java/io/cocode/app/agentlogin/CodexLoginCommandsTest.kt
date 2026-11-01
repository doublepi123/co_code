package io.cocode.app.agentlogin

import io.cocode.app.runtime.process.EmbeddedCommandSuite
import io.cocode.app.runtime.process.ProotCommands
import io.cocode.app.runtime.storage.GuestBinds
import io.cocode.app.runtime.storage.SharedStorageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CodexLoginCommandsTest {
    private val proot = File("/native/libcocode_android_proot.so")
    private val rootfs = File("/data/runtime/environment/rootfs")
    private val home = File("/data/runtime/home")
    private val workspace = File("/data/runtime/workspace")
    private val prootTmp = File("/data/runtime/proot-tmp")

    private val suite =
        EmbeddedCommandSuite.Paths(
            home = File("/data/runtime/command-suite/home"),
            tmp = File("/data/runtime/command-suite/tmp"),
            nativeLibraryDirectory = File("/native"),
            proot = proot,
            loader = File("/native/libcocode_android_proot_loader.so"),
            loader32 = File("/native/libcocode_android_proot_loader32.so"),
        )

    @Test
    fun `device login command uses the server binds and the guest codex binary`() {
        val codexDir = File("/sdcard/CoCode/codex")
        val binds =
            GuestBinds.shared(
                home = home,
                privateWorkspace = workspace,
                sharedProjects = File("/sdcard/CoCode/projects"),
                sharedOpencode = File("/sdcard/CoCode/opencode"),
                privateExceptionsDir = File("/data/runtime/home-private/opencode"),
                sharedCodex = codexDir,
            )

        val argv = CodexLoginCommands.argv(proot, rootfs, binds, CodexLoginCommand.DeviceAuth)

        assertEquals(proot.absolutePath, argv.first())
        assertTrue(argv.windowed(2).contains(listOf("-b", "${home.absolutePath}:/root")))
        assertTrue(
            argv.toString(),
            argv.windowed(2).contains(listOf("-b", "${codexDir.absolutePath}:${SharedStorageLayout.GUEST_CODEX_SHARED}")),
        )
        assertTrue(argv.containsAll(listOf("-w", "/root", "/usr/local/bin/codex", "login", "--device-auth")))
    }

    @Test
    fun `status and api-key commands carry their own arguments`() {
        val binds = GuestBinds.privateOnly(home, workspace)

        assertEquals(
            listOf("login", "status"),
            CodexLoginCommands.argv(proot, rootfs, binds, CodexLoginCommand.Status).takeLast(2),
        )
        assertEquals(
            listOf("login", "--with-api-key"),
            CodexLoginCommands.argv(proot, rootfs, binds, CodexLoginCommand.WithApiKey).takeLast(2),
        )
    }

    @Test
    fun `environment matches the server guest base and stays plain`() {
        val env = CodexLoginCommands.environment(suite, prootTmp)

        assertEquals("/root", env["HOME"])
        assertEquals(ProotCommands.GUEST_PATH, env["PATH"])
        assertEquals("C.UTF-8", env["LANG"])
        assertEquals("1", env["IS_SANDBOX"])
        assertEquals("1", env["NO_COLOR"])
        assertEquals("dumb", env["TERM"])
        assertEquals(prootTmp.absolutePath, env["PROOT_TMP_DIR"])
        // Codex is a native binary: no Node shim, and never a server password in this environment.
        assertFalse(env.containsKey("NODE_OPTIONS"))
        assertFalse(env.containsKey("T3CODE_AUTH_PASSWORD"))
        assertFalse(env.keys.any { it.contains("PASSWORD", ignoreCase = true) })
    }
}
