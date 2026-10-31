package io.cocode.app.runtime.storage

import io.cocode.app.runtime.RuntimePaths
import io.cocode.app.runtime.SharedStorageController
import io.cocode.app.runtime.SharedStorageState
import io.cocode.app.runtime.SharedStorageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Persistence and platform facts, injected so the controller is unit-tested without Android. */
interface SharedStoragePrefs {
    fun enabled(): Boolean

    fun setEnabled(enabled: Boolean)

    /**
     * Persistent consent schema version for full-directory sharing (Codex/Claude credentials and
     * history). 0 means no consent. Default methods keep old test doubles source-compatible and
     * deliberately grant no consent.
     */
    fun fullDirectoryConsentVersion(): Int = 0

    fun setFullDirectoryConsentVersion(version: Int) {}
}

/** Platform facts: whether all-files access is granted, and whether the shared root can be used. */
fun interface SharedStoragePermission {
    fun granted(): Boolean
}

/**
 * The shared-storage feature: persists the switch, decides the status, prepares the shared folders
 * on activation, and remembers the binds the running server was started with (for restartRequired).
 */
class SharedStorageControllerImpl(
    private val paths: RuntimePaths,
    /** `/storage/emulated/0/CoCode`; injected so tests use a temporary directory. */
    private val hostRoot: File,
    private val prefs: SharedStoragePrefs,
    private val permission: SharedStoragePermission,
) : SharedStorageController {
    private val mutableState = MutableStateFlow(compute(startedBinds = null))
    override val state: StateFlow<SharedStorageState> = mutableState.asStateFlow()

    /** The status and binds of the running server; null when no server was started through us. */
    @Volatile private var startedBinds: Pair<SharedStorageStatus, List<String>>? = null

    override fun setEnabled(enabled: Boolean) {
        prefs.setEnabled(enabled)
        refresh()
    }

    /**
     * The only path that may grant full-directory sharing. The UI confirm dialog calls this after
     * the user accepts that Codex/Claude credentials and histories become public.
     */
    override fun setEnabledWithFullDirectoryConsent(enabled: Boolean) {
        if (enabled) {
            prefs.setFullDirectoryConsentVersion(SharedStorageLayout.FULL_DIRECTORY_CONSENT_VERSION)
        }
        prefs.setEnabled(enabled)
        refresh()
    }

    override fun refreshPermission() = refresh()

    private fun hasFullDirectoryConsent(): Boolean =
        prefs.fullDirectoryConsentVersion() >= SharedStorageLayout.FULL_DIRECTORY_CONSENT_VERSION

    /**
     * The binds for the next server start. Prepares the shared folders when the decision is Active:
     * creates them, seeds the config, migrates once per agent and writes the README. Failure to
     * prepare falls back to private.
     */
    fun prepareForStart(): GuestBinds {
        var status = decideStatus()
        val fullDirectoryConsent = hasFullDirectoryConsent()
        if (status == SharedStorageStatus.Active) {
            // Shared storage is a convenience: if it can't be prepared (FUSE refused, volume gone),
            // start on private storage rather than failing the whole server start.
            val prepared = runCatching { prepareShared(fullDirectoryConsent) }
            if (prepared.isFailure) {
                android.util.Log.w(
                    "CoCode",
                    "Shared storage migration conflict or persistence failure; falling back to the private copies " +
                        "(the UI state may still read NeedsPermission; this is not necessarily a permission problem)",
                    prepared.exceptionOrNull(),
                )
                status = SharedStorageStatus.NeedsPermission
            }
        }
        // Both modes need the OpenCode private placeholders because the exception binds are nested
        // over the config dir in both. Codex and Claude are whole-directory binds with no exceptions.
        runCatching {
            SharedStorageDecision.prepareOpencodePrivate(
                privateExceptionsDir = legacyOpencodeDir(),
                legacyConfigDir = File(paths.home, ".config/opencode"),
            )
        }.onFailure { android.util.Log.w("CoCode", "Could not prepare private config", it) }
        val binds = bindsFor(status, fullDirectoryConsent)
        // Git on shared storage needs its config before any repo is touched. Applied in every mode:
        // harmless when shared storage is off, since the gitdir include matches only ~/projects.
        runCatching { GitConfigWriter.apply(paths.home) }
            .onFailure { android.util.Log.w("CoCode", "Could not write the git config", it) }
        // PRoot can only bind onto paths that exist, so the mount points are created in the home first.
        // ~/projects is always needed, ~/.config/opencode is needed by the OpenCode exception binds in
        // both modes, and the rest exist only in Active.
        ensureMountPoints(binds)
        return binds
    }

    private fun bindsFor(
        status: SharedStorageStatus,
        fullDirectoryConsent: Boolean = hasFullDirectoryConsent(),
    ): GuestBinds =
        SharedStorageDecision.binds(
            status,
            paths.home,
            paths.workspace,
            hostRoot,
            legacyOpencodeDir(),
            fullDirectoryConsent,
        )

    private fun legacyOpencodeDir(): File = File(paths.root, "home-private/opencode")

    /**
     * Creates the directories PRoot binds onto inside the home. Only the mounts the current bind set
     * uses are needed; the OpenCode config dir is needed in both modes because its private
     * service/node_modules exceptions are nested there.
     */
    private fun ensureMountPoints(binds: GuestBinds) {
        SharedStorageLayout.mountPointsFor(paths.home, binds).forEach { it.mkdirs() }
    }

    /** Called by the server once a start has been issued with [binds]. */
    fun recordStarted(binds: GuestBinds) {
        val status = decideStatus()
        startedBinds = status to binds.specs()
        refresh()
    }

    /** Called when the server stops: nothing is running, so nothing needs a restart. */
    fun recordStopped() {
        startedBinds = null
        refresh()
    }

    private fun refresh() {
        mutableState.value = compute(startedBinds)
    }

    private fun compute(startedBinds: Pair<SharedStorageStatus, List<String>>?): SharedStorageState {
        val status = decideStatus()
        val fullDirectoryConsent = hasFullDirectoryConsent()
        val current = status to bindsFor(status, fullDirectoryConsent).specs()
        return SharedStorageState(
            status = status,
            hostRoot = hostRoot.absolutePath,
            restartRequired = SharedStorageDecision.restartRequired(startedBinds, current),
            needsConsent =
                status == SharedStorageStatus.Active && !fullDirectoryConsent,
        )
    }

    private fun decideStatus(): SharedStorageStatus =
        SharedStorageDecision.status(
            enabled = prefs.enabled(),
            permissionGranted = permission.granted(),
            sharedRootUsable = sharedRootUsable(),
        )

    /** The shared root can hold the folders: it exists or can be created, and is writable. */
    private fun sharedRootUsable(): Boolean =
        runCatching {
            hostRoot.mkdirs()
            hostRoot.isDirectory && hostRoot.canWrite()
        }.getOrDefault(false)

    /**
     * One-time migration and folder setup for Active. Each agent has its own marker, because the
     * shared folders are adopted independently. Private copies are never overwritten and symlinks
     * are skipped. OpenCode skips its private secrets; Codex and Claude copy every regular file
     * because their whole folders are shared.
     */
    private fun prepareShared(fullDirectoryConsent: Boolean) {
        val projects = SharedStorageLayout.projectsDir(hostRoot).apply { mkdirs() }
        check(projects.isDirectory) { "cannot create ${projects.path}" }

        migrateOnce(
            privateDir = File(paths.home, ".config/opencode"),
            sharedDir = SharedStorageLayout.opencodeDir(hostRoot),
            marker = File(legacyOpencodeDir(), ".migrated-to-shared"),
            policy = SharedStorageLayout.OPENCODE_MIGRATION_POLICY,
            ensure = SharedStorageLayout::ensureOpencodeMinimal,
            journalDir = File(legacyOpencodeDir(), ".migration-journal"),
        )
        if (fullDirectoryConsent) {
            migrateOnce(
                privateDir = File(paths.home, ".codex"),
                sharedDir = SharedStorageLayout.codexDir(hostRoot),
                marker = File(paths.root, "home-private/codex/.migrated-to-shared"),
                policy = SharedStorageLayout.CODEX_MIGRATION_POLICY,
                ensure = SharedStorageLayout::ensureCodexMinimal,
                journalDir = File(paths.root, "home-private/codex/.migration-journal"),
                // Version 2 is the full-directory policy (auth/sessions/history included). A marker
                // from the earlier config-only policy is upgraded once; existing shared files are not replaced.
                version = "2",
            )
            migrateOnce(
                privateDir = File(paths.home, ".claude"),
                sharedDir = SharedStorageLayout.claudeDir(hostRoot),
                marker = File(paths.root, "home-private/claude/.migrated-to-shared"),
                policy = SharedStorageLayout.CLAUDE_MIGRATION_POLICY,
                ensure = SharedStorageLayout::ensureClaudeMinimal,
                journalDir = File(paths.root, "home-private/claude/.migration-journal"),
                version = "2",
            )
        }

        File(hostRoot, SharedStorageLayout.README).writeText(
            SharedStorageLayout.readme(hostRoot, fullDirectoryShared = fullDirectoryConsent),
        )
    }

    /**
     * Runs one agent's migration once per policy version. After a successful run the shared folder is
     * authoritative for that version: a file the user deletes there must not come back from the stale
     * private copy on the next start. A newer [version] upgrades an existing marker once, copying only
     * files that are still missing in shared (never overwriting). The config folders are ensured on
     * every start.
     */
    private fun migrateOnce(
        privateDir: File,
        sharedDir: File,
        marker: File,
        policy: SharedStorageLayout.MigrationPolicy,
        ensure: (File) -> List<File>,
        journalDir: File,
        version: String = "1",
    ) {
        // Recovery first: before the target.exists skip inside the planner and before the
        // migration-complete marker. A pending conflict must block this agent's marker.
        SharedStorageLayout.recoverPending(journalDir, sharedDir)
        val upToDate = marker.isFile && runCatching { marker.readText().trim() }.getOrDefault("") == version
        if (!upToDate) {
            SharedStorageLayout.applyMigration(
                SharedStorageLayout.planMigration(privateDir, sharedDir, policy),
                sharedDir,
                journalDir,
            )
            marker.parentFile?.mkdirs()
            marker.writeText("$version\n")
        }
        ensure(sharedDir)
    }
}
