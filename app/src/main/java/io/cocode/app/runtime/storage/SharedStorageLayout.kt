package io.cocode.app.runtime.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import io.cocode.app.runtime.install.RuntimeArchive

/**
 * Layout of the shared agent-config folders.
 *
 * OpenCode, Codex and Claude are all bound as whole directories. That is what makes their config
 * entry points (`opencode.jsonc`, `config.toml`, `settings.json`) and their login files safe against
 * atomic-save breakage: the temporary file and the rename both happen inside the same shared
 * directory. The user explicitly accepted that the Codex and Claude folders contain live login
 * tokens and session history; shared storage permissions are not a secret boundary.
 *
 * Only OpenCode keeps nested private exceptions (`service.json`, its `.tmp`, `node_modules`). The
 * Codex and Claude directories are not selectively filtered: any nested file bind would break the
 * atomic rename that the agents use for their credentials and config.
 */
object SharedStorageLayout {
    const val PROJECTS = "projects"
    const val OPENCODE = "opencode"
    const val CODEX = "codex"
    const val CLAUDE = "claude"
    const val README = "README.txt"

    /**
     * Consent schema version for sharing the complete Codex/Claude folders. The user's accepted
     * version is persisted by [SharedStoragePrefs.fullDirectoryConsentVersion]; a value below this
     * constant keeps the legacy projects/OpenCode-only mapping.
     */
    const val FULL_DIRECTORY_CONSENT_VERSION = 1

    /**
     * Guest paths, all under the persistent home `~` (= /root). The server's working directory is `~`,
     * so the default project root and file browser start there.
     */
    const val GUEST_HOME = "/root"
    const val GUEST_PROJECTS = "/root/projects"
    const val GUEST_PRIVATE_PROJECTS = "/root/projects-private"
    const val GUEST_OPENCODE_CONFIG = "/root/.config/opencode"
    const val GUEST_CODEX_CONFIG = "/root/.codex"
    const val GUEST_CLAUDE_CONFIG = "/root/.claude"

    /** Mount points under the home bind, created in the home before PRoot binds onto them. */
    val MOUNT_POINTS: List<String> =
        listOf("projects", "projects-private", ".config/opencode", ".codex", ".claude")

    /**
     * The home-relative directories PRoot needs for [binds]: `projects` always, the private
     * workspace and config directories only when the binds mount them. The OpenCode config dir is
     * also needed in private mode because its service/node_modules exceptions are nested there.
     */
    fun mountPointsFor(
        home: File,
        binds: GuestBinds,
    ): List<File> =
        buildList {
            add(File(home, "projects"))
            if (binds.privateProjectsHostPath != null) add(File(home, "projects-private"))
            val hasOpencodeExceptions =
                binds.privateExceptions.any { it.second.startsWith("${GUEST_OPENCODE_CONFIG}/") }
            if (binds.sharedOpencodeHostPath != null || hasOpencodeExceptions) {
                add(File(home, ".config/opencode"))
            }
            if (binds.sharedCodexHostPath != null) add(File(home, ".codex"))
            if (binds.sharedClaudeHostPath != null) add(File(home, ".claude"))
        }

    /**
     * Config folders created when a tool folder is first shared. They are only empty containers;
     * no config file is invented and the whole tool directory is already bound.
     */
    val CODEX_CONFIG_DIRS = listOf("rules", "skills", "prompts")

    val CLAUDE_CONFIG_DIRS = listOf("rules", "agents", "commands", "skills")

    /** Holds a password, so it stays private. OpenCode also writes `<configFile>.tmp` next to it. */
    const val SERVICE_FILE = "service.json"
    const val SERVICE_TMP = "service.json.tmp"

    /** Package installs need symlinks, which shared storage cannot hold, so this stays private. */
    const val NODE_MODULES = "node_modules"

    const val DEFAULT_CONFIG = "{\"\$schema\":\"https://opencode.ai/config.json\"}\n"

