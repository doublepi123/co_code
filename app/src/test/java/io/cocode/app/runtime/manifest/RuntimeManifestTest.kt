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
            npmPackage = "@doublepi123/cox",
            version = "0.1.1",
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
    fun `valid manifest resolves architecture and Cox npm package`() {
        manifest().validate()
        assertEquals(architecture, manifest().architecture("arm64-v8a"))
        assertEquals("@doublepi123/cox", manifest().t3.npmPackage)
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
    fun `Cox version and npm package are strict`() {
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(t3 = t3.copy(version = "0.1")).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(t3 = t3.copy(npmPackage = "cox; echo unsafe")).validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            manifest().copy(t3 = t3.copy(version = "0.1.14-rc.1")).validate()
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
             "t3":{"npmPackage":"@doublepi123/cox","version":"0.1.1"},
             "agents":[{"id":"opencode","npmPackage":"@opencode/cli","bin":"opencode","displayName":"OpenCode"}],
             "architectures":{"x86_64":{"alpineUrl":"https://e/x.tar.gz","alpineSha256":"${"b".repeat(64)}"}}}
            """.trimIndent()
        val parsed = RuntimeManifestReader { json }.read()
        assertEquals("0.1.1", parsed.t3.version)
        assertEquals(3773, parsed.port)
        assertEquals("OpenCode", parsed.agent("opencode")?.displayName)
    }

    @Test
    fun `bundled agent requires no npm package and unknown installers are rejected`() {
        val cursor = AgentSpec("cursor", bin = "", displayName = "Cursor SDK", installation = "bundled")
        manifest().copy(agents = listOf(cursor)).validate()
        assertThrows(IllegalArgumentException::class.java) { cursor.copy(installation = "shell").validate() }
    }

}
