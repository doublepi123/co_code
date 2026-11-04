package io.cocode.app.runtime

import android.content.SharedPreferences
import io.cocode.app.runtime.manifest.AgentSpec

/** Default agent set on a fresh install. */
val DEFAULT_AGENTS: Set<String> = setOf("opencode")

/** User-selected npm CLIs or bundled SDKs, separately from the Cox server's lifecycle. */
data class AgentsState(
    val available: List<AgentSpec> = emptyList(),
    val selected: Set<String> = emptySet(),
    val installed: Map<String, String> = emptyMap(),
    /** Compatibility field for server dependencies; Cox requires no implicit agent CLI. */
    val required: Set<String> = emptySet(),
)

/**
 * The agent-only diff between a wanted selection and the ids already installed. Server ids never
 * appear here: it is the reusable boundary future server-selection work can call without mixing
 * the two selections. Additions are installed, removals are uninstalled; nothing else changes.
 */
internal data class AgentSelectionChange(
    val additions: Set<String>,
    val removals: Set<String>,
) {
    val isEmpty: Boolean get() = additions.isEmpty() && removals.isEmpty()
}

internal fun agentSelectionChange(
    selected: Set<String>,
    installed: Set<String>,
): AgentSelectionChange =
    AgentSelectionChange(
        additions = selected - installed,
        removals = installed - selected,
    )

/**
 * Persisted agent selection. The set is never empty: an empty or all-unknown input is ignored by
 * [LocalRuntimeController.setAgents], so the stored set always holds at least one selectable agent.
 */
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
