package io.cocode.app.runtime

/** Cox keeps its historical T3 identity for saved targets and preferences. */
enum class ServerKind(
    val id: String,
    val displayName: String,
    val port: Int,
) {
    T3("t3", "Cox", 3773),
    ;

    /** Id of the on-device target for this kind, e.g. `local-t3`. */
    val localTargetId: String get() = "local-$id"

    companion object {
        /** Looks a kind up by its stable [id], or null when the id is unknown. */
        fun fromId(id: String): ServerKind? = entries.firstOrNull { it.id == id }
    }
}
