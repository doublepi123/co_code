package io.cocode.app.runtime.process

import io.cocode.app.runtime.ServerKind
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
    const val OPENCHAMBER_CLI = "/usr/local/lib/node_modules/@openchamber/web/bin/cli.js"
    const val GUEST_OPENCODE = "/usr/local/bin/opencode"
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
     * A server of [kind], bound to loopback, running in the foreground with the guest home as the
     * working directory. Private-only binds: ~/projects is the private workspace.
     */
    fun serverCommand(
        proot: File,
        rootfs: File,
        home: File,
        workspace: File,
        storageArguments: List<String>,
        kind: ServerKind = ServerKind.T3,
    ): List<String> =
        serverCommand(
            proot = proot,
            rootfs = rootfs,
            binds = GuestBinds.privateOnly(home = home, privateWorkspace = workspace),
            extraBinds = storageArguments,
            kind = kind,
        )

    /** A server of [kind] with an explicit bind set; each kind carries its own fixed port. */
    fun serverCommand(
        proot: File,
        rootfs: File,
        binds: GuestBinds,
        extraBinds: List<String>,
        kind: ServerKind = ServerKind.T3,
    ): List<String> =
        prootArguments(
            proot,
            rootfs,
            bindArguments(binds.specs() + extraBinds) + listOf("-w", SharedStorageLayout.GUEST_HOME),
        ) + serverGuestCommand(kind)

    /** The guest argv of the server, after proot and its binds. */
    private fun serverGuestCommand(kind: ServerKind): List<String> =
        when (kind) {
            ServerKind.T3 -> listOf(GUEST_NODE, T3_CLI)
            ServerKind.OpenChamber ->
                listOf(
                    GUEST_NODE,
                    OPENCHAMBER_CLI,
                    "serve",
                    "--foreground",
                    "--port",
                    kind.port.toString(),
                )
        }

    /** Environment for the long-running server: cleared by the caller, then set to exactly this. */
    fun serverEnvironment(
        suiteEnvironment: Map<String, String>,
        prootTmp: File,
        port: Int,
        uiPassword: String,
        kind: ServerKind = ServerKind.T3,
    ): Map<String, String> =
        buildMap {
            putAll(suiteEnvironment)
            put("PROOT_TMP_DIR", prootTmp.absolutePath)
            putAll(guestBaseEnvironment())
            // OpenCode must not try to update itself over the pinned npm install.
            put("OPENCODE_DISABLE_AUTOUPDATE", if (kind == ServerKind.T3) "true" else "1")
            // Android's SELinux makes os.networkInterfaces() throw; the shim supplies a loopback list.
            put("NODE_OPTIONS", "--require $SHIM_PATH")
            when (kind) {
                ServerKind.T3 -> {
                    put("T3CODE_HOST", "127.0.0.1")
                    put("T3CODE_PORT", port.toString())
                    put("T3CODE_NO_BROWSER", "true")
                    put("T3CODE_HOME", T3_HOME)
                    put("T3CODE_AUTH_PASSWORD", uiPassword)
                    // PRoot's -0 fakes uid 0 inside an unprivileged Android app sandbox. Claude Code
                    // refuses T3's bypassPermissions mode for root unless the process says it is
                    // sandboxed, which it is: the guest has no more rights than the app.
                    put("IS_SANDBOX", "1")
                }
                ServerKind.OpenChamber -> {
                    put("OPENCHAMBER_HOST", "127.0.0.1")
                    put("OPENCHAMBER_UI_PASSWORD", uiPassword)
                    // No paired-device relay: this server is only reachable on device loopback.
                    put("OPENCHAMBER_RELAY_HOST", "off")
                    put("OPENCHAMBER_OPENCODE_BIN", GUEST_OPENCODE)
                    put("OPENCHAMBER_OPENCODE_HEALTH_TIMEOUT_MS", "15000")
                }
            }
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
