package io.cocode.app.agentlogin

import io.cocode.app.runtime.process.EmbeddedCommandSuite
import io.cocode.app.runtime.process.ProotCommands
import io.cocode.app.runtime.storage.GuestBinds
import java.io.File

/**
 * Pure argv/env builders for the Codex sign-in commands. Reuses the server's PRoot prefix, bind
 * specs and guest base environment so Codex sees exactly the same `$HOME`, PATH and (in shared
 * mode) the same `~/.codex` folder the server and agent CLI use.
 */
internal object CodexLoginCommands {
    const val CODEX_BIN = "/usr/local/bin/codex"

    fun argv(
        proot: File,
        rootfs: File,
        binds: GuestBinds,
        command: CodexLoginCommand,
    ): List<String> =
        ProotCommands.prootArguments(
            proot = proot,
            rootfs = rootfs,
            extraArgs =
                ProotCommands.bindArguments(binds.specs()) +
                    listOf("-w", ProotCommands.GUEST_HOME),
        ) + listOf(CODEX_BIN) + command.arguments

    /**
     * Guest environment: the installer-style base (HOME=/root, guest PATH, LANG=C.UTF-8, no server
     * secrets, no Node shim — Codex is a native binary), plus NO_COLOR/TERM to keep the output plain
     * and IS_SANDBOX because PRoot fakes uid 0 inside an unprivileged app sandbox.
     */
    fun environment(
        suite: EmbeddedCommandSuite.Paths,
        prootTmp: File,
    ): Map<String, String> =
        ProotCommands.installEnvironment(suite.environment(), prootTmp) +
            mapOf(
                "IS_SANDBOX" to "1",
                "NO_COLOR" to "1",
                "TERM" to "dumb",
            )
}
