package io.cocode.app.runtime.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class CoxHomeMigrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun home(): File = temporaryFolder.newFolder("home")

    @Test
    fun `a legacy t3 directory is moved to cox with its data`() {
        val home = home()
        File(home, ".t3/userdata").mkdirs()
        File(home, ".t3/userdata/statev2.sqlite").writeText("state")

        val logs = mutableListOf<String>()
        assertEquals(CoxHomeMigration.COX_DIR, CoxHomeMigration.prepare(home) { logs += it })

        assertFalse(File(home, ".t3").exists())
        assertEquals("state", File(home, ".cox/userdata/statev2.sqlite").readText())
        assertTrue(logs.single().contains(".t3"))
    }

    @Test
    fun `an existing cox directory is never merged or overwritten`() {
        val home = home()
        File(home, ".t3/userdata").mkdirs()
        File(home, ".t3/userdata/old.txt").writeText("old")
        File(home, ".cox/userdata").mkdirs()
        File(home, ".cox/userdata/new.txt").writeText("new")

        val logs = mutableListOf<String>()
        assertEquals(CoxHomeMigration.COX_DIR, CoxHomeMigration.prepare(home) { logs += it })

        assertEquals("old", File(home, ".t3/userdata/old.txt").readText())
        assertEquals("new", File(home, ".cox/userdata/new.txt").readText())
        assertFalse(File(home, ".cox/userdata/old.txt").exists())
        assertTrue(logs.single().contains("left as it is"))
    }

    @Test
    fun `nothing happens without a legacy directory`() {
        val home = home()
        File(home, ".cox/userdata").mkdirs()

        assertEquals(CoxHomeMigration.COX_DIR, CoxHomeMigration.prepare(home))

        assertFalse(File(home, ".t3").exists())
        assertTrue(File(home, ".cox/userdata").isDirectory)
    }

    @Test
    fun `a symlinked legacy directory is moved as a link, never followed`() {
        val home = home()
        val target = temporaryFolder.newFolder("elsewhere")
        File(target, "userdata/statev2.sqlite").apply {
            parentFile?.mkdirs()
            writeText("state")
        }
        Files.createSymbolicLink(File(home, ".t3").toPath(), target.toPath())

        assertEquals(CoxHomeMigration.COX_DIR, CoxHomeMigration.prepare(home))

        assertFalse("the old name is gone", Files.exists(File(home, ".t3").toPath()))
        assertTrue("the link itself moved", Files.isSymbolicLink(File(home, ".cox").toPath()))
        assertEquals("state", File(target, "userdata/statev2.sqlite").readText())
    }
}
