package io.cocode.app.runtime

/**
 * List edits shared by [RemoteServerStore] implementations, so the saved order is the order the
 * user chose: an edited server keeps its place, a new one goes to the end.
 */
fun List<ServerTarget.Remote>.withServerUpserted(server: ServerTarget.Remote): List<ServerTarget.Remote> {
    val index = indexOfFirst { it.id == server.id }
    if (index < 0) return this + server
    return toMutableList().also { it[index] = server }
}

/**
 * The list with server [id] moved so it ends up at [toIndex] (clamped to the list). An unknown id
 * or a move to the same place returns the list unchanged; the length never changes.
 */
fun List<ServerTarget.Remote>.withServerMoved(
    id: String,
    toIndex: Int,
): List<ServerTarget.Remote> {
    val from = indexOfFirst { it.id == id }
    if (from < 0) return this
    val to = toIndex.coerceIn(0, lastIndex)
    if (to == from) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
