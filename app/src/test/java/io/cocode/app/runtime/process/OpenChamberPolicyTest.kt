package io.cocode.app.runtime.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class OpenChamberPolicyTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `policy pins the guest opencode binary at the documented path`() {
        val rootfs = folder.newFolder("rootfs")

        OpenChamberPolicy.write(rootfs)

        val policy = File(rootfs, "etc/openchamber/policy.json")
        assertEquals("{\"opencodeBinary\":\"/usr/local/bin/opencode\"}\n", policy.readText())
        assertTrue(policy.isFile)
    }

    @Test
    fun `a symlink at the policy path is replaced, not followed`() {
        val rootfs = folder.newFolder("rootfs")
        val victim = File(folder.root, "victim.txt").apply { writeText("untouched") }
        val policy = File(rootfs, "etc/openchamber/policy.json")
        policy.parentFile.mkdirs()
        Files.createSymbolicLink(policy.toPath(), victim.toPath())

        OpenChamberPolicy.write(rootfs)

        assertEquals("untouched", victim.readText())
        assertFalse(Files.isSymbolicLink(policy.toPath()))
        assertEquals(OpenChamberPolicy.CONTENT, policy.readText())
    }

    @Test
    fun `an already correct policy is left in place`() {
        val rootfs = folder.newFolder("rootfs")
        val policy = File(rootfs, "etc/openchamber/policy.json")
        policy.parentFile.mkdirs()
        policy.writeText(OpenChamberPolicy.CONTENT)
        val before = policy.lastModified()

        OpenChamberPolicy.write(rootfs)

        assertEquals(before, policy.lastModified())
    }
}
