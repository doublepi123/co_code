package io.cocode.app.runtime

import java.io.File

/** Every on-disk location of the Linux runtime, rooted at `filesDir/runtime`. */
class RuntimePaths(
    val root: File,
) {
    /**
     * Active environment: `rootfs/` plus its own `metadata.json`, which is the single authoritative
     * record of what is installed. Swapped atomically by directory rename on install or repair.
     */
    val environment: File get() = File(root, "environment")
    val rootfs: File get() = File(environment, "rootfs")
    /** Built fresh by install or repair; only renamed into [environment] after it is verified. */
    val staging: File get() = File(root, "environment.staging")
    /** Previous environment during a swap. Garbage once [environment] has been committed. */
    val rollback: File get() = File(root, "environment.rollback")
    /**
     * Guest `/root`: T3 Code/agent config, sessions and credentials. Lives outside the
     * environment so a repair or reinstall never deletes it. Bind-mounted over the guest /root.
     */
    val home: File get() = File(root, "home")
    val cache: File get() = File(root, "cache")
    val logs: File get() = File(root, "logs")
    val workspace: File get() = File(root, "workspace")
    val prootTmp: File get() = File(root, "proot-tmp")
    val commandSuite: File get() = File(root, "command-suite")
    val metadataFile: File get() = File(environment, METADATA_FILE_NAME)

    /** T3 Code's UI password, the historical location. Use [uiPasswordFile] for another kind. */
    val uiPasswordFile: File get() = uiPasswordFile(ServerKind.T3)

    /** Per-server UI password file. A password is never shared between kinds. */
    fun uiPasswordFile(kind: ServerKind): File = File(root, kind.uiPasswordFileName)

    val installLog: File get() = File(logs, "install.log")

    /** T3 Code's log, the historical location. [kind] selects the server's own log. */
    val serverLog: File get() = serverLog(ServerKind.T3)

    /** Per-server log file. T3 keeps its historical `t3.log` name, so existing data stays readable. */
    fun serverLog(kind: ServerKind): File = File(logs, kind.serverLogFileName)

    private val ServerKind.serverLogFileName: String
        get() =
            when (this) {
                ServerKind.T3 -> T3_LOG_FILE_NAME
            }

    private val ServerKind.uiPasswordFileName: String
        get() =
            when (this) {
                // Preserved verbatim: existing installations already store their T3 password here.
                ServerKind.T3 -> UI_PASSWORD_FILE_NAME
            }

    companion object {
        const val METADATA_FILE_NAME = "metadata.json"
        const val UI_PASSWORD_FILE_NAME = "ui-password"
        const val T3_LOG_FILE_NAME = "t3.log"

        fun under(filesDir: File): RuntimePaths = RuntimePaths(File(filesDir, "runtime"))
    }
}