    /** Config entry points: at least one must exist in the shared folder, else a default is created. */
    val CONFIG_CANDIDATES = listOf("opencode.jsonc", "opencode.json")

    /** OpenCode private names that are never migrated to, or copied from, the shared folder. */
    val PRIVATE_NAMES = setOf(SERVICE_FILE, SERVICE_TMP, NODE_MODULES)

    fun projectsDir(hostRoot: File) = File(hostRoot, PROJECTS)

    fun opencodeDir(hostRoot: File) = File(hostRoot, OPENCODE)

    fun codexDir(hostRoot: File) = File(hostRoot, CODEX)

    fun claudeDir(hostRoot: File) = File(hostRoot, CLAUDE)

    /**
     * The OpenCode private exceptions. The canonical private files live in the app-private
     * exception folder; [nodeModulesHostPath] may be the legacy private config tree so internal
     * absolute symlinks (including .l2s links) keep resolving. Nested binds are applied in both
     * modes.
     */
    fun opencodePrivateExceptions(
        privateExceptionsDir: File,
        nodeModulesHostPath: File = File(privateExceptionsDir, NODE_MODULES),
    ): List<Pair<File, String>> =
        listOf(SERVICE_FILE, SERVICE_TMP).map { name ->
            File(privateExceptionsDir, name) to "$GUEST_OPENCODE_CONFIG/$name"
        } + (nodeModulesHostPath to "$GUEST_OPENCODE_CONFIG/$NODE_MODULES")

    /**
     * Chooses the stable `node_modules` bind source without moving or rewriting it. A non-empty
     * canonical tree wins. Otherwise an existing legacy `~/.config/opencode/node_modules` (real
     * directory or symlink) is retained as the source, because moving it would break internal
     * absolute symlinks such as pnpm's `.l2s`. If neither exists, the canonical empty directory is
     * the source.
     */
    fun opencodeNodeModulesSource(
        privateExceptionsDir: File,
        legacyConfigDir: File?,
    ): File {
        val canonical = File(privateExceptionsDir, NODE_MODULES)
        if (isUsableNodeModules(canonical)) return canonical
        val legacy = legacyConfigDir?.let { File(it, NODE_MODULES) }
        if (legacy != null && isUsableNodeModules(legacy)) return legacy
        return canonical
    }

    private fun isUsableNodeModules(path: File): Boolean =
        Files.isSymbolicLink(path.toPath()) ||
            (path.isDirectory && path.listFiles().orEmpty().isNotEmpty())

    /** One step of the migration plan: copy [from] (private) to [to] (shared), or skip with a reason. */
    sealed interface Step {
        data class Copy(val from: File, val to: File) : Step

        data class Skip(val from: File, val reason: String) : Step
    }

    /**
     * What may be migrated. [privateNames] and [privatePatterns] apply to top-level entries (OpenCode
     * uses them for its service/node_modules exceptions). [skipEmptyPlaceholders] keeps the old
     * OpenCode rule; the Codex and Claude migrations copy every regular file, including empty ones,
     * because those folders are shared in full.
     */
    data class MigrationPolicy(
        val privateNames: Set<String> = emptySet(),
        val privatePatterns: List<Regex> = emptyList(),
        val allowTopLevel: (File) -> Boolean = { true },
        val skipEmptyPlaceholders: Boolean = true,
    )

    /** OpenCode migrates everything except its private service/node_modules entries. */
    val OPENCODE_MIGRATION_POLICY = MigrationPolicy(privateNames = PRIVATE_NAMES)

    /** Codex is shared in full, so every regular file (auth/history/sessions included) is migrated. */
    val CODEX_MIGRATION_POLICY = MigrationPolicy(skipEmptyPlaceholders = false)

    /** Claude is shared in full, so every regular file (credentials/history/sessions included) is migrated. */
    val CLAUDE_MIGRATION_POLICY = MigrationPolicy(skipEmptyPlaceholders = false)

