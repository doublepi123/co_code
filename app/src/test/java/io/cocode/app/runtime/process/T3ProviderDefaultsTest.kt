package io.cocode.app.runtime.process

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertNull(instances(out)["codex"])
    }

    @Test
    fun `creates settings when the file does not exist`() {
        val out = T3ProviderDefaults.merge(null, setOf("opencode"))!!
        assertEquals("true", instances(out)["opencode"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
    }

    @Test
    fun `never overrides a configured instance, even one turned off`() {
        val current = """{"providerInstances":{"opencode":{"driver":"opencode","enabled":false}}}"""
        assertNull(T3ProviderDefaults.merge(current, setOf("opencode")))
    }

    @Test
    fun `nothing to do without off-by-default agents`() {
        assertNull(T3ProviderDefaults.merge("{}", setOf("codex", "claude")))
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
            File("runtime/home/.t3/userdata/settings.json"),
            T3ProviderDefaults.settingsFile(home),
        )
    }
}
