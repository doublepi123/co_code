package io.cocode.app.runtime.storage

import java.io.File

/**
 * The effective bind mounts for one server launch. Pure data, turned into PRoot `-b` arguments by
 * ProotCommands. Every mount is under the persistent home (`/root`, i.e. `~`).
 *
 * Order matters and is fixed:
 *  1. the persistent private home over /root,
 *  2. the project binds: `~/projects` and, in Active mode, `~/projects-private`,
 *  3. the shared config folders: OpenCode over `~/.config/opencode`, Codex over `~/.codex-shared` (private `~/.codex` is the CODEX_HOME), Claude
 *     over `~/.claude` (full directories, so atomic config and credential saves land in shared),
 *  4. private exceptions nested on top of the OpenCode folder: service.json, service.json.tmp,
 *     node_modules.
 *
 * Step 4 must come after step 3, otherwise the directory bind would hide the private files. The
 * server's working directory is always `~` (see ProotCommands).
 */
data class GuestBinds(
    /** Persistent private home, bound over /root. Always present. */
    val homeHostPath: File,
    /** Mounted at ~/projects: shared projects when active, else the private workspace. */
    val projectsHostPath: File,
    /** The private workspace, mounted at ~/projects-private. Only in Active mode (null otherwise). */
    val privateProjectsHostPath: File?,
    /** Shared OpenCode config folder, bound over the guest config dir. Null when shared config is off. */
    val sharedOpencodeHostPath: File?,
    /** OpenCode private exceptions nested over the shared folder: (host path, guest path). */
    val privateExceptions: List<Pair<File, String>>,
    /** Shared Codex folder, bound over `~/.codex-shared` (only config entries are symlinked). Null when off. */
    val sharedCodexHostPath: File? = null,
    /** Shared Claude folder, bound over `~/.claude`. Null when shared config is off. */
    val sharedClaudeHostPath: File? = null,
) {
    /** Ordered `host:guest` specs for PRoot `-b`, following the fixed order above. */
    fun specs(): List<String> =
        buildList {
            add("${homeHostPath.absolutePath}:${SharedStorageLayout.GUEST_HOME}")
            add("${projectsHostPath.absolutePath}:${SharedStorageLayout.GUEST_PROJECTS}")
            privateProjectsHostPath?.let { add("${it.absolutePath}:${SharedStorageLayout.GUEST_PRIVATE_PROJECTS}") }
            sharedOpencodeHostPath?.let { add("${it.absolutePath}:${SharedStorageLayout.GUEST_OPENCODE_CONFIG}") }
            sharedCodexHostPath?.let { add("${it.absolutePath}:${SharedStorageLayout.GUEST_CODEX_SHARED}") }
            sharedClaudeHostPath?.let { add("${it.absolutePath}:${SharedStorageLayout.GUEST_CLAUDE_CONFIG}") }
            privateExceptions.forEach { (host, guest) -> add("${host.absolutePath}:$guest") }
        }

    companion object {
        /** Private-only: ~/projects is the private workspace. No second mount, no config bind. */
        fun privateOnly(
            home: File,
            privateWorkspace: File,
        ) = GuestBinds(
            homeHostPath = home,
            projectsHostPath = privateWorkspace,
            privateProjectsHostPath = null,
            sharedOpencodeHostPath = null,
            privateExceptions = emptyList(),
            sharedCodexHostPath = null,
            sharedClaudeHostPath = null,
        )

        /**
         * Off, but with the OpenCode private exceptions nested over the private config folder. This
         * keeps service.json/service.json.tmp/node_modules in one canonical place when the switch is
         * toggled: the running server reads and writes the same private files in both modes.
         */
        fun privateWithOpencodeExceptions(
            home: File,
            privateWorkspace: File,
            privateExceptionsDir: File,
            nodeModulesHostPath: File = File(privateExceptionsDir, SharedStorageLayout.NODE_MODULES),
        ) = GuestBinds(
            homeHostPath = home,
            projectsHostPath = privateWorkspace,
            privateProjectsHostPath = null,
            sharedOpencodeHostPath = null,
            privateExceptions = SharedStorageLayout.opencodePrivateExceptions(privateExceptionsDir, nodeModulesHostPath),
            sharedCodexHostPath = null,
            sharedClaudeHostPath = null,
        )

        /**
         * Shared: ~/projects is the shared projects folder, ~/projects-private the private workspace,
         * and the whole OpenCode, Codex and Claude folders over their guest config dirs.
         * [privateExceptionsDir] holds the OpenCode private files (service.json and node_modules).
         */
        fun shared(
            home: File,
            privateWorkspace: File,
            sharedProjects: File,
            sharedOpencode: File,
            privateExceptionsDir: File,
            nodeModulesHostPath: File = File(privateExceptionsDir, SharedStorageLayout.NODE_MODULES),
            sharedCodex: File? = null,
            sharedClaude: File? = null,
        ): GuestBinds {
            val exceptions = SharedStorageLayout.opencodePrivateExceptions(privateExceptionsDir, nodeModulesHostPath)
            return GuestBinds(
                homeHostPath = home,
                projectsHostPath = sharedProjects,
                privateProjectsHostPath = privateWorkspace,
                sharedOpencodeHostPath = sharedOpencode,
                privateExceptions = exceptions,
                sharedCodexHostPath = sharedCodex,
                sharedClaudeHostPath = sharedClaude,
            )
        }
    }
}