    /**
     * Plans the one-time migration of private agent config into the shared folder. Rules:
     *  - symbolic links are never followed (shared storage cannot represent them);
     *  - a shared file that already exists is never overwritten;
     *  - directories merge recursively: only the entries missing in shared are planned;
     *  - OpenCode skips its private secrets and empty placeholders; Codex/Claude copy every regular
     *    file, including auth/history/session files, after the user enabled the feature.
     */
    fun planMigration(
        privateDir: File,
        sharedDir: File,
        policy: MigrationPolicy = MigrationPolicy(),
    ): List<Step> {
        val steps = mutableListOf<Step>()
        privateDir.listFiles().orEmpty().sortedBy { it.name }.forEach { source ->
            planEntry(source, File(sharedDir, source.name), steps, policy, topLevel = true)
        }
        return steps
    }

    private fun planEntry(
        source: File,
        target: File,
        steps: MutableList<Step>,
        policy: MigrationPolicy,
        topLevel: Boolean,
    ) {
        if (Files.isSymbolicLink(source.toPath())) {
            steps += Step.Skip(source, "symbolic link")
            return
        }
        if (Files.isSymbolicLink(target.toPath())) {
            steps += Step.Skip(source, "shared path is a symbolic link")
            return
        }
        if (topLevel) {
            if (!policy.allowTopLevel(source)) {
                steps += Step.Skip(source, "not shared")
                return
            }
            if (source.name in policy.privateNames || policy.privatePatterns.any { it.matches(source.name) }) {
                steps += Step.Skip(source, "private exception")
                return
            }
        }
        if (source.isDirectory) {
            if (target.exists() && !target.isDirectory) {
                steps += Step.Skip(source, "shared path is a file")
                return
            }
            source.listFiles().orEmpty().sortedBy { it.name }.forEach { child ->
                planEntry(child, File(target, child.name), steps, policy, topLevel = false)
            }
            return
        }
        if (target.exists()) {
            steps += Step.Skip(source, "shared file exists")
            return
        }
        if (source.length() == 0L && policy.skipEmptyPlaceholders) {
            steps += Step.Skip(source, "empty placeholder")
            return
        }
        steps += Step.Copy(source, target)
    }

