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
import java.util.concurrent.TimeUnit

/**
 * On-device proof for the full-directory Codex/Claude binds. It runs the real PRoot launcher against
 * the installed guest rootfs and checks that atomic saves land in the shared folder:
 *
 *  - Codex `config.toml` AND `auth.json` written as temp + rename;
 *  - Claude `settings.json` AND `.credentials.json` written as temp + rename, including the
 *    randomly named credential temporary.
 *
 * This is the accepted full-mapping model: credentials and history are in shared storage. The test
 * uses only synthetic markers and a fresh `.storage-test-*` directory directly under the real
 * `/sdcard/CoCode`, removed afterwards; it never touches existing CoCode folders. It requires
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
    fun codexConfigAndAuthAtomicSavesStayLiveInShared() {
        val home = File(sandbox, "home-codex").apply { mkdirs() }
        val shared = newSharedDir("codex")
        File(home, ".codex").mkdirs()
        val config = File(shared, "config.toml").apply { writeText("model = \"OLD\"\n") }
        val auth = File(shared, "auth.json").apply { writeText("{\"token\":\"SYNTHETIC_OLD\"}") }

        val output =
            runGuest(
                home = home,
                extraBinds = listOf("${shared.absolutePath}:/root/.codex"),
                script =
                    """
                    printf 'model = "SYNTHETIC_CODEX_CONFIG"\n' > /root/.codex/config.toml.tmp
                    mv /root/.codex/config.toml.tmp /root/.codex/config.toml
                    printf '{"token":"SYNTHETIC_CODEX_AUTH"}' > /root/.codex/auth.json.tmp
                    mv /root/.codex/auth.json.tmp /root/.codex/auth.json
                    mkdir -p /root/.codex/sessions/2026
                    printf 'SYNTHETIC_SESSION' > /root/.codex/sessions/2026/01.jsonl
                    cat /root/.codex/config.toml
                    """.trimIndent(),
            )

        assertTrue("guest did not see the new config: $output", output.contains("SYNTHETIC_CODEX_CONFIG"))
        assertEquals("model = \"SYNTHETIC_CODEX_CONFIG\"\n", config.readText())
        assertEquals("{\"token\":\"SYNTHETIC_CODEX_AUTH\"}", auth.readText())
        assertEquals("SYNTHETIC_SESSION", File(shared, "sessions/2026/01.jsonl").readText())
        assertFalse("the config temporary must be consumed by rename", File(shared, "config.toml.tmp").exists())
        assertFalse("the auth temporary must be consumed by rename", File(shared, "auth.json.tmp").exists())
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
