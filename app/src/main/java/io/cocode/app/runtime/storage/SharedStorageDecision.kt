package io.cocode.app.runtime.storage

import io.cocode.app.runtime.SharedStorageStatus
import java.io.File

/**
 * The status decision and the bind set for one server start. Pure: the caller supplies the facts
 * (enabled, permission, whether the shared folder can be created) and gets back what to do.
 */
object SharedStorageDecision {
    /** Off unless enabled; NeedsPermission when enabled without access; Active only when everything holds. */
    fun status(
        enabled: Boolean,
        permissionGranted: Boolean,
        sharedRootUsable: Boolean,
    ): SharedStorageStatus =
        when {
            !enabled -> SharedStorageStatus.Off
            !permissionGranted || !sharedRootUsable -> SharedStorageStatus.NeedsPermission
            else -> SharedStorageStatus.Active
        }

    /**
     * The binds a server start uses for [status]. Active requires the shared folders to exist.
     *
     * Codex and Claude are whole-directory binds only when [fullDirectoryConsent] is true. An
     * existing enabled user without the current consent version keeps the legacy projects/OpenCode
     * mapping; the UI asks for consent before the credential-bearing folders are shared.
     *
     * The OpenCode private exception folder is the same in both modes, so toggling the switch never
     * changes which service.json/node_modules the server reads and writes.
     */
    fun binds(
        status: SharedStorageStatus,
        home: File,
        privateWorkspace: File,
        hostRoot: File,
        privateExceptionsDir: File,
        fullDirectoryConsent: Boolean,
    ): GuestBinds =
        when (status) {
            SharedStorageStatus.Active ->
                GuestBinds.shared(
                    home = home,
                    privateWorkspace = privateWorkspace,
                    sharedProjects = SharedStorageLayout.projectsDir(hostRoot),
                    sharedOpencode = SharedStorageLayout.opencodeDir(hostRoot),
                    privateExceptionsDir = privateExceptionsDir,
                    nodeModulesHostPath =
                        SharedStorageLayout.opencodeNodeModulesSource(
                            privateExceptionsDir,
                            File(home, ".config/opencode"),
                        ),
                    sharedCodex =
                        if (fullDirectoryConsent) SharedStorageLayout.codexDir(hostRoot) else null,
                    sharedClaude =
                        if (fullDirectoryConsent) SharedStorageLayout.claudeDir(hostRoot) else null,
                )
            SharedStorageStatus.Off, SharedStorageStatus.NeedsPermission ->
                GuestBinds.privateWithOpencodeExceptions(
                    home = home,
                    privateWorkspace = privateWorkspace,
                    privateExceptionsDir = privateExceptionsDir,
                    nodeModulesHostPath =
                        SharedStorageLayout.opencodeNodeModulesSource(
                            privateExceptionsDir,
                            File(home, ".config/opencode"),
                        ),
                )
        }

    /**
     * Creates the OpenCode private files the binds need before the start. Existing content is never
     * overwritten. A legacy private config copy is adopted when the exception copy is empty, or when
     * the private config copy is newer (the user last ran with shared storage off).
     *
     * Codex and Claude have no private exceptions: their whole folders are either shared or private.
     */
    fun prepareOpencodePrivate(
        privateExceptionsDir: File,
        legacyConfigDir: File? = null,
    ): List<File> {
        val created = mutableListOf<File>()
        privateExceptionsDir.mkdirs()
        val target = File(privateExceptionsDir, SharedStorageLayout.SERVICE_FILE)
        val legacy = legacyConfigDir?.let { File(it, SharedStorageLayout.SERVICE_FILE) }
        val legacyHasContent = legacy != null && legacy.isFile && legacy.length() > 0
        val targetEmpty = !target.exists() || target.length() == 0L
        val legacyIsNewer =
            legacyHasContent && target.isFile && target.length() > 0 &&
                legacy!!.lastModified() > target.lastModified()
        val adoptLegacy = legacyHasContent && (targetEmpty || legacyIsNewer)
        if (adoptLegacy) {
            legacy!!.copyTo(target, overwrite = true)
            created += target
        } else if (!target.exists()) {
            target.createNewFile()
            created += target
        }
        listOf(SharedStorageLayout.SERVICE_TMP, SharedStorageLayout.NODE_MODULES).forEach { name ->
            val path = File(privateExceptionsDir, name)
            if (name == SharedStorageLayout.NODE_MODULES) {
                val chosen =
                    SharedStorageLayout.opencodeNodeModulesSource(privateExceptionsDir, legacyConfigDir)
                if (chosen == path && !path.exists()) {
                    path.mkdirs()
                    created += path
                }
            } else if (!path.exists()) {
                path.createNewFile()
                created += path
            }
        }
        return created
    }

    /**
     * True when a running server was started with [started] binds but the current decision is
     * [current]. Compares the status and the exact bind list, so a changed path also counts.
     */
    fun restartRequired(
        started: Pair<SharedStorageStatus, List<String>>?,
        current: Pair<SharedStorageStatus, List<String>>,
    ): Boolean = started != null && started != current
}
