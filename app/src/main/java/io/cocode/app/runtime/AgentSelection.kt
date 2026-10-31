package io.cocode.app.runtime

import android.content.SharedPreferences
import io.cocode.app.runtime.manifest.AgentSpec

/** Default agent set on a fresh install. */
val DEFAULT_AGENTS: Set<String> = setOf("opencode")

/**
 * What the UI shows for agent selection: the agents the manifest offers, the saved selection and
 * the versions that are actually installed ([AgentsState.installed] is empty before an install).
 */
data class AgentsState(
    val available: List<AgentSpec> = emptyList(),
    val selected: Set<String> = emptySet(),
    val installed: Map<String, String> = emptyMap(),
)

/** Persisted agent selection. The set is never empty: the UI and [LocalRuntimeController.setAgents] reject that. */
interface AgentSelectionStore {
    fun selected(): Set<String>

    fun save(selected: Set<String>)
}

/** In-memory store for tests and previews. */
class InMemoryAgentSelectionStore(
    initial: Set<String> = DEFAULT_AGENTS,
) : AgentSelectionStore {
    private var value = initial

    override fun selected(): Set<String> = value

    override fun save(selected: Set<String>) {
        value = selected
    }
}

/** SharedPreferences-backed store; the file name is its own so it is easy to inspect. */
class PrefsAgentSelectionStore(
    private val prefs: SharedPreferences,
) : AgentSelectionStore {
    override fun selected(): Set<String> =
        prefs.getStringSet(KEY_AGENTS, null)?.toSet() ?: DEFAULT_AGENTS

    override fun save(selected: Set<String>) {
        prefs.edit().putStringSet(KEY_AGENTS, selected.toSet()).apply()
    }

    companion object {
        const val FILE = "cocode_agents"
        const val KEY_AGENTS = "selected"

        fun open(prefs: SharedPreferences): PrefsAgentSelectionStore = PrefsAgentSelectionStore(prefs)
    }
}
