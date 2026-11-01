package io.cocode.app.runtime.storage

import io.cocode.app.runtime.SharedStorageStatus
import io.cocode.app.runtime.process.ProotCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SharedStorageTest {
    @get:Rule val folder = TemporaryFolder()

    private var journalCounter = 0

    private fun newJournalDir(): File = folder.newFolder("journal-${journalCounter++}")

    private fun migrate(
        steps: List<SharedStorageLayout.Step>,
        sharedDir: File,
        journalDir: File = newJournalDir(),
        copyToTarget: ((File, File) -> Unit)? = null,
    ): List<File> =
        if (copyToTarget == null) {
            SharedStorageLayout.applyMigration(steps, sharedDir, journalDir)
        } else {
            SharedStorageLayout.applyMigration(steps, sharedDir, journalDir, copyToTarget)
        }

    private val proot = File("/native/libcocode_proot.so")
    private val rootfs = File("/data/runtime/environment/rootfs")
    private val home = File("/data/runtime/home")
    private val privateWs = File("/data/runtime/workspace")
    private val hostRoot = File("/storage/emulated/0/CoCode")
    private val exceptions = File("/data/runtime/home-private/opencode")
    private val allFilesPolicy = SharedStorageLayout.MigrationPolicy(skipEmptyPlaceholders = false)

    /** The `host:guest` binds we add. The fixed /dev /proc /sys /system mounts have no colon and are skipped. */
    private fun List<String>.pairs(): List<String> =
        zipWithNext().filter { it.first == "-b" && it.second.contains(':') }.map { it.second }

    private fun activeBinds() =
        SharedStorageDecision.binds(SharedStorageStatus.Active, home, privateWs, hostRoot, exceptions, true)

    private fun activePairs() = ProotCommands.serverCommand(proot, rootfs, activeBinds(), emptyList()).pairs()

    // --- GuestBinds order and content ---

    @Test
    fun `off binds the private workspace only at projects, home first, with no second mount`() {
        val cmd = ProotCommands.serverCommand(proot, rootfs, GuestBinds.privateOnly(home, privateWs), emptyList())

        assertEquals(
            listOf(
                "/data/runtime/home:/root",
                "/data/runtime/workspace:/root/projects",
            ),
            cmd.pairs(),
        )
        assertEquals("/root", cmd[cmd.indexOf("-w") + 1])
    }

    @Test
    fun `active binds in the fixed order - home, projects, whole tool dirs, then opencode private exceptions`() {
        assertEquals(
            listOf(
                "/data/runtime/home:/root",
                "/storage/emulated/0/CoCode/projects:/root/projects",
                "/data/runtime/workspace:/root/projects-private",
                "/storage/emulated/0/CoCode/opencode:/root/.config/opencode",
                "/storage/emulated/0/CoCode/codex:/root/.codex-shared",
                "/storage/emulated/0/CoCode/claude:/root/.claude",
                "/data/runtime/home-private/opencode/service.json:/root/.config/opencode/service.json",
                "/data/runtime/home-private/opencode/service.json.tmp:/root/.config/opencode/service.json.tmp",
                "/data/runtime/home-private/opencode/node_modules:/root/.config/opencode/node_modules",
            ),
            activePairs(),
        )
        val command = ProotCommands.serverCommand(proot, rootfs, activeBinds(), emptyList())
        assertEquals("/root", command[command.indexOf("-w") + 1])
    }

    @Test
    fun `no bind targets the old workspace mount points`() {
        val all = activePairs() + ProotCommands.serverCommand(proot, rootfs, GuestBinds.privateOnly(home, privateWs), emptyList()).pairs()

        assertFalse(all.any { it.endsWith(":/workspace") || it.endsWith(":/workspace-private") })
    }

    @Test
    fun `shared projects mount at home projects and the private workspace at home projects-private`() {
        val pairs = activePairs()

        assertTrue(pairs.contains("/storage/emulated/0/CoCode/projects:/root/projects"))
        assertTrue(pairs.contains("/data/runtime/workspace:/root/projects-private"))
    }

    @Test
    fun `the mount points under the home are the ones the binds target`() {
        val binds = activeBinds()
        val exceptionTargets = binds.privateExceptions.map { it.second }.toSet()
        val configTargets =
            binds.specs()
                .map { it.substringAfter(':') }
                .filter { it.startsWith("/root/") && it !in exceptionTargets }
                .map { it.removePrefix("/root/") }

        val expectedBindDirs = (SharedStorageLayout.MOUNT_POINTS - ".codex").toSet()
        assertEquals(expectedBindDirs, configTargets.toSet())
        assertTrue(
            "the private .codex dir is still a mount point for config links",
            SharedStorageLayout.mountPointsFor(folder.root, binds).any { it.name == ".codex" },
        )
    }

    @Test
    fun `off mode creates only projects in the home, not the config dirs`() {
        val h = folder.newFolder("home-off")
        val binds = GuestBinds.privateOnly(h, File(h, "unused-ws"))

        SharedStorageLayout.mountPointsFor(h, binds).forEach { it.mkdirs() }

        assertTrue(File(h, "projects").isDirectory)
        assertFalse(File(h, "projects-private").exists())
        assertFalse(File(h, ".config/opencode").exists())
        assertFalse(File(h, ".codex").exists())
        assertFalse(File(h, ".claude").exists())
    }

    @Test
    fun `active mode creates projects, projects-private and all three tool dirs in the home`() {
        val h = folder.newFolder("home-active")
        val binds = SharedStorageDecision.binds(
            SharedStorageStatus.Active,
            h,
            File(h, "ws"),
            hostRoot,
            File(h, "exceptions"),
            true,
        )

        SharedStorageLayout.mountPointsFor(h, binds).forEach { it.mkdirs() }

        assertTrue(File(h, "projects").isDirectory)
        assertTrue(File(h, "projects-private").isDirectory)
        assertTrue(File(h, ".config/opencode").isDirectory)
        assertTrue(File(h, ".codex").isDirectory)
        assertTrue(File(h, ".codex-shared").isDirectory)
        assertTrue(File(h, ".claude").isDirectory)
    }

    @Test
    fun `the opencode private exceptions come after every directory bind so they are not hidden`() {
        val pairs = activePairs()
        val opencodeDir = pairs.indexOf("/storage/emulated/0/CoCode/opencode:/root/.config/opencode")
        val codexDir = pairs.indexOf("/storage/emulated/0/CoCode/codex:/root/.codex-shared")
        val claudeDir = pairs.indexOf("/storage/emulated/0/CoCode/claude:/root/.claude")
        val firstException = pairs.indexOfFirst { it.endsWith(":/root/.config/opencode/service.json") }

        assertTrue(opencodeDir in 0 until firstException)
        assertTrue(codexDir in 0 until firstException)
        assertTrue(claudeDir in 0 until firstException)
    }

    @Test
    fun `no single-file bind is made for any shared config content`() {
        val shared = activePairs().filter { it.startsWith("/storage/emulated/0/CoCode/") }

        assertEquals(
            listOf(
                "/storage/emulated/0/CoCode/projects:/root/projects",
                "/storage/emulated/0/CoCode/opencode:/root/.config/opencode",
                "/storage/emulated/0/CoCode/codex:/root/.codex-shared",
                "/storage/emulated/0/CoCode/claude:/root/.claude",
            ),
            shared,
        )
    }

    @Test
    fun `codex uses a separate shared dir while claude is a whole-dir bind`() {
        val specs = activeBinds().specs()

        assertEquals(1, bindTargetCount(specs, ":/root/.codex-shared"))
        assertEquals(0, bindTargetCount(specs, ":/root/.codex"))
        assertEquals(1, bindTargetCount(specs, ":/root/.claude"))
        assertFalse("~/.claude.json must stay private", specs.any { it.contains("/root/.claude.json") })
        assertFalse("no Claude subdir bind", specs.any { it.contains(":/root/.claude/") })
        assertFalse(
            "credentials are covered by the whole-dir bind, not a nested file bind",
            specs.any { it.endsWith(":/root/.claude/.credentials.json") },
        )
    }

    private fun bindTargetCount(
        specs: List<String>,
        suffix: String,
    ): Int = specs.count { it.endsWith(suffix) }

    // --- Migration matrix ---

    @Test
    fun `opencode migration copies regular config files and skips secrets and node_modules`() {
        val priv = folder.newFolder("priv1")
        File(priv, "oh-my-opencode-slim.json").writeText("{\"a\":1}")
        File(priv, "service.json").writeText("{\"password\":\"x\"}")
        File(priv, "service.json.tmp").writeText("{\"password\":\"y\"}")
        File(priv, "node_modules/pkg").apply { mkdirs(); resolve("index.js").writeText("m") }
        val shared = File(folder.root, "shared1")

        val copied =
            migrate(
                SharedStorageLayout.planMigration(priv, shared, SharedStorageLayout.OPENCODE_MIGRATION_POLICY),
                shared,
            )

        assertEquals("{\"a\":1}", File(shared, "oh-my-opencode-slim.json").readText())
        assertFalse(File(shared, "service.json").exists())
        assertFalse(File(shared, "service.json.tmp").exists())
        assertFalse(File(shared, "node_modules").exists())
        assertEquals(listOf(File(shared, "oh-my-opencode-slim.json")), copied)
    }

    @Test
    fun `migration never overwrites an existing shared file`() {
        val priv = folder.newFolder("priv2")
        File(priv, "opencode.jsonc").writeText("private")
        val shared = folder.newFolder("shared2")
        File(shared, "opencode.jsonc").writeText("user")

        migrate(SharedStorageLayout.planMigration(priv, shared), shared)

        assertEquals("user", File(shared, "opencode.jsonc").readText())
    }

    @Test
    fun `a zero-byte opencode placeholder is skipped`() {
        val priv = folder.newFolder("priv3")
        File(priv, "AGENTS.md").writeText("")
        val shared = File(folder.root, "shared3")

        migrate(
            SharedStorageLayout.planMigration(priv, shared, SharedStorageLayout.OPENCODE_MIGRATION_POLICY),
            shared,
        )

        assertFalse(File(shared, "AGENTS.md").exists())
    }

    @Test
    fun `directories merge recursively without overwriting existing files`() {
        val priv = folder.newFolder("priv5")
        File(priv, "skills/a").apply { mkdirs(); resolve("SKILL.md").writeText("private a") }
        File(priv, "skills/b").apply { mkdirs(); resolve("SKILL.md").writeText("private b") }
        val shared = folder.newFolder("shared5")
        File(shared, "skills/a").apply { mkdirs(); resolve("SKILL.md").writeText("user a") }

        migrate(SharedStorageLayout.planMigration(priv, shared), shared)

        assertEquals("user a", File(shared, "skills/a/SKILL.md").readText())
        assertEquals("private b", File(shared, "skills/b/SKILL.md").readText())
    }

    @Test
    fun `symbolic links in the private folder are skipped, never followed`() {
        val priv = folder.newFolder("priv6")
        val target = folder.newFile("outside.txt").apply { writeText("outside") }
        java.nio.file.Files.createSymbolicLink(File(priv, "link.json").toPath(), target.toPath())
        val shared = File(folder.root, "shared6")

        val steps = SharedStorageLayout.planMigration(priv, shared)

        assertTrue(steps.any { it is SharedStorageLayout.Step.Skip && it.reason == "symbolic link" })
        assertFalse(File(shared, "link.json").exists())
    }

    @Test
    fun `a shared target symlink is skipped instead of being written through`() {
        val priv = folder.newFolder("priv-symlink-target")
        File(priv, "config.toml").writeText("private")
        val shared = folder.newFolder("shared-symlink-target")
        val outside = folder.newFile("outside-target.txt").apply { writeText("outside") }
        java.nio.file.Files.createSymbolicLink(File(shared, "config.toml").toPath(), outside.toPath())

        val steps = SharedStorageLayout.planMigration(priv, shared, allFilesPolicy)
        migrate(steps, shared)

        assertTrue(
            steps.any { it is SharedStorageLayout.Step.Skip && it.reason == "shared path is a symbolic link" },
        )
        assertEquals("outside", outside.readText())
    }

    @Test
    fun `codex config migration copies only the shared config entries`() {
        val priv = folder.newFolder("codex-private")
        File(priv, "config.toml").writeText("model = \"gpt-5\"\n")
        File(priv, "AGENTS.md").writeText("rules")
        File(priv, "rules/a.md").apply { parentFile.mkdirs(); writeText("a") }
        File(priv, "skills/s/SKILL.md").apply { parentFile.mkdirs(); writeText("s") }
        File(priv, "auth.json").writeText("{\"token\":\"SYNTHETIC_CODEX_AUTH\"}")
        File(priv, "auth.lock").writeText("lock")
        File(priv, "sessions/2026/01.jsonl").apply { parentFile.mkdirs(); writeText("SYNTHETIC_SESSION") }
        File(priv, "sqlite/state_5.sqlite").apply { parentFile.mkdirs(); writeText("state") }
        File(priv, "history.jsonl").writeText("SYNTHETIC_HISTORY")
        val shared = File(folder.root, "codex-shared")

        val steps = SharedStorageLayout.planMigration(priv, shared, SharedStorageLayout.CODEX_MIGRATION_POLICY)
        migrate(steps, shared)

        assertEquals("model = \"gpt-5\"\n", File(shared, "config.toml").readText())
        assertEquals("rules", File(shared, "AGENTS.md").readText())
        assertEquals("a", File(shared, "rules/a.md").readText())
        assertEquals("s", File(shared, "skills/s/SKILL.md").readText())
        listOf("auth.json", "auth.lock", "sessions", "sqlite", "history.jsonl").forEach {
            assertFalse("$it must stay private", File(shared, it).exists())
        }
    }

    @Test
    fun `codex old full-layout upgrade copies state back to private and skips locks`() {
        val priv = folder.newFolder("codex-upgrade-private")
        File(priv, "history.jsonl").writeText("PRIVATE_HISTORY")
        val shared = folder.newFolder("codex-upgrade-shared")
        File(shared, "auth.json").writeText("{\"token\":\"SYNTHETIC_UPGRADE\"}")
        File(shared, "sessions/2026/01.jsonl").apply { parentFile.mkdirs(); writeText("SHARED_SESSION") }
        File(shared, "sessions/2026/old.lock").apply { parentFile.mkdirs(); writeText("lock") }
        File(shared, "history.jsonl").writeText("SHARED_HISTORY")
        File(shared, "log/x.log").apply { parentFile.mkdirs(); writeText("log") }
        File(shared, "auth.lock").writeText("lock")
        File(shared, "config.toml").writeText("shared config")

        migrate(
            SharedStorageLayout.planMigration(shared, priv, SharedStorageLayout.CODEX_UPGRADE_POLICY),
            priv,
        )

        assertEquals("{\"token\":\"SYNTHETIC_UPGRADE\"}", File(priv, "auth.json").readText())
        assertEquals("SHARED_SESSION", File(priv, "sessions/2026/01.jsonl").readText())
        assertEquals("PRIVATE_HISTORY", File(priv, "history.jsonl").readText())
        assertEquals("log", File(priv, "log/x.log").readText())
        assertFalse("lock files are skipped", File(priv, "auth.lock").exists())
        assertFalse("nested lock files are skipped", File(priv, "sessions/2026/old.lock").exists())
        assertFalse("config entries are not copied back", File(priv, "config.toml").exists())
    }

    @Test
    fun `claude migration copies every regular file including credentials and transcripts`() {
        val priv = folder.newFolder("claude-private")
        File(priv, "settings.json").writeText("{\"permissions\":{}}")
        File(priv, "CLAUDE.md").writeText("global rules")
        File(priv, "rules/r.md").apply { parentFile.mkdirs(); writeText("r") }
        File(priv, "agents/a.md").apply { parentFile.mkdirs(); writeText("a") }
        File(priv, "commands/c.md").apply { parentFile.mkdirs(); writeText("c") }
        File(priv, "skills/s/SKILL.md").apply { parentFile.mkdirs(); writeText("s") }
        File(priv, ".credentials.json").writeText("{\"token\":\"SYNTHETIC_CLAUDE_AUTH\"}")
        File(priv, "projects/p.jsonl").apply { parentFile.mkdirs(); writeText("SYNTHETIC_TRANSCRIPT") }
        File(priv, "history.jsonl").writeText("SYNTHETIC_HISTORY")
        File(priv, "file-history/f").apply { parentFile.mkdirs(); writeText("history") }
        val target = folder.newFile("claude-outside.txt").apply { writeText("outside") }
        java.nio.file.Files.createSymbolicLink(File(priv, "link.json").toPath(), target.toPath())
        val shared = File(folder.root, "claude-shared")

        migrate(
            SharedStorageLayout.planMigration(priv, shared, SharedStorageLayout.CLAUDE_MIGRATION_POLICY),
            shared,
        )

        assertEquals("{\"permissions\":{}}", File(shared, "settings.json").readText())
        assertEquals("{\"token\":\"SYNTHETIC_CLAUDE_AUTH\"}", File(shared, ".credentials.json").readText())
        assertEquals("SYNTHETIC_TRANSCRIPT", File(shared, "projects/p.jsonl").readText())
        assertEquals("SYNTHETIC_HISTORY", File(shared, "history.jsonl").readText())
        assertEquals("global rules", File(shared, "CLAUDE.md").readText())

        assertFalse(File(shared, "link.json").exists())
    }

    @Test
    fun `ensureMinimal adds a default config only when no opencode json or jsonc exists`() {
        val shared = folder.newFolder("shared7")
        File(shared, "opencode.json").writeText("{}")

        SharedStorageLayout.ensureMinimal(shared)

        assertFalse("an existing opencode.json means no default is created", File(shared, "opencode.jsonc").exists())
        listOf("skills", "agents", "commands").forEach { assertTrue(File(shared, it).isDirectory) }

        val empty = folder.newFolder("shared8")
        SharedStorageLayout.ensureMinimal(empty)
        assertEquals(SharedStorageLayout.DEFAULT_CONFIG, File(empty, "opencode.jsonc").readText())
    }

    @Test
    fun `ensureCodexMinimal never invents an empty config toml and creates the safe folders`() {
        val shared = folder.newFolder("codex-minimal")

        SharedStorageLayout.ensureCodexMinimal(shared)

        assertFalse("an empty TOML would be invalid and hide the real config", File(shared, "config.toml").exists())
        listOf("rules", "skills", "prompts").forEach { assertTrue(File(shared, it).isDirectory) }
    }

    @Test
    fun `ensureClaudeMinimal never invents settings json or CLAUDE md and creates the config folders`() {
        val shared = folder.newFolder("claude-minimal")

        SharedStorageLayout.ensureClaudeMinimal(shared)

        assertFalse(File(shared, "settings.json").exists())
        assertFalse(File(shared, "CLAUDE.md").exists())
        SharedStorageLayout.CLAUDE_CONFIG_DIRS.forEach { assertTrue(File(shared, it).isDirectory) }
    }

    // --- OpenCode private placeholders ---

    @Test
    fun `prepareOpencodePrivate creates the private files and adopts a newer private service json`() {
        val privateExceptions = folder.newFolder("exceptions")
        val legacyConfig = folder.newFolder("legacy-config")
        File(legacyConfig, "service.json").writeText("{\"password\":\"real\"}")

        SharedStorageDecision.prepareOpencodePrivate(privateExceptions, legacyConfig)

        assertEquals("{\"password\":\"real\"}", File(privateExceptions, "service.json").readText())
        assertTrue(File(privateExceptions, "service.json.tmp").isFile)
        assertEquals(0L, File(privateExceptions, "service.json.tmp").length())
        assertTrue(File(privateExceptions, "node_modules").isDirectory)

        // An older exception copy is not replaced by an older private copy.
        File(privateExceptions, "service.json").writeText("{\"password\":\"rotated\"}")
        File(privateExceptions, "service.json").setLastModified(2_000_000_000_000L)
        SharedStorageDecision.prepareOpencodePrivate(privateExceptions, legacyConfig)
        assertEquals("{\"password\":\"rotated\"}", File(privateExceptions, "service.json").readText())

        // A newer private-home copy (the user last ran with shared storage off) wins.
        File(legacyConfig, "service.json").writeText("{\"password\":\"newer-off\"}")
        File(legacyConfig, "service.json").setLastModified(3_000_000_000_000L)
        SharedStorageDecision.prepareOpencodePrivate(privateExceptions, legacyConfig)
        assertEquals("{\"password\":\"newer-off\"}", File(privateExceptions, "service.json").readText())
    }

    @Test
    fun `legacy enabled without consent keeps codex and claude private and flags consent`() {
        val paths = io.cocode.app.runtime.RuntimePaths(folder.newFolder("runtime-legacy-consent"))
        val sdcard = folder.newFolder("sdcard-legacy-consent")
        val prefs = MemPrefs(on = true, consent = 0)
        val controller =
            SharedStorageControllerImpl(
                paths = paths,
                hostRoot = sdcard,
                prefs = prefs,
                permission = SharedStoragePermission { true },
            )

        val legacy = controller.prepareForStart()
        assertTrue(legacy.specs().any { it.endsWith(":/root/projects") })
        assertTrue(legacy.specs().any { it.endsWith(":/root/.config/opencode") })
        assertFalse("codex must stay private", legacy.specs().any { it.endsWith(":/root/.codex") })
        assertFalse("codex shared dir must not be bound", legacy.specs().any { it.endsWith(":/root/.codex-shared") })
        assertFalse("claude must stay private", legacy.specs().any { it.endsWith(":/root/.claude") })
        assertFalse(File(paths.root, "home-private/codex/.migrated-to-shared").exists())
        assertFalse(File(paths.root, "home-private/claude/.migrated-to-shared").exists())
        assertFalse("legacy mode must not create the public Codex folder", File(sdcard, "codex").exists())
        assertTrue(controller.state.value.needsConsent)

        controller.setEnabledWithFullDirectoryConsent(true)
        assertEquals(SharedStorageLayout.FULL_DIRECTORY_CONSENT_VERSION, prefs.consent)
        assertFalse(controller.state.value.needsConsent)

        val full = controller.prepareForStart()
        assertTrue(full.specs().any { it.endsWith(":/root/.codex-shared") })
        assertTrue(full.specs().any { it.endsWith(":/root/.claude") })
        assertTrue(File(paths.root, "home-private/codex/.migrated-to-shared").isFile)
        assertTrue(File(paths.root, "home-private/claude/.migrated-to-shared").isFile)
    }

    @Test
    fun `setEnabled alone never grants full-directory consent`() {
        val paths = io.cocode.app.runtime.RuntimePaths(folder.newFolder("runtime-no-consent"))
        val sdcard = folder.newFolder("sdcard-no-consent")
        val prefs = MemPrefs(on = false)
        val controller =
            SharedStorageControllerImpl(
                paths = paths,
                hostRoot = sdcard,
                prefs = prefs,
                permission = SharedStoragePermission { true },
            )

        controller.setEnabled(true)
        assertEquals(0, prefs.consent)
        assertTrue(controller.state.value.needsConsent)
        assertFalse(File(sdcard, "codex").exists())

        controller.setEnabledWithFullDirectoryConsent(true)
        assertEquals(SharedStorageLayout.FULL_DIRECTORY_CONSENT_VERSION, prefs.consent)
        assertFalse(controller.state.value.needsConsent)
    }

    @Test
    fun `default SharedStoragePrefs methods grant no consent`() {
        val prefs =
            object : SharedStoragePrefs {
                private var on = false

                override fun enabled() = on

                override fun setEnabled(enabled: Boolean) {
                    on = enabled
                }
            }

        assertEquals(0, prefs.fullDirectoryConsentVersion())
        prefs.setFullDirectoryConsentVersion(SharedStorageLayout.FULL_DIRECTORY_CONSENT_VERSION)
        assertEquals("the default setter is a no-op", 0, prefs.fullDirectoryConsentVersion())
    }

    @Test
    fun `a partial target is a conflict on retry and is never accepted as complete`() {
        val priv = folder.newFolder("priv-failed-copy")
        File(priv, "auth.json").writeText("complete")
        val shared = File(folder.root, "shared-failed-copy")
        val journal = newJournalDir()
        val steps = SharedStorageLayout.planMigration(priv, shared, allFilesPolicy)

        var threw = false
        try {
            migrate(steps, shared, journal) { _, target ->
                target.writeText("partial")
                throw java.io.IOException("simulated interruption")
            }
        } catch (_: java.io.IOException) {
            threw = true
        }

        assertTrue(threw)
        assertEquals("partial", File(shared, "auth.json").readText())
        assertTrue("the pending journal is retained", journal.listFiles().orEmpty().any { it.name.endsWith(".journal") })
        assertTrue("the app-private snapshot is retained", journal.listFiles().orEmpty().any { it.name.endsWith(".snapshot") })

        var conflict = false
        try {
            SharedStorageLayout.recoverPending(journal, shared)
        } catch (_: MigrationConflictException) {
            conflict = true
        }
        assertTrue("a partial target must be an explicit conflict", conflict)
        assertEquals("partial", File(shared, "auth.json").readText())
        assertTrue("the conflict keeps the journal", journal.listFiles().orEmpty().any { it.name.endsWith(".journal") })
        assertTrue("the conflict keeps the snapshot", journal.listFiles().orEmpty().any { it.name.endsWith(".snapshot") })
    }

    @Test
    fun `a completed target is finalized from a pending journal`() {
        val priv = folder.newFolder("priv-completed-pending")
        File(priv, "auth.json").writeText("complete")
        val shared = File(folder.root, "shared-completed-pending")
        val journal = newJournalDir()
        val steps = SharedStorageLayout.planMigration(priv, shared, allFilesPolicy)

        try {
            migrate(steps, shared, journal) { snapshot, target ->
                Files.copy(snapshot.toPath(), target.toPath())
                throw java.io.IOException("simulated crash after complete copy")
            }
        } catch (_: java.io.IOException) {
            // The pending journal and snapshot remain.
        }

        println("JOURNAL FILES: " + journal.listFiles().orEmpty().joinToString { "${it.name}:${it.length()}" })
        journal.listFiles().orEmpty().filter { it.name.endsWith(".journal") }.forEach { println("JOURNAL: " + it.readText()) }
        journal.listFiles().orEmpty().filter { it.name.endsWith(".snapshot") }.forEach { println("SNAP HASH: " + io.cocode.app.runtime.install.RuntimeArchive.sha256(it)) }
        assertEquals("complete", File(shared, "auth.json").readText())
        val recovered = SharedStorageLayout.recoverPending(journal, shared)
        assertEquals(listOf(File(shared, "auth.json")), recovered)
        assertEquals("complete", File(shared, "auth.json").readText())
        assertFalse("the committed journal is cleaned", journal.listFiles().orEmpty().any { it.name.endsWith(".journal") })
        assertFalse("the committed snapshot is cleaned", journal.listFiles().orEmpty().any { it.name.endsWith(".snapshot") })
    }

    @Test
    fun `a complete target finalizes even if snapshot cleanup was interrupted`() {
        val priv = folder.newFolder("priv-completed-no-snapshot")
        File(priv, "auth.json").writeText("complete")
        val shared = File(folder.root, "shared-completed-no-snapshot")
        val journal = newJournalDir()
        val steps = SharedStorageLayout.planMigration(priv, shared, allFilesPolicy)

        try {
            migrate(steps, shared, journal) { snapshot, target ->
                Files.copy(snapshot.toPath(), target.toPath())
                throw java.io.IOException("simulated crash after complete copy")
            }
        } catch (_: java.io.IOException) {
            // The pending journal remains.
        }
        journal.listFiles().orEmpty()
            .filter { it.name.endsWith(".snapshot") }
            .forEach { Files.deleteIfExists(it.toPath()) }

        val recovered = SharedStorageLayout.recoverPending(journal, shared)
        assertEquals(listOf(File(shared, "auth.json")), recovered)
        assertEquals("complete", File(shared, "auth.json").readText())
        assertFalse(journal.listFiles().orEmpty().any { it.name.endsWith(".journal") })
    }

    @Test
    fun `a missing target is retried from the app-private snapshot`() {
        val priv = folder.newFolder("priv-missing-target")
        File(priv, "auth.json").writeText("complete")
        val shared = File(folder.root, "shared-missing-target")
        val journal = newJournalDir()
        val steps = SharedStorageLayout.planMigration(priv, shared, allFilesPolicy)

        try {
            migrate(steps, shared, journal) { _, _ ->
                throw java.io.IOException("simulated crash before CREATE_NEW")
            }
        } catch (_: java.io.IOException) {
            // The pending journal and snapshot remain.
        }

        assertFalse(File(shared, "auth.json").exists())
        val recovered = SharedStorageLayout.recoverPending(journal, shared)
        assertEquals(listOf(File(shared, "auth.json")), recovered)
        assertEquals("complete", File(shared, "auth.json").readText())
        assertFalse(journal.listFiles().orEmpty().any { it.name.endsWith(".journal") })
        assertFalse(journal.listFiles().orEmpty().any { it.name.endsWith(".snapshot") })
    }

    @Test
    fun `a target appearing before CREATE_NEW is preserved and the pending journal is revoked`() {
        val priv = folder.newFolder("priv-raced-target")
        File(priv, "auth.json").writeText("source")
        val shared = File(folder.root, "shared-raced-target")
        val journal = newJournalDir()
        val steps = SharedStorageLayout.planMigration(priv, shared, allFilesPolicy)

        migrate(steps, shared, journal) { _, target ->
            target.writeText("raced")
            throw java.nio.file.FileAlreadyExistsException(target.absolutePath)
        }

        assertEquals("raced", File(shared, "auth.json").readText())
        assertFalse(journal.listFiles().orEmpty().any { it.name.endsWith(".journal") })
        assertFalse(journal.listFiles().orEmpty().any { it.name.endsWith(".snapshot") })
    }

    @Test
    fun `the pending journal has path length and hash but no credential text`() {
        val priv = folder.newFolder("priv-journal-redaction")
        File(priv, "auth.json").writeText("{\"token\":\"SYNTHETIC_JOURNAL_SECRET\"}")
        val shared = File(folder.root, "shared-journal-redaction")
        val journal = newJournalDir()
        val steps = SharedStorageLayout.planMigration(priv, shared, allFilesPolicy)

        try {
            migrate(steps, shared, journal) { _, target ->
                target.writeText("partial")
                throw java.io.IOException("simulated interruption")
            }
        } catch (_: java.io.IOException) {
            // Pending journal remains.
        }

        val text = journal.listFiles().orEmpty().first { it.name.endsWith(".journal") }.readText()
        assertFalse("credential bytes must not be in the journal", text.contains("SYNTHETIC_JOURNAL_SECRET"))
        assertTrue(text.contains("length="))
        assertTrue(text.contains("sha256="))
        assertTrue(text.contains("target="))
    }

    @Test
    fun `a journal that cannot be made durable fails before the public target is created`() {
        val priv = folder.newFolder("priv-journal-fail")
        File(priv, "auth.json").writeText("complete")
        val shared = File(folder.root, "shared-journal-fail")
        val journalPath = folder.newFile("journal-is-a-file")
        val steps = SharedStorageLayout.planMigration(priv, shared, allFilesPolicy)

        var threw = false
        try {
            migrate(steps, shared, journalPath)
        } catch (_: Throwable) {
            threw = true
        }

        assertTrue(threw)
        assertFalse("no public target may be created without a durable journal", File(shared, "auth.json").exists())
    }

    @Test
    fun `legacy node_modules stays the bind source so internal absolute links keep resolving`() {
        val privateExceptions = folder.newFolder("exceptions-adopt")
        // The upgrade bug: an earlier version created an empty canonical node_modules.
        val canonical = File(privateExceptions, "node_modules").apply { mkdirs() }
        val privateConfig = folder.newFolder("private-config-adopt")
        val legacy = File(privateConfig, "node_modules").apply { mkdirs() }
        val internalTarget = File(legacy, "pkg/target.js").apply { parentFile.mkdirs(); writeText("internal") }
        val internalLink = File(legacy, "pkg/link.js")
        Files.createSymbolicLink(internalLink.toPath(), internalTarget.toPath())
        val externalTarget = folder.newFile("dep-target.txt").apply { writeText("external") }
        val externalLink = File(legacy, "pkg/external.js")
        Files.createSymbolicLink(externalLink.toPath(), externalTarget.toPath())

        SharedStorageDecision.prepareOpencodePrivate(privateExceptions, privateConfig)

        val chosen = SharedStorageLayout.opencodeNodeModulesSource(privateExceptions, privateConfig)
        assertEquals("the legacy tree is retained as the bind source", legacy, chosen)
        assertTrue("the legacy tree is not moved", legacy.isDirectory)
        assertTrue("the canonical empty tree is left alone", canonical.isDirectory)
        assertEquals("internal", File(chosen, "pkg/link.js").readText())
        assertEquals(internalTarget.toPath(), Files.readSymbolicLink(internalLink.toPath()))
        assertEquals(externalTarget.toPath(), Files.readSymbolicLink(externalLink.toPath()))

        SharedStorageDecision.prepareOpencodePrivate(privateExceptions, privateConfig)
        assertEquals(legacy, SharedStorageLayout.opencodeNodeModulesSource(privateExceptions, privateConfig))
        assertEquals("internal", File(chosen, "pkg/link.js").readText())
    }

    @Test
    fun `off upgrade binds the legacy node_modules source without rewriting links`() {
        val paths = io.cocode.app.runtime.RuntimePaths(folder.newFolder("runtime-off-adopt"))
        val sdcard = folder.newFolder("sdcard-off-adopt")
        paths.home.mkdirs()
        val canonical = File(paths.root, "home-private/opencode/node_modules").apply { mkdirs() }
        val legacy = File(paths.home, ".config/opencode/node_modules").apply { mkdirs() }
        val internalTarget = File(legacy, "pkg/target.js").apply { parentFile.mkdirs(); writeText("internal") }
        val internalLink = File(legacy, "pkg/link.js")
        Files.createSymbolicLink(internalLink.toPath(), internalTarget.toPath())
        val controller =
            SharedStorageControllerImpl(
                paths = paths,
                hostRoot = sdcard,
                prefs = MemPrefs(on = false),
                permission = SharedStoragePermission { true },
            )

        val binds = controller.prepareForStart()

        assertEquals(legacy, SharedStorageLayout.opencodeNodeModulesSource(
            File(paths.root, "home-private/opencode"),
            File(paths.home, ".config/opencode"),
        ))
        assertTrue("the legacy tree stays in place", legacy.isDirectory)
        assertTrue("the canonical empty tree is left alone", canonical.isDirectory)
        assertEquals("internal", File(legacy, "pkg/link.js").readText())
        assertEquals(internalTarget.toPath(), Files.readSymbolicLink(internalLink.toPath()))
        assertTrue(
            "off binds the legacy node_modules source",
            binds.privateExceptions.any {
                it.first == legacy && it.second == "/root/.config/opencode/node_modules"
            },
        )
    }

    @Test
    fun `a valid canonical node_modules is never overwritten by the legacy tree`() {
        val privateExceptions = folder.newFolder("exceptions-valid")
        val privateConfig = folder.newFolder("private-config-valid")
        File(privateExceptions, "node_modules/keep.txt").apply { parentFile.mkdirs(); writeText("keep") }
        File(privateConfig, "node_modules/old.txt").apply { parentFile.mkdirs(); writeText("old") }

        SharedStorageDecision.prepareOpencodePrivate(privateExceptions, privateConfig)

        assertEquals(
            File(privateExceptions, "node_modules"),
            SharedStorageLayout.opencodeNodeModulesSource(privateExceptions, privateConfig),
        )
        assertEquals("keep", File(privateExceptions, "node_modules/keep.txt").readText())
        assertFalse(File(privateExceptions, "node_modules/old.txt").exists())
        assertTrue("the legacy tree is left untouched", File(privateConfig, "node_modules/old.txt").isFile)
    }

    @Test
    fun `active codex layout links config entries and keeps auth private`() {
        val home = folder.newFolder("codex-link-home")
        val codex = File(home, ".codex").apply { mkdirs() }
        File(codex, "config.toml").writeText("private config")
        File(codex, "skills/s/SKILL.md").apply { parentFile.mkdirs(); writeText("skill") }
        File(codex, "auth.json").writeText("{\"token\":\"SYNTHETIC_PRIVATE_AUTH\"}")
        val hostRoot = folder.newFolder("codex-link-host")
        val shared = File(hostRoot, "codex").apply { mkdirs() }
        // ensureCodexMinimal creates these as empty containers before the link layout runs.
        File(shared, "skills").mkdirs()
        val journal = newJournalDir()

        SharedStorageLayout.prepareCodexLinkLayout(home, hostRoot, active = true, journalDir = journal)

        val configLink = File(codex, "config.toml")
        assertTrue(Files.isSymbolicLink(configLink.toPath()))
        assertEquals("/root/.codex-shared/config.toml", Files.readSymbolicLink(configLink.toPath()).toString())
        assertEquals("private config", File(shared, "config.toml").readText())
        assertEquals(
            "private config",
            File(File(codex, SharedStorageLayout.CODEX_STASH_DIR), "config.toml").readText(),
        )
        assertTrue(Files.isSymbolicLink(File(codex, "skills").toPath()))
        assertEquals("skill", File(shared, "skills/s/SKILL.md").readText())
        assertEquals("{\"token\":\"SYNTHETIC_PRIVATE_AUTH\"}", File(codex, "auth.json").readText())
        assertFalse("auth must never be a link", Files.isSymbolicLink(File(codex, "auth.json").toPath()))
    }

    @Test
    fun `inactive codex layout restores stashed entries and leaves no dangling link`() {
        val home = folder.newFolder("codex-restore-home")
        val codex = File(home, ".codex").apply { mkdirs() }
        File(codex, "config.toml").writeText("private config")
        File(codex, "skills/s/SKILL.md").apply { parentFile.mkdirs(); writeText("skill") }
        File(codex, "auth.json").writeText("{\"token\":\"SYNTHETIC_PRIVATE_AUTH\"}")
        val hostRoot = folder.newFolder("codex-restore-host")
        val journal = newJournalDir()

        SharedStorageLayout.prepareCodexLinkLayout(home, hostRoot, active = true, journalDir = journal)
        SharedStorageLayout.prepareCodexLinkLayout(home, hostRoot, active = false, journalDir = journal)

        val config = File(codex, "config.toml")
        assertFalse(Files.isSymbolicLink(config.toPath()))
        assertEquals("private config", config.readText())
        assertFalse(Files.isSymbolicLink(File(codex, "skills").toPath()))
        assertEquals("skill", File(codex, "skills/s/SKILL.md").readText())
        assertEquals("{\"token\":\"SYNTHETIC_PRIVATE_AUTH\"}", File(codex, "auth.json").readText())
        assertFalse(File(File(codex, SharedStorageLayout.CODEX_STASH_DIR), "config.toml").exists())
    }

    @Test
    fun `an existing codex stash is never overwritten and current entry is kept`() {
        val home = folder.newFolder("codex-stash-home")
        val codex = File(home, ".codex").apply { mkdirs() }
        File(codex, "config.toml").writeText("current config")
        File(codex, SharedStorageLayout.CODEX_STASH_DIR).apply { mkdirs() }
        File(File(codex, SharedStorageLayout.CODEX_STASH_DIR), "config.toml").writeText("stashed config")
        val hostRoot = folder.newFolder("codex-stash-host")

        SharedStorageLayout.prepareCodexLinkLayout(home, hostRoot, active = true, journalDir = newJournalDir())

        assertFalse(Files.isSymbolicLink(File(codex, "config.toml").toPath()))
        assertEquals("current config", File(codex, "config.toml").readText())
        assertEquals(
            "stashed config",
            File(File(codex, SharedStorageLayout.CODEX_STASH_DIR), "config.toml").readText(),
        )
    }

    @Test
    fun `old full layout marker upgrades state back to private before creating config links`() {
        val paths = io.cocode.app.runtime.RuntimePaths(folder.newFolder("runtime-codex-upgrade"))
        val sdcard = folder.newFolder("sdcard-codex-upgrade")
        paths.home.mkdirs()
        File(paths.home, ".codex/config.toml").apply { parentFile.mkdirs(); writeText("private config") }
        File(sdcard, "codex/auth.json").apply { parentFile.mkdirs(); writeText("{\"token\":\"SYNTHETIC_OLD_PUBLIC\"}") }
        File(sdcard, "codex/sessions/2026/01.jsonl").apply { parentFile.mkdirs(); writeText("OLD_SESSION") }
        File(sdcard, "codex/history.jsonl").writeText("OLD_HISTORY")
        File(paths.root, "home-private/codex/.migrated-to-shared").apply {
            parentFile.mkdirs()
            writeText("2\n")
        }
        val controller =
            SharedStorageControllerImpl(
                paths = paths,
                hostRoot = sdcard,
                prefs = MemPrefs(on = true, consent = SharedStorageLayout.FULL_DIRECTORY_CONSENT_VERSION),
                permission = SharedStoragePermission { true },
            )

        controller.prepareForStart()

        assertEquals("{\"token\":\"SYNTHETIC_OLD_PUBLIC\"}", File(paths.home, ".codex/auth.json").readText())
        assertEquals("OLD_SESSION", File(paths.home, ".codex/sessions/2026/01.jsonl").readText())
        assertEquals("OLD_HISTORY", File(paths.home, ".codex/history.jsonl").readText())
        assertEquals(
            "public copies are left in place",
            "OLD_SESSION",
            File(sdcard, "codex/sessions/2026/01.jsonl").readText(),
        )
        assertTrue(Files.isSymbolicLink(File(paths.home, ".codex/config.toml").toPath()))
        assertEquals("3", File(paths.root, "home-private/codex/.migrated-to-shared").readText().trim())
    }

    // --- README ---

    @Test
    fun `README warns about Claude credentials while Codex sign-ins stay private`() {
        val text = SharedStorageLayout.readme(File("/storage/emulated/0/CoCode"))

        assertTrue(text.contains("PUBLIC STORAGE WARNING"))
        assertTrue(text.contains("auth.json"))
        assertTrue(text.contains("Codex sign-ins and sessions are not in"))
        assertTrue(text.contains("auth.lock"))
        assertTrue(text.contains(".credentials.json"))
        assertTrue(text.contains("session history"))
        assertTrue(text.contains("NOT a secret boundary"))
        assertTrue(text.contains("~/.claude.json"))
        assertTrue(text.contains("does not copy changed public files back"))
        assertTrue(text.contains("service.json"))
        assertTrue(text.contains("node_modules"))
        assertTrue(text.contains("~/projects-private"))
        assertFalse(text.contains("/workspace"))
    }

    @Test
    fun `legacy README says codex and claude stay private until consent`() {
        val text = SharedStorageLayout.readme(File("/storage/emulated/0/CoCode"), fullDirectoryShared = false)

        assertTrue(text.contains("remain private"))
        assertTrue(text.contains("confirm full-directory sharing"))
        assertFalse(text.contains("codex/"))
        assertFalse(text.contains("claude/"))
        assertTrue(text.contains("service.json"))
    }

    // --- Decision and restartRequired ---

    @Test
    fun `status is Off unless enabled, NeedsPermission when enabled without access, Active when all hold`() {
        assertEquals(SharedStorageStatus.Off, SharedStorageDecision.status(false, true, true))
        assertEquals(SharedStorageStatus.NeedsPermission, SharedStorageDecision.status(true, false, true))
        assertEquals(SharedStorageStatus.NeedsPermission, SharedStorageDecision.status(true, true, false))
        assertEquals(SharedStorageStatus.Active, SharedStorageDecision.status(true, true, true))
    }

    @Test
    fun `restartRequired only when a running server was started with a different bind set`() {
        val off = SharedStorageStatus.Off to listOf("a")
        val active = SharedStorageStatus.Active to listOf("b")

        assertFalse("no server started: nothing to restart", SharedStorageDecision.restartRequired(null, off))
        assertFalse("same binds: no restart", SharedStorageDecision.restartRequired(off, off))
        assertTrue("toggled on since start: restart", SharedStorageDecision.restartRequired(off, active))
        assertTrue(
            "same status but different path: restart",
            SharedStorageDecision.restartRequired(active, SharedStorageStatus.Active to listOf("c")),
        )
    }

    /** An existing Active user (started on the per-item binds) must be asked to restart on the new bind list. */
    @Test
    fun `a server started on the old per-item bind list needs a restart under the new whole-dir list`() {
        val oldSpecs =
            listOf(
                "/data/runtime/home:/root",
                "/storage/emulated/0/CoCode/projects:/workspace",
                "/data/runtime/workspace:/workspace-private",
                "/storage/emulated/0/CoCode/opencode/opencode.jsonc:/root/.config/opencode/opencode.jsonc",
            )
        val current = SharedStorageStatus.Active to activeBinds().specs()

        assertTrue(SharedStorageDecision.restartRequired(SharedStorageStatus.Active to oldSpecs, current))
    }

    private class MemPrefs(
        var on: Boolean,
        var consent: Int = 0,
    ) : SharedStoragePrefs {
        override fun enabled() = on

        override fun setEnabled(enabled: Boolean) {
            on = enabled
        }

        override fun fullDirectoryConsentVersion() = consent

        override fun setFullDirectoryConsentVersion(version: Int) {
            consent = version
        }
    }

    @Test
    fun `controller reports restartRequired after toggling on while a server runs`() {
        val paths = io.cocode.app.runtime.RuntimePaths(folder.newFolder("runtime-root"))
        val sdcard = folder.newFolder("sdcard-CoCode")
        var granted = true
        val prefs = MemPrefs(on = false)
        val controller =
            SharedStorageControllerImpl(
                paths = paths,
                hostRoot = sdcard,
                prefs = prefs,
                permission = SharedStoragePermission { granted },
            )

        controller.recordStarted(controller.prepareForStart())
        assertEquals(SharedStorageStatus.Off, controller.state.value.status)
        assertFalse(controller.state.value.restartRequired)
        assertFalse(controller.state.value.needsConsent)

        controller.setEnabledWithFullDirectoryConsent(true)
        assertTrue(controller.state.value.restartRequired)

        controller.recordStarted(controller.prepareForStart())
        assertEquals(SharedStorageStatus.Active, controller.state.value.status)
        assertFalse(controller.state.value.restartRequired)
        assertTrue(File(sdcard, "README.txt").isFile)
        assertTrue(File(sdcard, "opencode/opencode.jsonc").isFile)
        assertTrue(File(sdcard, "codex/rules").isDirectory)
        assertTrue(File(sdcard, "claude/skills").isDirectory)
        assertTrue("opencode marker", File(paths.root, "home-private/opencode/.migrated-to-shared").isFile)
        assertTrue("codex marker", File(paths.root, "home-private/codex/.migrated-to-shared").isFile)
        assertTrue("claude marker", File(paths.root, "home-private/claude/.migrated-to-shared").isFile)
        assertTrue(
            "the private OpenCode placeholder exists",
            File(paths.root, "home-private/opencode/service.json.tmp").isFile,
        )

        granted = false
        controller.refreshPermission()
        assertEquals(SharedStorageStatus.NeedsPermission, controller.state.value.status)
        assertTrue(controller.state.value.restartRequired)

        controller.recordStopped()
        assertFalse(controller.state.value.restartRequired)
    }

    /**
     * Honest toggle behavior: enabling migrates the current private tree into shared once; while
     * Active the agents read/write shared; disabling does NOT copy public changes back, so the
     * private tree stays as it was and the public tree keeps the newer data. The OpenCode secret is
     * the exception: its private exception file is the same in both modes.
     */
    @Test
    fun `on off roundtrip keeps codex login private and restores config links`() {
        val paths = io.cocode.app.runtime.RuntimePaths(folder.newFolder("runtime-preserve"))
        val sdcard = folder.newFolder("sdcard-preserve")
        val prefs = MemPrefs(on = false)
        val controller =
            SharedStorageControllerImpl(
                paths = paths,
                hostRoot = sdcard,
                prefs = prefs,
                permission = SharedStoragePermission { true },
            )
        paths.home.mkdirs()
        val privateCodex = File(paths.home, ".codex").apply { mkdirs() }
        val privateAuth = File(privateCodex, "auth.json").apply {
            writeText("{\"token\":\"SYNTHETIC_PRIVATE_CODEX\"}")
        }
        val privateConfig = File(privateCodex, "config.toml").apply { writeText("PRIVATE_CONFIG") }
        val privateCredentials = File(paths.home, ".claude/.credentials.json").apply {
            parentFile.mkdirs()
            writeText("{\"token\":\"SYNTHETIC_PRIVATE_CLAUDE\"}")
        }
        val opencodeExceptions = File(paths.root, "home-private/opencode").apply { mkdirs() }
        val service = File(opencodeExceptions, "service.json").apply { writeText("{\"password\":\"SYNTHETIC_PRIVATE\"}") }

        controller.recordStarted(controller.prepareForStart())
        assertEquals(SharedStorageStatus.Off, controller.state.value.status)

        controller.setEnabledWithFullDirectoryConsent(true)
        val active = controller.prepareForStart()
        assertEquals(SharedStorageStatus.Active, controller.state.value.status)
        val sharedAuth = File(sdcard, "codex/auth.json")
        val sharedConfig = File(sdcard, "codex/config.toml")
        val sharedCredentials = File(sdcard, "claude/.credentials.json")
        assertFalse("Codex auth never goes public", sharedAuth.exists())
        assertEquals("PRIVATE_CONFIG", sharedConfig.readText())
        assertEquals("{\"token\":\"SYNTHETIC_PRIVATE_CLAUDE\"}", sharedCredentials.readText())
        assertEquals("{\"password\":\"SYNTHETIC_PRIVATE\"}", service.readText())
        assertTrue("codex config is a link", Files.isSymbolicLink(privateConfig.toPath()))
        assertTrue("codex uses a separate shared dir", active.specs().any { it.endsWith(":/root/.codex-shared") })
        assertFalse("never bind over private CODEX_HOME", active.specs().any { it.endsWith(":/root/.codex") })
        assertTrue("claude is a whole-dir bind", active.specs().any { it.endsWith(":/root/.claude") })
        assertTrue(
            "opencode service is bound from the private exception folder",
            active.privateExceptions.any { it.first == service && it.second == "/root/.config/opencode/service.json" },
        )

        // Codex writes auth to its private CODEX_HOME; config goes through the shared link.
        privateAuth.writeText("{\"token\":\"SYNTHETIC_ACTIVE_CODEX\"}")
        sharedConfig.writeText("ACTIVE_CONFIG")
        sharedCredentials.writeText("{\"token\":\"SYNTHETIC_ACTIVE_CLAUDE\"}")
        service.writeText("{\"password\":\"SYNTHETIC_ACTIVE\"}")
        controller.recordStarted(active)

        controller.setEnabled(false)
        val off = controller.prepareForStart()
        assertEquals(SharedStorageStatus.Off, controller.state.value.status)
        assertEquals("{\"token\":\"SYNTHETIC_ACTIVE_CODEX\"}", privateAuth.readText())
        assertFalse("Codex auth was never public", sharedAuth.exists())
        assertFalse("the config link is restored", Files.isSymbolicLink(privateConfig.toPath()))
        assertEquals("PRIVATE_CONFIG", privateConfig.readText())
        assertEquals("public config keeps the Active write", "ACTIVE_CONFIG", sharedConfig.readText())
        assertEquals(
            "Claude private copy is not auto-updated",
            "{\"token\":\"SYNTHETIC_PRIVATE_CLAUDE\"}",
            privateCredentials.readText(),
        )
        assertEquals(
            "public Claude keeps the Active write",
            "{\"token\":\"SYNTHETIC_ACTIVE_CLAUDE\"}",
            sharedCredentials.readText(),
        )
        assertEquals("{\"password\":\"SYNTHETIC_ACTIVE\"}", service.readText())
        assertTrue(
            "off mode still binds the same OpenCode service file",
            off.privateExceptions.any { it.first == service && it.second == "/root/.config/opencode/service.json" },
        )
        assertEquals(5, off.specs().size)
    }

    @Test
    fun `migration runs once per agent and a deleted shared file is not restored`() {
        val paths = io.cocode.app.runtime.RuntimePaths(folder.newFolder("runtime-once"))
        val sdcard = folder.newFolder("sdcard-once")
        paths.home.mkdirs()
        File(paths.home, ".codex").apply { mkdirs(); resolve("config.toml").writeText("private") }
        File(paths.home, ".codex/notes.md").writeText("private notes")
        File(paths.home, ".codex/skills/x.md").apply { parentFile.mkdirs(); writeText("skill") }
        File(paths.home, ".claude/rules").apply { mkdirs(); resolve("r.md").writeText("rules") }
        val prefs = MemPrefs(on = true, consent = SharedStorageLayout.FULL_DIRECTORY_CONSENT_VERSION)
        val controller =
            SharedStorageControllerImpl(
                paths = paths,
                hostRoot = sdcard,
                prefs = prefs,
                permission = SharedStoragePermission { true },
            )

        controller.prepareForStart()
        assertEquals("private", File(sdcard, "codex/config.toml").readText())
        assertEquals("skill", File(sdcard, "codex/skills/x.md").readText())
        assertFalse("private-only notes are not shared", File(sdcard, "codex/notes.md").exists())
        assertEquals("rules", File(sdcard, "claude/rules/r.md").readText())

        File(sdcard, "codex/skills/x.md").delete()
        File(sdcard, "claude/rules/r.md").delete()
        controller.prepareForStart()

        assertFalse("deleted shared file must not come back", File(sdcard, "codex/skills/x.md").exists())
        assertFalse("deleted shared file must not come back", File(sdcard, "claude/rules/r.md").exists())
    }
}