    /**
     * Journaled migration. Recovery runs before any `target.exists -> skip` decision. A complete
     * app-private snapshot and a durable journal are written first; the public target is created
     * with `CREATE_NEW` and copied from that snapshot through the same channel. No rename or
     * truncate ever publishes the file. A matching complete target finalizes; a missing target is
     * retried; a partial/mismatching target is an explicit conflict that keeps the private source,
     * snapshot and journal intact.
     *
     * [copyToTarget] is injectable only for tests that simulate an interruption after the journal is
     * durable. Production uses [createTargetFromSnapshot].
     */
    fun applyMigration(
        steps: List<Step>,
        sharedDir: File,
        journalDir: File,
        copyToTarget: (snapshot: File, target: File) -> Unit = ::createTargetFromSnapshot,
    ): List<File> {
        journalDir.mkdirs()
        check(journalDir.isDirectory) { "cannot create migration journal directory" }
        val root = sharedDir.absoluteFile.toPath().normalize()
        recoverPending(journalDir, sharedDir)
        val copied = mutableListOf<File>()
        steps.filterIsInstance<Step.Copy>().forEach { step ->
            val parent = step.to.parentFile ?: return@forEach
            parent.mkdirs()
            if (Files.isSymbolicLink(step.from.toPath()) ||
                Files.isSymbolicLink(step.to.toPath()) ||
                step.to.exists()
            ) {
                return@forEach
            }
            val relative =
                root.relativize(step.to.absoluteFile.toPath().normalize()).toString().also {
                    if (it.isEmpty() || it.startsWith("..") || File(it).isAbsolute) {
                        throw MigrationConflictException("migration target escapes the shared folder")
                    }
                }
            val id = sha256Hex(relative.toByteArray(StandardCharsets.UTF_8))
            val snapshot = File(journalDir, "$id$SNAPSHOT_SUFFIX")
            val journal = File(journalDir, "$id$JOURNAL_SUFFIX")
            writeSnapshot(step.from, snapshot, journalDir)
            val entry =
                JournalEntry(
                    version = JOURNAL_VERSION,
                    targetRelative = relative,
                    snapshotName = snapshot.name,
                    length = snapshot.length(),
                    sha256 = RuntimeArchive.sha256(snapshot),
                    state = JOURNAL_PENDING,
                )
            try {
                writeJournal(journalDir, journal, entry)
            } catch (error: Throwable) {
                runCatching { Files.deleteIfExists(journal.toPath()) }
                runCatching { Files.deleteIfExists(snapshot.toPath()) }
                throw error
            }
            try {
                copyToTarget(snapshot, step.to)
            } catch (_: FileAlreadyExistsException) {
                // The public file appeared before our exclusive create. Preserve it and revoke only
                // this pending snapshot/journal; the private source is untouched.
                revokePending(journalDir, journal, snapshot)
                return@forEach
            } catch (error: Throwable) {
                // Keep the snapshot/journal so recovery can retry a missing target or report a
                // partial/mismatching target as a conflict. The private source is untouched.
                throw error
            }
            if (!matchesSnapshot(step.to, entry)) {
                throw MigrationConflictException(
                    "migration target differs after copy: ${entry.targetRelative}",
                )
            }
            // Mark committed durably before removing the snapshot/journal.
            writeJournal(journalDir, journal, entry.copy(state = JOURNAL_COMMITTED))
            cleanupCommitted(journalDir, journal, snapshot)
            copied += step.to
        }
        recoverPending(journalDir, sharedDir)
        return copied
    }

