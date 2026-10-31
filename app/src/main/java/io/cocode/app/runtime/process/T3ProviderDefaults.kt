package io.cocode.app.runtime.process

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * T3 Code enables only its Codex and Claude providers until a provider instance is configured. An
 * agent the person installed on purpose should simply be usable, so before each start the app adds
 * `providerInstances.<id> = {"driver": <id>, "enabled": true}` to T3's `settings.json` for every
 * installed agent whose driver T3 keeps off by default.
 *
 * Only missing instances are added: an instance the person configured (including one they turned
 * off) is never touched, and an unreadable settings file is left as it is.
 */
object T3ProviderDefaults {
    /** Agent id → T3 provider driver/instance id, for drivers T3 disables until configured. */
    private val OFF_BY_DEFAULT = mapOf("opencode" to "opencode")

    private val json = Json { prettyPrint = true }

    /** `<home>/.t3/userdata/settings.json` for the bind-mounted guest home. */
    fun settingsFile(home: File): File = File(home, ".t3/userdata/settings.json")

    /**
     * Returns the new settings text, or null when nothing has to change. Pure, for tests.
     * [current] is the file content or null when the file does not exist yet.
     */
    fun merge(current: String?, installedAgents: Set<String>): String? {
        val wanted = installedAgents.mapNotNull { OFF_BY_DEFAULT[it] }
        if (wanted.isEmpty()) return null
        val root: JsonObject =
            if (current.isNullOrBlank()) {
                JsonObject(emptyMap())
            } else {
                (runCatching { Json.parseToJsonElement(current) }.getOrNull() as? JsonObject) ?: return null
            }
        val instances = when (val existing = root["providerInstances"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> existing
            else -> return null
        }
        val missing = wanted.filterNot { instances.containsKey(it) }
        if (missing.isEmpty()) return null
        val merged: Map<String, JsonElement> =
            instances + missing.associateWith { id ->
                buildJsonObject {
                    put("driver", JsonPrimitive(id))
                    put("enabled", true)
                }
            }
        return json.encodeToString(JsonObject.serializer(), JsonObject(root + ("providerInstances" to JsonObject(merged))))
    }

    /** Applies [merge] to the file under [home]. Best effort; callers ignore failures. */
    fun apply(home: File, installedAgents: Set<String>) {
        val file = settingsFile(home)
        val current = if (file.isFile) file.readText() else null
        val next = merge(current, installedAgents) ?: return
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.cocode-tmp")
        temp.writeText(next)
        if (!temp.renameTo(file)) {
            temp.delete()
            error("Could not replace ${file.name}")
        }
    }
}
