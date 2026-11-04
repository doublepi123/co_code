package io.cocode.app.runtime.process

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Seeds modern Cox provider-instance envelopes for explicitly installed agents, with separate opt-in
 * entries for providers whose installers or executable configuration belong to Cox's settings UI.
 *
 * Only missing instances are added: an instance the person configured (including one they turned
 * off) is never touched, and an unreadable settings file is left as it is.
 */
object T3ProviderDefaults {
    /** Installed agent id to Cox's provider driver and default instance identity. */
    private val AGENT_DRIVERS =
        mapOf(
            "codex" to "codex",
            "claude" to "claudeAgent",
            "opencode" to "opencode",
            "grok" to "grok",
            "pi" to "pi",
            "cursor" to "cursor",
        )
    private val MANAGED_DRIVERS = setOf("antigravity", "acpRegistry", "fx", "muse")

    /** Cox's data directory name under the guest home. */
    const val COX_HOME_DIR = ".cox"

    /** The legacy T3 Code data directory name, used only while an old bundle is installed. */
    const val T3_HOME_DIR = ".t3"

    private val json = Json { prettyPrint = true }

    /** `<home>/<homeDir>/userdata/settings.json` for the bind-mounted guest home. */
    fun settingsFile(
        home: File,
        homeDir: String = COX_HOME_DIR,
    ): File = File(File(home, homeDir), "userdata/settings.json")

    /**
     * Returns the new settings text, or null when nothing has to change. Pure, for tests.
     * [current] is the file content or null when the file does not exist yet.
     */
    fun merge(current: String?, installedAgents: Set<String>, cox: Boolean = true): String? {
        val installed =
            if (cox) installedAgents.mapNotNull(AGENT_DRIVERS::get).toSet()
            else installedAgents.intersect(setOf("opencode"))
        val wanted = if (installed.isEmpty()) emptySet() else installed + if (cox) MANAGED_DRIVERS else emptySet()
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
        val configuredDrivers =
            instances.values
                .mapNotNull { (it as? JsonObject)?.get("driver") as? JsonPrimitive }
                .map { it.content }
                .toSet()
        val missing = wanted.filterNot { instances.containsKey(it) || it in configuredDrivers }
        if (missing.isEmpty()) return null
        val merged: Map<String, JsonElement> =
            instances + missing.associateWith { id ->
                buildJsonObject {
                    put("driver", JsonPrimitive(id))
                    put("enabled", id in installed)
                    put("config", buildJsonObject {})
                }
            }
        return json.encodeToString(JsonObject.serializer(), JsonObject(root + ("providerInstances" to JsonObject(merged))))
    }

    /** Applies [merge] to the file under [home]. Best effort; callers ignore failures. */
    fun apply(
        home: File,
        installedAgents: Set<String>,
        homeDir: String = COX_HOME_DIR,
    ) {
        val file = settingsFile(home, homeDir)
        val current = if (file.isFile) file.readText() else null
        val next = merge(current, installedAgents, cox = homeDir != T3_HOME_DIR) ?: return
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.cocode-tmp")
        temp.writeText(next)
        if (!temp.renameTo(file)) {
            temp.delete()
            error("Could not replace ${file.name}")
        }
    }
}
