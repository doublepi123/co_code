package io.cocode.app.runtime

/**
 * The two web servers this app can run inside the shared Linux environment.
 *
 * Exactly one server is active at a time. Both read and write the same guest home, so the OpenCode
 * configuration, sessions and agent credentials are shared. Each kind keeps its own loopback port,
 * UI password and log file; a password or cookie from one kind is never reused for the other.
 *
 * [id] is stable storage/UI identity (also used for `ServerTarget` ids). [displayName] is the English
 * technical name; the UI localises it. `T3` keeps its historical id for saved targets and
 * preferences, but its on-device server is Cox (which speaks the T3 Code protocol). [port] is fixed
 * rather than read from the runtime metadata, so an older metadata file can never point a kind at
 * another kind's port.
 */
enum class ServerKind(
    val id: String,
    val displayName: String,
    val port: Int,
) {
    T3("t3", "Cox", 3773),
    OpenChamber("openchamber", "OpenChamber", 3000),
    ;

    /** Id of the on-device target for this kind, e.g. `local-t3` / `local-openchamber`. */
    val localTargetId: String get() = "local-$id"

    companion object {
        /** Looks a kind up by its stable [id], or null when the id is unknown. */
        fun fromId(id: String): ServerKind? = entries.firstOrNull { it.id == id }
    }
}
