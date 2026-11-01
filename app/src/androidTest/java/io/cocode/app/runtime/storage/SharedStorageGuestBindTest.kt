package io.cocode.app.runtime.storage

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.cocode.app.runtime.RuntimePaths
import io.cocode.app.runtime.process.EmbeddedCommandSuite
import io.cocode.app.runtime.process.ProotCommands
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * On-device proof for the final Codex config-link + Claude full-directory layout. It runs the real
 * PRoot launcher against the installed guest rootfs:
 *
 *  - Codex keeps `~/.codex` on app-private storage, so `flock(auth.lock)` works; only config entries
 *    are symlinks into `~/.codex-shared`;
 *  - Codex config writes resolve the symlink, create the temp in the shared directory, rename
 *    there, and leave the private link a link;
 *  - Codex `skills/` writes land in shared;
 *  - Claude full-directory `settings.json` and `.credentials.json` temp+rename stay live.
 *
 * The test uses only synthetic markers and a fresh `.storage-test-*` directory directly under the
 * real `/sdcard/CoCode`, removed afterwards; it never touches existing CoCode folders. It requires
 * all-files access and the runtime/rootfs and fails rather than skips.
 */
@RunWith(AndroidJUnit4::class)
class SharedStorageGuestBindTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val paths = RuntimePaths.under(context.filesDir)

    private lateinit var sandbox: File
    private lateinit var suite: EmbeddedCommandSuite.Paths
    private val createdSharedDirs = mutableListOf<File>()
    private var available = false

    @Before
    fun setUp() {
        sandbox = File(context.cacheDir, "storage-bind-${System.nanoTime()}").apply { mkdirs() }
        available =
            runCatching {
                paths.prootTmp.mkdirs()
                val root = SharedStorageAccess.defaultHostRoot().apply { mkdirs() }
                val sharedUsable =
                    SharedStorageAccess.granted(context) && root.isDirectory && root.canWrite()
                suite = EmbeddedCommandSuite(File(context.applicationInfo.nativeLibraryDir), paths).resolve()
                sharedUsable && suite.proot.canExecute() && File(paths.rootfs, "bin/sh").isFile
            }.getOrDefault(false)
        assertTrue(
            "needs all-files access, a writable /sdcard/CoCode, the installed PRoot runtime and guest rootfs",
            available,
        )
    }

    @After
    fun tearDown() {
        if (::sandbox.isInitialized) sandbox.deleteRecursively()
        createdSharedDirs.forEach { runCatching { it.deleteRecursively() } }
        createdSharedDirs.clear()
    }

    /** A fresh directory directly under the real public `/sdcard/CoCode`, removed in [tearDown]. */
    private fun newSharedDir(name: String): File {
        val dir = File(SharedStorageAccess.defaultHostRoot(), ".storage-test-${System.nanoTime()}-$name")
        check(dir.mkdirs()) { "cannot create test directory under ${SharedStorageAccess.defaultHostRoot()}" }
        createdSharedDirs += dir
        return dir
    }

    private fun runGuest(
        home: File,
        extraBinds: List<String>,
        script: String,
    ): String {
        home.mkdirs()
        val command = ProotCommands.guestShellCommand(suite.proot, paths.rootfs, home, extraBinds, script)
        val env = ProotCommands.installEnvironment(suite.environment(), paths.prootTmp)
        val process =
            ProcessBuilder(command)
                .directory(sandbox)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(env)
                }
                .start()
        val output = StringBuilder()
        val reader =
            Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(output) { output.append(line).append('\n') }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
        val finished = process.waitFor(40, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            runCatching { process.inputStream.close() }
        }
        reader.join(5_000)
        val text = synchronized(output) { output.toString() }
        Log.i(TAG, "guest exit=${if (finished) process.exitValue() else "timeout"} output<<<$text>>>")
        assertTrue("guest command timed out: $text", finished)
        assertEquals("guest command failed; output=<<<$text>>>", 0, process.exitValue())
        return text
    }

    @Test
    fun codexPrivateHomeKeepsFlockAndConfigLinksWriteToShared() {
        val home = File(sandbox, "home-codex").apply { mkdirs() }
        val shared = newSharedDir("codex-config")
        val privateCodex = File(home, ".codex").apply { mkdirs() }
        File(home, ".codex-shared").mkdirs()
        File(privateCodex, "auth.lock").writeText("")
        val sharedConfig = File(shared, "config.toml").apply { writeText("model = \"OLD\"\n") }
        File(shared, "skills").mkdirs()
        Files.createSymbolicLink(
            File(privateCodex, "config.toml").toPath(),
            File("/root/.codex-shared/config.toml").toPath(),
        )
        Files.createSymbolicLink(
            File(privateCodex, "skills").toPath(),
            File("/root/.codex-shared/skills").toPath(),
        )

        val output =
            runGuest(
                home = home,
                extraBinds = listOf("${shared.absolutePath}:/root/.codex-shared"),
                script =
                    """
                    python3 -c 'import fcntl,os;fd=os.open("/root/.codex/auth.lock",os.O_RDWR|os.O_CREAT);fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB);fcntl.flock(fd,fcntl.LOCK_UN);os.close(fd)'
                    TARGET=$(readlink /root/.codex/config.toml)
                    printf 'model = "SYNTHETIC_SHARED_CONFIG"\n' > "${'$'}{TARGET}.tmp"
                    mv "${'$'}{TARGET}.tmp" "${'$'}{TARGET}"
                    mkdir -p /root/.codex/skills/x
                    printf 'skill' > /root/.codex/skills/x/SKILL.md
                    printf '%s' "${'$'}TARGET"
                    """.trimIndent(),
            )

        assertTrue("guest did not resolve the shared config link: $output", output.contains("/root/.codex-shared/config.toml"))
        assertEquals("model = \"SYNTHETIC_SHARED_CONFIG\"\n", sharedConfig.readText())
        assertTrue("the private config entry must remain a link", Files.isSymbolicLink(File(privateCodex, "config.toml").toPath()))
        assertEquals("skill", File(shared, "skills/x/SKILL.md").readText())
        assertTrue("Codex auth.lock must stay private", File(privateCodex, "auth.lock").isFile)
        assertFalse("Codex auth must never be shared", File(shared, "auth.json").exists())
    }

    @Test
    fun claudeSettingsAndCredentialsAtomicSavesStayLiveInShared() {
        val home = File(sandbox, "home-claude").apply { mkdirs() }
        val shared = newSharedDir("claude")
        File(home, ".claude").mkdirs()
        // Outside ~/.claude: this file must stay private and must never be part of the bind.
        File(home, ".claude.json").writeText("{\"account\":\"SYNTHETIC_PRIVATE\"}")
        val settings = File(shared, "settings.json").apply { writeText("{\"permissions\":{}}") }
        val credentials = File(shared, ".credentials.json").apply { writeText("{\"token\":\"SYNTHETIC_OLD\"}") }

        val output =
            runGuest(
                home = home,
                extraBinds = listOf("${shared.absolutePath}:/root/.claude"),
                script =
                    """
                    printf '{"permissions":{"allow":["Bash"]}}' > /root/.claude/settings.json.tmp
                    mv /root/.claude/settings.json.tmp /root/.claude/settings.json
                    printf '{"token":"SYNTHETIC_CLAUDE_AUTH"}' > '/root/.claude/.credentials.json.0123456789abcdef.tmp'
                    mv '/root/.claude/.credentials.json.0123456789abcdef.tmp' /root/.claude/.credentials.json
                    mkdir -p /root/.claude/projects
                    printf 'SYNTHETIC_TRANSCRIPT' > /root/.claude/projects/p.jsonl
                    cat /root/.claude/settings.json
                    """.trimIndent(),
            )

        assertTrue("guest did not see the new settings: $output", output.contains("SYNTHETIC") || output.contains("Bash"))
        assertEquals("{\"permissions\":{\"allow\":[\"Bash\"]}}", settings.readText())
        assertEquals("{\"token\":\"SYNTHETIC_CLAUDE_AUTH\"}", credentials.readText())
        assertEquals("SYNTHETIC_TRANSCRIPT", File(shared, "projects/p.jsonl").readText())
        assertFalse("the settings temporary must be consumed by rename", File(shared, "settings.json.tmp").exists())
        assertFalse(
            "the credential temporary must be consumed by rename",
            shared.listFiles().orEmpty().any { it.name.startsWith(".credentials.json.") },
        )
        assertFalse("~/.claude.json is outside the bind and must stay private", File(shared, ".claude.json").exists())
    }

    private companion object {
        const val TAG = "SharedStorageGuestBind"
    }
}
