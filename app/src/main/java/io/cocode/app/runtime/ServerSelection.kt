package io.cocode.app.runtime

import android.content.SharedPreferences
import java.io.File

/** Servers a fresh install offers. T3 keeps the historical default. */
val DEFAULT_SERVERS: Set<ServerKind> = setOf(ServerKind.T3)

/**
 * What the UI shows for server selection. [installed] holds every installed server's version and
 * [active] is the one process allowed to run. This is a separate axis from [AgentsState].
 */
data class ServersState(
    val selected: Set<ServerKind> = DEFAULT_SERVERS,
    val installed: Map<ServerKind, String> = emptyMap(),
    val active: ServerKind = ServerKind.T3,
)

/** Persisted server choice: the user's selection and the server last made active. */
data class ServerSelection(
    val selected: Set<ServerKind> = DEFAULT_SERVERS,
    val active: ServerKind = ServerKind.T3,
)

/**
 * Persisted server selection. The set is never empty: an empty input is ignored by
 * [LocalRuntimeController.setServers], and a read that yields nothing falls back to the installed
 * set or [DEFAULT_SERVERS].
 */
interface ServerSelectionStore {
    fun load(): ServerSelection

    fun save(selection: ServerSelection)

    /**
     * The stored value, or null when nothing was ever chosen. Used only to adopt a discovered
     * environment without overriding an explicit draft (including a failed apply's retry).
     */
    fun storedSelection(): ServerSelection? = null
}

/**
 * Small file-backed store under the runtime directory, so the choice survives a relaunch even
 * before the app wires a preferences-backed store. Format is two lines, written atomically.
 */
class FileServerSelectionStore(
    private val root: File,
) : ServerSelectionStore {
    override fun load(): ServerSelection = read() ?: ServerSelection()

    override fun storedSelection(): ServerSelection? = read()

    private fun read(): ServerSelection? {
        val file = File(root, FILE_NAME)
        if (!file.isFile) return null
        val lines =
            runCatching { file.readLines() }.getOrNull()
                ?: return null
        val selected =
            lines
                .firstOrNull { it.startsWith(SELECTED_PREFIX) }
                ?.removePrefix(SELECTED_PREFIX)
                ?.split(',')
                ?.mapNotNull(ServerKind::fromId)
                ?.toSet()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_SERVERS
        val active =
            lines
                .firstOrNull { it.startsWith(ACTIVE_PREFIX) }
                ?.removePrefix(ACTIVE_PREFIX)
                ?.let(ServerKind::fromId)
                ?: ServerKind.T3
        return ServerSelection(selected, active)
    }

    override fun save(selection: ServerSelection) {
        root.mkdirs()
        val content =
            SELECTED_PREFIX + selection.selected.joinToString(",") { it.id } + "\n" +
                ACTIVE_PREFIX + selection.active.id + "\n"
        io.cocode.app.runtime.fs.writeFileReplacing(File(root, FILE_NAME), content)
    }

    private companion object {
        const val FILE_NAME = "server-selection"
        const val SELECTED_PREFIX = "selected="
        const val ACTIVE_PREFIX = "active="
    }
}

class InMemoryServerSelectionStore(
    initial: ServerSelection? = null,
) : ServerSelectionStore {
    private var value = initial

    override fun load(): ServerSelection = value ?: ServerSelection()

    override fun storedSelection(): ServerSelection? = value

    override fun save(selection: ServerSelection) {
        value = selection
    }
}

/** SharedPreferences-backed store; a separate file keeps it easy to inspect. */
class PrefsServerSelectionStore(
    private val prefs: SharedPreferences,
) : ServerSelectionStore {
    override fun load(): ServerSelection =
        ServerSelection(
            selected =
                prefs.getStringSet(KEY_SELECTED, null)
                    ?.mapNotNull(ServerKind::fromId)
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_SERVERS,
            active = prefs.getString(KEY_ACTIVE, null)?.let(ServerKind::fromId) ?: ServerKind.T3,
        )

    override fun save(selection: ServerSelection) {
        prefs
            .edit()
            .putStringSet(KEY_SELECTED, selection.selected.map { it.id }.toSet())
            .putString(KEY_ACTIVE, selection.active.id)
            .apply()
    }

    override fun storedSelection(): ServerSelection? =
        if (prefs.contains(KEY_SELECTED)) load() else null

    companion object {
        const val FILE = "cocode_servers"
        const val KEY_SELECTED = "selected"
        const val KEY_ACTIVE = "active"

        fun open(prefs: SharedPreferences): PrefsServerSelectionStore = PrefsServerSelectionStore(prefs)
    }
}

/**
 * A non-empty selection: the requested set when valid, otherwise the installed set, otherwise T3.
 * Used for persisted preferences, never as a silent fallback for an explicit empty click.
 */
fun sanitizeServerSelection(
    selected: Set<ServerKind>,
    installed: Set<ServerKind> = emptySet(),
): Set<ServerKind> =
    when {
        selected.isNotEmpty() -> selected
        installed.isNotEmpty() -> installed
        else -> DEFAULT_SERVERS
    }

/** Compatibility dependency API: Cox has no mandatory CLI agent dependency. */
fun requiredAgentsForServers(
    selected: Set<ServerKind>,
    installed: Set<ServerKind>,
    knownAgents: Set<String>,
): Set<String> = emptySet()
