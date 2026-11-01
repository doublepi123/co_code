package io.cocode.app.runtime.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files

/**
 * On-device proof for the recoverable, journaled migration copy on the real `/sdcard/CoCode`
 * filesystem. This is not an atomic-visibility claim: the public target is created with
 * `CREATE_NEW` and copied from an app-private snapshot; a crash can leave a partial target, which
 * recovery reports as an explicit conflict instead of accepting it.
 *
 * The test requires all-files access and a writable `/sdcard/CoCode`; it fails rather than skips so
 * the gate cannot pass without the real FUSE path. It uses synthetic markers and removes its
 * `.storage-test-*` directories.
 */
@RunWith(AndroidJUnit4::class)
class SharedStorageMigrationDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val createdDirs = mutableListOf<File>()
    private val createdJournalDirs = mutableListOf<File>()

    @Before
    fun setUp() {
        val granted = SharedStorageAccess.granted(context)
        val root = SharedStorageAccess.defaultHostRoot().apply { mkdirs() }
        assertTrue(
            "needs all-files access and a writable /sdcard/CoCode",
            granted && root.isDirectory && root.canWrite(),
        )
    }

    @After
    fun tearDown() {
        createdDirs.forEach { runCatching { it.deleteRecursively() } }
        createdJournalDirs.forEach { runCatching { it.deleteRecursively() } }
        createdDirs.clear()
        createdJournalDirs.clear()
    }

    private fun newSharedDir(name: String): File {
        val dir = File(SharedStorageAccess.defaultHostRoot(), ".storage-test-${System.nanoTime()}-$name")
        check(dir.mkdirs()) { "cannot create test directory under ${SharedStorageAccess.defaultHostRoot()}" }
        createdDirs += dir
        return dir
    }

    private fun newJournalDir(name: String): File {
        val dir = File(context.cacheDir, "migration-journal-${System.nanoTime()}-$name")
        check(dir.mkdirs()) { "cannot create app-private journal directory" }
        createdJournalDirs += dir
        return dir
    }

    private fun privateSource(name: String): File =
        File(context.cacheDir, "storage-migration-${System.nanoTime()}-$name").apply { mkdirs() }

    @Test
    fun completeCopyIsPublishedAndJournalAndSnapshotAreCleaned() {
        val source = privateSource("codex-source")
        File(source, "auth.json").writeText("SYNTHETIC_MIGRATION")
        val shared = newSharedDir("migration-complete")
        val journal = newJournalDir("complete")

        val copied =
            SharedStorageLayout.applyMigration(
                SharedStorageLayout.planMigration(source, shared, SharedStorageLayout.CLAUDE_MIGRATION_POLICY),
                shared,
                journal,
            )

        assertEquals(listOf(File(shared, "auth.json")), copied)
        assertEquals("SYNTHETIC_MIGRATION", File(shared, "auth.json").readText())
        assertFalse(journal.listFiles().orEmpty().any { it.name.endsWith(".journal") })
        assertFalse(journal.listFiles().orEmpty().any { it.name.endsWith(".snapshot") })
    }

    @Test
    fun existingSharedFinalWinsAndIsNotOverwritten() {
        val source = privateSource("codex-private")
        File(source, "auth.json").writeText("SYNTHETIC_PRIVATE")
        val shared = newSharedDir("migration-existing")
        File(shared, "auth.json").writeText("SYNTHETIC_PUBLIC")
        val journal = newJournalDir("existing")

        SharedStorageLayout.applyMigration(
            SharedStorageLayout.planMigration(source, shared, SharedStorageLayout.CLAUDE_MIGRATION_POLICY),
            shared,
            journal,
        )

        assertEquals("SYNTHETIC_PUBLIC", File(shared, "auth.json").readText())
        assertFalse(journal.listFiles().orEmpty().any { it.name.endsWith(".journal") })
        assertFalse(journal.listFiles().orEmpty().any { it.name.endsWith(".snapshot") })
    }

    @Test
    fun aTargetCreatedDuringTheCopyIsNeverOverwritten() {
        val source = privateSource("codex-raced")
        File(source, "auth.json").writeText("SYNTHETIC_SOURCE")
        val shared = newSharedDir("migration-raced")
        val journal = newJournalDir("raced")
        val steps = SharedStorageLayout.planMigration(source, shared, SharedStorageLayout.CLAUDE_MIGRATION_POLICY)

        SharedStorageLayout.applyMigration(
            steps,
            shared,
            journal,
            copyToTarget = { _, target ->
                target.writeText("SYNTHETIC_RACED")
                throw FileAlreadyExistsException(target.absolutePath)
            },
        )

        assertEquals("SYNTHETIC_RACED", File(shared, "auth.json").readText())
        assertFalse(journal.listFiles().orEmpty().any { it.name.endsWith(".journal") })
        assertFalse(journal.listFiles().orEmpty().any { it.name.endsWith(".snapshot") })
    }

    @Test
    fun aPartialTargetIsAConflictAndIsNeverAcceptedAsComplete() {
        val source = privateSource("codex-partial")
        File(source, "auth.json").writeText("SYNTHETIC_COMPLETE")
        val shared = newSharedDir("migration-partial")
        val journal = newJournalDir("partial")
        val steps = SharedStorageLayout.planMigration(source, shared, SharedStorageLayout.CLAUDE_MIGRATION_POLICY)

        try {
            SharedStorageLayout.applyMigration(
                steps,
                shared,
                journal,
                copyToTarget = { _, target ->
                    target.writeText("SYNTHETIC_PART")
                    throw IOException("simulated interruption")
                },
            )
        } catch (_: IOException) {
            // Pending journal and snapshot stay for recovery.
        }

        assertTrue(journal.listFiles().orEmpty().any { it.name.endsWith(".journal") })
        assertTrue(journal.listFiles().orEmpty().any { it.name.endsWith(".snapshot") })
        var conflict = false
        try {
            SharedStorageLayout.recoverPending(journal, shared)
        } catch (_: MigrationConflictException) {
            conflict = true
        }
        assertTrue("a partial target must be an explicit conflict", conflict)
        assertEquals("SYNTHETIC_PART", File(shared, "auth.json").readText())
        assertTrue("the conflict keeps the journal", journal.listFiles().orEmpty().any { it.name.endsWith(".journal") })
        assertTrue("the conflict keeps the snapshot", journal.listFiles().orEmpty().any { it.name.endsWith(".snapshot") })
    }

    @Test
    fun aCompletedTargetIsFinalizedFromAPendingJournal() {
        val source = privateSource("codex-completed-pending")
        File(source, "auth.json").writeText("SYNTHETIC_COMPLETE")
        val shared = newSharedDir("migration-completed-pending")
        val journal = newJournalDir("completed-pending")
        val steps = SharedStorageLayout.planMigration(source, shared, SharedStorageLayout.CLAUDE_MIGRATION_POLICY)

        try {
            SharedStorageLayout.applyMigration(
                steps,
                shared,
                journal,
                copyToTarget = { snapshot, target ->
                    Files.copy(snapshot.toPath(), target.toPath())
                    throw IOException("simulated crash after complete copy")
                },
            )
        } catch (_: IOException) {
            // Pending journal and snapshot stay for recovery.
        }

        assertEquals("SYNTHETIC_COMPLETE", File(shared, "auth.json").readText())
        val recovered = SharedStorageLayout.recoverPending(journal, shared)
        assertEquals(listOf(File(shared, "auth.json")), recovered)
        assertFalse(journal.listFiles().orEmpty().any { it.name.endsWith(".journal") })
        assertFalse(journal.listFiles().orEmpty().any { it.name.endsWith(".snapshot") })
    }

    @Test
    fun aMissingTargetIsRetriedFromTheAppPrivateSnapshot() {
        val source = privateSource("codex-missing")
        File(source, "auth.json").writeText("SYNTHETIC_RETRY")
        val shared = newSharedDir("migration-missing")
        val journal = newJournalDir("missing")
        val steps = SharedStorageLayout.planMigration(source, shared, SharedStorageLayout.CLAUDE_MIGRATION_POLICY)

        try {
            SharedStorageLayout.applyMigration(
                steps,
                shared,
                journal,
                copyToTarget = { _, _ -> throw IOException("simulated crash before CREATE_NEW") },
            )
        } catch (_: IOException) {
            // Pending journal and snapshot stay for recovery.
        }

        assertFalse(File(shared, "auth.json").exists())
        val recovered = SharedStorageLayout.recoverPending(journal, shared)
        assertEquals(listOf(File(shared, "auth.json")), recovered)
        assertEquals("SYNTHETIC_RETRY", File(shared, "auth.json").readText())
        assertFalse(journal.listFiles().orEmpty().any { it.name.endsWith(".journal") })
        assertFalse(journal.listFiles().orEmpty().any { it.name.endsWith(".snapshot") })
    }

    @Test
    fun aJournalThatCannotBeMadeDurableFailsBeforeThePublicTargetIsCreated() {
        val source = privateSource("codex-journal-fail")
        File(source, "auth.json").writeText("SYNTHETIC_NO_TARGET")
        val shared = newSharedDir("migration-journal-fail")
        val journalAsFile = File(context.cacheDir, "migration-journal-is-a-file-${System.nanoTime()}")
        check(journalAsFile.createNewFile()) { "cannot create failure journal path" }
        createdJournalDirs += journalAsFile

        var threw = false
        try {
            SharedStorageLayout.applyMigration(
                SharedStorageLayout.planMigration(source, shared, SharedStorageLayout.CLAUDE_MIGRATION_POLICY),
                shared,
                journalAsFile,
            )
        } catch (_: Throwable) {
            threw = true
        }

        assertTrue(threw)
        assertFalse(File(shared, "auth.json").exists())
    }
}