    /**
     * Resolves every pending journal before the caller checks its one-time migration marker. A
     * matching completed target is finalized; a missing target is retried from the app-private
     * snapshot; a partial or mismatching target is a conflict and is never overwritten or deleted.
     */
    fun recoverPending(
        journalDir: File,
        sharedDir: File,
    ): List<File> {
        if (!journalDir.exists()) return emptyList()
        if (!journalDir.isDirectory) {
            throw MigrationConflictException("migration journal path is not a directory")
        }
        val root = sharedDir.absoluteFile.toPath().normalize()
        val recovered = mutableListOf<File>()
        journalDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(JOURNAL_SUFFIX) && !Files.isSymbolicLink(it.toPath()) }
            .sortedBy { it.name }
            .forEach { journal ->
                val entry = readJournal(journal)
                val target = resolveTarget(root, entry.targetRelative)
                val snapshot = resolveSnapshot(journalDir, entry.snapshotName)
                if (target.exists()) {
                    // A complete target can be finalized even if the snapshot was already removed
                    // just before a crash; the journal's length/hash are authoritative.
                    if (Files.isSymbolicLink(target.toPath()) || !target.isFile || !matchesSnapshot(target, entry)) {
                        throw MigrationConflictException(
                            "pending migration target exists with different content: ${entry.targetRelative}",
                        )
                    }
                    cleanupCommitted(journalDir, journal, snapshot)
                    recovered += target
                } else {
                    if (!snapshot.isFile || !matchesSnapshot(snapshot, entry)) {
                        throw MigrationConflictException(
                            "pending migration snapshot is missing or does not match the journal: ${entry.targetRelative}",
                        )
                    }
                    try {
                        createTargetFromSnapshot(snapshot, target)
                    } catch (error: FileAlreadyExistsException) {
                        if (!target.isFile || !matchesSnapshot(target, entry)) {
                            throw MigrationConflictException(
                                "pending migration target appeared during retry: ${entry.targetRelative}",
                            )
                        }
                        cleanupCommitted(journalDir, journal, snapshot)
                        recovered += target
                        return@forEach
                    }
                    if (!matchesSnapshot(target, entry)) {
                        throw MigrationConflictException(
                            "pending migration retry produced different content: ${entry.targetRelative}",
                        )
                    }
                    cleanupCommitted(journalDir, journal, snapshot)
                    recovered += target
                }
            }
        return recovered
    }

    private fun createTargetFromSnapshot(
        snapshot: File,
        target: File,
    ) {
        target.parentFile?.mkdirs()
        FileChannel.open(
            target.toPath(),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { output ->
            FileChannel.open(snapshot.toPath(), StandardOpenOption.READ).use { input ->
                val size = input.size()
                var position = 0L
                while (position < size) {
                    val written = input.transferTo(position, size - position, output)
                    if (written <= 0L) {
                        throw IOException("cannot copy migration snapshot to target")
                    }
                    position += written
                }
            }
            output.force(true)
        }
    }

    private fun writeSnapshot(
        source: File,
        snapshot: File,
        journalDir: File,
    ) {
        val temp = Files.createTempFile(journalDir.toPath(), ".snapshot-", ".tmp").toFile()
        try {
            copyComplete(source, temp)
            Files.move(
                temp.toPath(),
                snapshot.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            fsyncDir(journalDir)
        } finally {
            runCatching { Files.deleteIfExists(temp.toPath()) }
        }
    }

    private fun writeJournal(
        journalDir: File,
        journal: File,
        entry: JournalEntry,
    ) {
        val temp = Files.createTempFile(journalDir.toPath(), ".journal-", ".tmp").toFile()
        try {
            FileOutputStream(temp).use { output ->
                output.write(entry.encode().toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            Files.move(
                temp.toPath(),
                journal.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            fsyncDir(journalDir)
        } finally {
            runCatching { Files.deleteIfExists(temp.toPath()) }
        }
    }

    private fun readJournal(journal: File): JournalEntry {
        val values =
            runCatching {
                journal.readLines(StandardCharsets.UTF_8)
                    .filter { it.contains('=') }
                    .associate { line ->
                        val index = line.indexOf('=')
                        line.substring(0, index) to line.substring(index + 1)
                    }
            }.getOrElse {
                throw MigrationConflictException("migration journal is unreadable")
            }
        val version = values["version"]?.toIntOrNull()
        val targetRelative =
            values["target"]?.let {
                runCatching { String(Base64.getUrlDecoder().decode(it), StandardCharsets.UTF_8) }
                    .getOrNull()
            }
        val snapshotName = values["snapshot"]
        val length = values["length"]?.toLongOrNull()
        val sha256 = values["sha256"]
        val state = values["state"]
        if (version != JOURNAL_VERSION || targetRelative.isNullOrEmpty() || length == null ||
            length < 0 || sha256 == null || !SHA256_HEX.matches(sha256) ||
            snapshotName == null || !SNAPSHOT_NAME.matches(snapshotName) ||
            (state != JOURNAL_PENDING && state != JOURNAL_COMMITTED)
        ) {
            throw MigrationConflictException("migration journal is invalid")
        }
        return JournalEntry(version, targetRelative, snapshotName, length, sha256, state)
    }

    private fun resolveTarget(
        root: java.nio.file.Path,
        relative: String,
    ): File {
        val target = File(root.toFile(), relative).absoluteFile.toPath().normalize()
        if (target == root || !target.startsWith(root)) {
            throw MigrationConflictException("migration journal target escapes the shared folder")
        }
        return target.toFile()
    }

    private fun resolveSnapshot(
        journalDir: File,
        name: String,
    ): File {
        if (!SNAPSHOT_NAME.matches(name)) {
            throw MigrationConflictException("migration journal snapshot name is invalid")
        }
        return File(journalDir, name)
    }

    private fun matchesSnapshot(
        file: File,
        entry: JournalEntry,
    ): Boolean =
        file.isFile && file.length() == entry.length &&
            RuntimeArchive.sha256(file).equals(entry.sha256, ignoreCase = true)

    private fun cleanupCommitted(
        journalDir: File,
        journal: File,
        snapshot: File,
    ) {
        Files.deleteIfExists(snapshot.toPath())
        Files.deleteIfExists(journal.toPath())
        fsyncDir(journalDir)
    }

    private fun revokePending(
        journalDir: File,
        journal: File,
        snapshot: File,
    ) {
        Files.deleteIfExists(snapshot.toPath())
        Files.deleteIfExists(journal.toPath())
        fsyncDir(journalDir)
    }

    private fun fsyncDir(dir: File) {
        FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private fun copyComplete(
        from: File,
        temp: File,
    ) {
        FileInputStream(from).use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class JournalEntry(
        val version: Int,
        val targetRelative: String,
        val snapshotName: String,
        val length: Long,
        val sha256: String,
        val state: String,
    ) {
        fun encode(): String =
            buildString {
                append("version=").append(version).append('\n')
                append("target=")
                    .append(Base64.getUrlEncoder().withoutPadding().encodeToString(targetRelative.toByteArray(StandardCharsets.UTF_8)))
                    .append('\n')
                append("snapshot=").append(snapshotName).append('\n')
                append("length=").append(this@JournalEntry.length).append('\n')
                append("sha256=").append(sha256).append('\n')
                append("state=").append(state).append('\n')
            }
    }

    private const val JOURNAL_VERSION = 1
    private const val JOURNAL_PENDING = "pending"
    private const val JOURNAL_COMMITTED = "committed"
    private const val JOURNAL_SUFFIX = ".journal"
    private const val SNAPSHOT_SUFFIX = ".snapshot"
    private val SNAPSHOT_NAME = Regex("^[0-9a-f]{64}\\.snapshot$")
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

    /**
     * Guarantees a usable OpenCode config entry point after migration: when the shared folder has
     * neither opencode.jsonc nor opencode.json, creates opencode.jsonc with the default content.
     * Also creates the skills/agents/commands folders so the guest finds them.
     */
    fun ensureMinimal(sharedDir: File): List<File> = ensureOpencodeMinimal(sharedDir)

    fun ensureOpencodeMinimal(sharedDir: File): List<File> {
        val created = mutableListOf<File>()
        sharedDir.mkdirs()
        if (CONFIG_CANDIDATES.none { File(sharedDir, it).isFile }) {
            File(sharedDir, CONFIG_CANDIDATES.first()).also {
                it.writeText(DEFAULT_CONFIG)
                created += it
            }
        }
        listOf("skills", "agents", "commands").forEach { name ->
            File(sharedDir, name).takeIf { !it.exists() }?.let {
                it.mkdirs()
                created += it
            }
        }
        return created
    }

    /**
     * Creates the Codex config folders. No `config.toml` is generated: an empty TOML would be
     * invalid and would hide the user's real config in the private home.
     */
    fun ensureCodexMinimal(sharedDir: File): List<File> {
        val created = mutableListOf<File>()
        sharedDir.mkdirs()
        CODEX_CONFIG_DIRS.forEach { name ->
            File(sharedDir, name).takeIf { !it.exists() }?.let {
                it.mkdirs()
                created += it
            }
        }
        return created
    }

    /**
     * Creates the Claude config folders. No `settings.json` or `CLAUDE.md` is generated; the user's
     * private files are migrated into the shared folder instead.
     */
    fun ensureClaudeMinimal(sharedDir: File): List<File> {
        val created = mutableListOf<File>()
        sharedDir.mkdirs()
        CLAUDE_CONFIG_DIRS.forEach { name ->
            File(sharedDir, name).takeIf { !it.exists() }?.let {
                it.mkdirs()
                created += it
            }
        }
        return created
    }

    /** The README text. English. Prominent warning because Codex/Claude are mapped in full. */
    fun readme(
        hostRoot: File,
        fullDirectoryShared: Boolean = true,
    ): String = if (fullDirectoryShared) fullDirectoryReadme(hostRoot) else legacyReadme(hostRoot)

    private fun fullDirectoryReadme(hostRoot: File): String =
        """
        CoCode shared folder
        ========================

        ${hostRoot.absolutePath}/
          projects/   Your project folders. Inside the Linux environment they appear as
                      ~/projects (the home directory is the server's working directory).
          opencode/   The OpenCode global config folder. It is bound over ~/.config/opencode,
                      so every file here is live config: opencode.jsonc, AGENTS.md, skills/,
                      agents/, commands/, and any other file OpenCode or its plugins read.
          codex/      The whole Codex folder, bound live over ~/.codex. It contains config.toml,
                      AGENTS.md, rules/, skills/, prompts/ AND the login token (auth.json),
                      session transcripts, history, logs and SQLite state.
          claude/     The whole Claude folder, bound live over ~/.claude. It contains
                      settings.json, CLAUDE.md, rules/, agents/, commands/, skills/ AND the
                      login token (.credentials.json), transcripts, history and session state.

        PUBLIC STORAGE WARNING
        ----------------------
        The codex/ and claude/ folders include live authentication tokens and full session history.
        Anyone and any app with access to shared storage may read them. Android/FUSE permissions
        are NOT a secret boundary. Enable shared storage only if you accept that these credentials
        and conversations are readable outside the app.

        Claude's ~/.claude.json file is outside ~/.claude and is NOT mapped. It stays private.

        On disable: the app does not copy changed public files back into the private home. Changes
        made while shared storage was on stay in this folder (and are used again when you turn it
        back on). The private copies are not deleted and are used while shared storage is off.
        Merge or copy manually if you want the public edits in private mode. The switch UI should
        warn about this before disabling.

        If a copy was interrupted, a partially written public file can remain. The app then falls
        back to the private copies and logs the conflict instead of accepting or overwriting that
        file. Delete the incomplete public file to retry from the private snapshot.

        Kept private to the app (never placed here, not readable by other apps):
          - OpenCode's service.json, service.json.tmp and node_modules,
          - ~/.local/share/opencode and T3 Code's data directory,
          - ~/.claude.json (outside the mapped ~/.claude folder).

        Caveat: shared storage has no symlinks and no executable bits. Projects that rely on
        symlinks, node_modules or executable scripts can misbehave here, and git may report
        mode changes. For those projects use ~/projects-private, which is the app's private
        workspace.
        """.trimIndent() + "\n"

    private fun legacyReadme(hostRoot: File): String =
        """
        CoCode shared folder
        ========================

        ${hostRoot.absolutePath}/
          projects/   Your project folders. Inside the Linux environment they appear as
                      ~/projects (the home directory is the server's working directory).
          opencode/   The OpenCode global config folder, bound live over ~/.config/opencode.

        Codex and Claude Code remain private in this legacy mode. Their folders are shared only
        after you confirm full-directory sharing in the app; that confirmation includes their
        login tokens and session history.

        Kept private to the app (never placed here, not readable by other apps):
          - the full Codex and Claude Code folders (including auth.json and .credentials.json),
          - ~/.claude.json,
          - OpenCode's service.json, service.json.tmp and node_modules,
          - ~/.local/share/opencode and T3 Code's data directory.

        On disable: the app does not copy changed public files back into the private home.
        The public data stays in this folder. The switch UI should warn about this.

        Caveat: shared storage has no symlinks and no executable bits. Use ~/projects-private
        for projects that need node_modules.
        """.trimIndent() + "\n"
}

/** A pending migration could not be completed without overwriting or accepting a mismatching file. */
class MigrationConflictException(message: String) : IOException(message)
