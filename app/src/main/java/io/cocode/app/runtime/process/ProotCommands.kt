package io.cocode.app.runtime.process

import io.cocode.app.runtime.storage.GuestBinds
import io.cocode.app.runtime.storage.SharedStorageLayout
import java.io.File

/**
 * Pure builders for the PRoot command lines and guest environments. Kept free of Android APIs so
 * the exact argv and env are unit-tested.
 */
object ProotCommands {
    const val GUEST_HOME = "/root"
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    const val GUEST_NODE = "/usr/bin/node"
    const val T3_CLI = "/opt/t3/bin.mjs"
    const val T3_HOME = "/root/.t3"
    const val SHIM_PATH = "/opt/cocode/android-shim.cjs"

    /** Shared PRoot prefix: kill-on-exit, link2symlink, fake root, rootfs and the Android mounts. */
    fun prootArguments(
        proot: File,
        rootfs: File,
        extraArgs: List<String>,
    ): List<String> =
        buildList {
            add(proot.absolutePath)
            add("--kill-on-exit")
            add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            listOf("/dev", "/proc", "/sys", "/system").forEach { mount ->
                add("-b")
                add(mount)
            }
            addAll(extraArgs)
        }

    /** `-b host:guest` pairs for each spec. */
    fun bindArguments(specs: List<String>): List<String> = specs.flatMap { listOf("-b", it) }

    /**
     * Bind spec that maps the persistent [home] (outside the environment) over the guest /root.
     * Applied to every guest command, so config, sessions and credentials survive a rebuild.
     */
    fun homeBindSpec(home: File): String = "${home.absolutePath}:$GUEST_HOME"

    /** A non-interactive `/bin/sh -lc <script>` inside the rootfs, used by the installer. */
    fun guestShellCommand(
        proot: File,
        rootfs: File,
        home: File,
        extraBinds: List<String>,
        script: String,
    ): List<String> =
        prootArguments(
            proot,
            rootfs,
            bindArguments(listOf(homeBindSpec(home)) + extraBinds) + listOf("-w", GUEST_HOME),
        ) +
            listOf("/bin/sh", "-lc", script)

    /**
     * The T3 Code server, bound to loopback, running in the foreground with the guest home as the
     * working directory. Private-only binds: ~/projects is the private workspace.
     */
    fun serverCommand(
        proot: File,
        rootfs: File,
        home: File,
        workspace: File,
        storageArguments: List<String>,
    ): List<String> =
        serverCommand(
            proot = proot,
            rootfs = rootfs,
            binds = GuestBinds.privateOnly(home = home, privateWorkspace = workspace),
            extraBinds = storageArguments,
        )

    /** The T3 Code server with an explicit bind set; the port comes from the environment. */
    fun serverCommand(
        proot: File,
        rootfs: File,
        binds: GuestBinds,
        extraBinds: List<String>,
    ): List<String> =
        prootArguments(
            proot,
            rootfs,
            bindArguments(binds.specs() + extraBinds) + listOf("-w", SharedStorageLayout.GUEST_HOME),
        ) +
            listOf(GUEST_NODE, T3_CLI)

    /** Environment for the long-running server: cleared by the caller, then set to exactly this. */
    fun serverEnvironment(
        suiteEnvironment: Map<String, String>,
        prootTmp: File,
        port: Int,
        uiPassword: String,
    ): Map<String, String> =
        buildMap {
            putAll(suiteEnvironment)
            put("PROOT_TMP_DIR", prootTmp.absolutePath)
            putAll(guestBaseEnvironment())
            put("T3CODE_HOST", "127.0.0.1")
            put("T3CODE_PORT", port.toString())
            put("T3CODE_NO_BROWSER", "true")
            put("T3CODE_HOME", T3_HOME)
            put("T3CODE_AUTH_PASSWORD", uiPassword)
            // Codex/OpenCode must not try to update themselves over the pinned npm install.
            put("OPENCODE_DISABLE_AUTOUPDATE", "true")
            // Android's SELinux makes os.networkInterfaces() throw; the shim supplies a loopback list.
            put("NODE_OPTIONS", "--require $SHIM_PATH")
        }

    /** Environment for installer steps (apk, npm): no server secrets, no shim. */
    fun installEnvironment(
        suiteEnvironment: Map<String, String>,
        prootTmp: File,
    ): Map<String, String> =
        buildMap {
            putAll(suiteEnvironment)
            put("PROOT_TMP_DIR", prootTmp.absolutePath)
            putAll(guestBaseEnvironment())
            put("npm_config_update_notifier", "false")
        }

    private fun guestBaseEnvironment(): Map<String, String> =
        mapOf(
            "HOME" to GUEST_HOME,
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to "/bin/bash",
            "PATH" to GUEST_PATH,
            "TMPDIR" to "/tmp",
            "LANG" to "C.UTF-8",
            "XDG_CONFIG_HOME" to "/root/.config",
            "XDG_CACHE_HOME" to "/root/.cache",
            "XDG_DATA_HOME" to "/root/.local/share",
            "XDG_STATE_HOME" to "/root/.local/state",
        )
}
