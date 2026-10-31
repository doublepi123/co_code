package io.cocode.app.runtime.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeManifestTest {
    private val architecture =
        RuntimeArchitecture(
            alpineUrl = "https://example.com/alpine.tar.gz",
            alpineSha256 = "a".repeat(64),
        )

    private val t3 =
        T3Spec(
            repository = "doublepi123/t3code",
            version = "0.1.6-pw.5",
            assetPattern = "t3-{version}-android-linux-{arch}-musl.tar.gz",
            architectures = mapOf("arm64-v8a" to "arm64", "x86_64" to "x64"),
        )

    private val agents =
        listOf(
            AgentSpec("codex", "@openai/codex", "codex", "Codex"),
            AgentSpec("claude", "@anthropic-ai/claude-code", "claude", "Claude Code"),
            AgentSpec("opencode", "@opencode/cli", "opencode", "OpenCode"),
        )

    private fun manifest(arch: RuntimeArchitecture = architecture) =
        RuntimeManifest(
            schemaVersion = 1,
            runtimeVersion = "2026.10.31.1",
            alpineVersion = "3.24.1",
            port = 3773,
            t3 = t3,
            agents = agents,
            architectures = mapOf("arm64-v8a" to arch, "x86_64" to arch),
        )

    @Test
    fun `valid manifest resolves architecture and t3 asset`() {
        manifest().validate()
        assertEquals(architecture, manifest().architecture("arm64-v8a"))
        assertEquals("arm64", manifest().t3Arch("arm64-v8a"))
        assertEquals("x64", manifest().t3Arch("x86_64"))
        assertEquals(
            "t3-0.1.6-pw.5-android-linux-arm64-musl.tar.gz",
            t3.assetName("0.1.6-pw.5", "arm64"),
        )
    }

    @Test
    fun `manifest rejects insecure rootfs URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            architecture.copy(alpineUrl = "http://example.com/alpine.tar.gz").validate("arm64-v8a")
        }
    }

    @Test
    fun `manifest rejects malformed sha256`() {
        assertThrows(IllegalArgumentException::class.java) {
            architecture.copy(alpineSha256 = "not-a-hash").validate("arm64-v8a")
        }
    }

    @Test
    fun `manifest rejects unsupported schema`() {
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(schemaVersion = 2).validate()
        }
    }

    @Test
    fun `t3 version and repository are strict`() {
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(t3 = t3.copy(version = "0.1.6")).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(t3 = t3.copy(repository = "not a repo")).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(t3 = t3.copy(assetPattern = "t3.tar.gz")).validate()
        }
    }

    @Test
    fun `every rootfs abi needs a t3 bundle`() {
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(t3 = t3.copy(architectures = mapOf("arm64-v8a" to "arm64"))).validate()
        }
    }

    @Test
    fun `agents must be unique and non-empty`() {
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(agents = emptyList()).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(agents = agents + agents.first()).validate()
        }
    }

    @Test
    fun `reader parses the bundled json shape`() {
        val json =
            """
            {"schemaVersion":1,"runtimeVersion":"r","alpineVersion":"3.24.1","port":3773,
             "t3":{"repository":"doublepi123/t3code","version":"0.1.6-pw.5",
                   "assetPattern":"t3-{version}-android-linux-{arch}-musl.tar.gz",
                   "architectures":{"x86_64":"x64"}},
             "agents":[{"id":"opencode","npmPackage":"@opencode/cli","bin":"opencode","displayName":"OpenCode"}],
             "architectures":{"x86_64":{"alpineUrl":"https://e/x.tar.gz","alpineSha256":"${"b".repeat(64)}"}}}
            """.trimIndent()
        val parsed = RuntimeManifestReader { json }.read()
        assertEquals("0.1.6-pw.5", parsed.t3.version)
        assertEquals(3773, parsed.port)
        assertEquals("OpenCode", parsed.agent("opencode")?.displayName)
    }

    @Test
    fun `release tag parsing accepts only pw releases`() {
        assertEquals("0.1.6-pw.5", T3Spec.TAG.matchEntire("v0.1.6-pw.5")?.groupValues?.get(1))
        assertEquals(null, T3Spec.TAG.matchEntire("v0.1.6"))
        assertEquals(null, T3Spec.TAG.matchEntire("0.1.6-pw.5"))
    }
}
