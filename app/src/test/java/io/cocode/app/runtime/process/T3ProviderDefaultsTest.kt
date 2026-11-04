package io.cocode.app.runtime.process

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class T3ProviderDefaultsTest {
    private fun instances(text: String) = Json.parseToJsonElement(text).jsonObject["providerInstances"]!!.jsonObject

    @Test
    fun `adds opencode when installed and missing, keeping other settings`() {
        val out = T3ProviderDefaults.merge("""{"idleCompaction":"onReturn"}""", setOf("opencode", "codex"))!!
        val root = Json.parseToJsonElement(out).jsonObject
        assertEquals("onReturn", root["idleCompaction"]!!.jsonPrimitive.content)
        val opencode = instances(out)["opencode"]!!.jsonObject
        assertEquals("opencode", opencode["driver"]!!.jsonPrimitive.content)
        assertEquals("true", opencode["enabled"]!!.jsonPrimitive.content)
        assertEquals("true", instances(out)["codex"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun `creates settings when the file does not exist`() {
        val out = T3ProviderDefaults.merge(null, setOf("opencode"))!!
        assertEquals("true", instances(out)["opencode"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun `never overrides a configured instance, even one turned off`() {
        val current = """{"providerInstances":{"opencode":{"driver":"opencode","enabled":false}}}"""
        val result = T3ProviderDefaults.merge(current, setOf("opencode"))!!
        assertEquals("false", instances(result)["opencode"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        assertNull(T3ProviderDefaults.merge(result, setOf("opencode")))
    }

    @Test
    fun `nothing to do without known installed agents`() {
        assertNull(T3ProviderDefaults.merge("{}", setOf("unknown")))
    }

    @Test
    fun `leaves unreadable or unexpected settings alone`() {
        assertNull(T3ProviderDefaults.merge("{not json", setOf("opencode")))
        assertNull(T3ProviderDefaults.merge("[]", setOf("opencode")))
        assertNull(T3ProviderDefaults.merge("""{"providerInstances":[]}""", setOf("opencode")))
    }

    @Test
    fun `the settings path follows the active server home`() {
        val home = File("runtime/home")
        assertEquals(
            File("runtime/home/.cox/userdata/settings.json"),
            T3ProviderDefaults.settingsFile(home, T3ProviderDefaults.COX_HOME_DIR),
        )
        assertEquals(
            File("runtime/home/.t3/userdata/settings.json"),
            T3ProviderDefaults.settingsFile(home, T3ProviderDefaults.T3_HOME_DIR),
        )
        assertEquals(
            File("runtime/home/.cox/userdata/settings.json"),
            T3ProviderDefaults.settingsFile(home),
        )
    }

    @Test
    fun `installed agents use modern provider envelopes and managed entries stay opt in`() {
        val installed = setOf("codex", "claude", "opencode", "grok", "pi", "cursor")
        val result = instances(T3ProviderDefaults.merge(null, installed)!!)
        for (id in setOf("codex", "claudeAgent", "opencode", "grok", "pi", "cursor")) {
            val instance = result[id]!!.jsonObject
            assertEquals(id, instance["driver"]!!.jsonPrimitive.content)
            assertEquals("true", instance["enabled"]!!.jsonPrimitive.content)
            assertTrue(instance["config"]!!.jsonObject.isEmpty())
        }
        assertNull(result["claude"])
        for (id in setOf("antigravity", "acpRegistry", "fx", "muse")) {
            assertEquals("false", result[id]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a renamed provider instance is preserved without adding a duplicate`() {
        val current = """{"providerInstances":{"custom-pi":{"driver":"pi","enabled":false,"config":{"model":"custom"}}}}"""
        val result = instances(T3ProviderDefaults.merge(current, setOf("pi"))!!)
        assertNull(result["pi"])
        assertEquals("custom", result["custom-pi"]!!.jsonObject["config"]!!.jsonObject["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `legacy T3 defaults do not introduce unsupported Cox drivers`() {
        val result = instances(T3ProviderDefaults.merge(null, setOf("codex", "opencode", "grok"), cox = false)!!)
        assertEquals(setOf("opencode"), result.keys)
    }
}
