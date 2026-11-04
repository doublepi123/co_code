package io.cocode.app.data

import android.content.SharedPreferences
import io.cocode.app.runtime.ServerKind
import io.cocode.app.runtime.ServerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The saved order of remote servers: moves, edits in place, and that it survives a reload. */
class RemoteServerOrderTest {
    private fun server(
        id: String,
        name: String = id.uppercase(),
    ) = ServerTarget.Remote(id, name, "https://$id.example.com", null, kind = ServerKind.T3)

    private fun EncryptedRemoteServerStore.ids() = servers.value.map { it.id }

    private fun storeWith(vararg ids: String): Pair<MemoryPrefs, EncryptedRemoteServerStore> {
        val prefs = MemoryPrefs()
        val store = EncryptedRemoteServerStore(prefs)
        ids.forEach { store.upsert(server(it)) }
        return prefs to store
    }

    @Test
    fun `new servers go to the end`() {
        val (_, store) = storeWith("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), store.ids())
    }

    @Test
    fun `editing a server keeps its place`() {
        val (prefs, store) = storeWith("a", "b", "c")
        store.upsert(server("a", name = "Renamed"))
        assertEquals(listOf("a", "b", "c"), store.ids())
        assertEquals("Renamed", store.servers.value.first().displayName)
        assertEquals(listOf("a", "b", "c"), EncryptedRemoteServerStore(prefs).ids())
    }

    @Test
    fun `move down, up, to the ends, and the order survives a reload`() {
        val (prefs, store) = storeWith("a", "b", "c", "d")
        store.move("a", 1)
        assertEquals(listOf("b", "a", "c", "d"), store.ids())
        store.move("d", 0)
        assertEquals(listOf("d", "b", "a", "c"), store.ids())
        store.move("d", 3)
        assertEquals(listOf("b", "a", "c", "d"), store.ids())
        store.move("c", 1)
        assertEquals(listOf("b", "c", "a", "d"), store.ids())
        assertEquals(listOf("b", "c", "a", "d"), EncryptedRemoteServerStore(prefs).ids())
    }

    @Test
    fun `out of range targets are clamped and the length never changes`() {
        val (_, store) = storeWith("a", "b", "c")
        store.move("a", 99)
        assertEquals(listOf("b", "c", "a"), store.ids())
        store.move("a", -5)
        assertEquals(listOf("a", "b", "c"), store.ids())
    }

    @Test
    fun `unknown ids and no-op moves write nothing`() {
        val (prefs, store) = storeWith("a", "b", "c")
        val before = store.servers.value
        val writes = prefs.writes
        store.move("missing", 0)
        store.move("b", 1)
        assertSame(before, store.servers.value)
        assertEquals(writes, prefs.writes)
    }

    @Test
    fun `a move is a single write`() {
        val (prefs, store) = storeWith("a", "b", "c")
        val writes = prefs.writes
        store.move("c", 0)
        assertEquals(writes + 1, prefs.writes)
    }

    @Test
    fun `move keeps every field of every server`() {
        val (_, store) = storeWith("a", "b")
        val secured = ServerTarget.Remote("s", "Secured", "http://192.0.2.10:3000", "pw", allowInsecureHttp = true, kind = ServerKind.T3)
        store.upsert(secured)
        store.move("s", 0)
        assertEquals(secured, store.servers.value.first())
    }

    /** In-memory prefs that count applied edits. */
    private class MemoryPrefs : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()
        var writes = 0
            private set

        override fun getAll(): Map<String, *> = HashMap(values)

        override fun getString(
            key: String,
            defValue: String?,
        ): String? = values[key] as? String ?: defValue

        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = defValues

        override fun getInt(
            key: String,
            defValue: Int,
        ): Int = values[key] as? Int ?: defValue

        override fun getLong(
            key: String,
            defValue: Long,
        ): Long = values[key] as? Long ?: defValue

        override fun getFloat(
            key: String,
            defValue: Float,
        ): Float = values[key] as? Float ?: defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = key in values

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val puts = LinkedHashMap<String, Any?>()
            private val removes = HashSet<String>()

            override fun putString(
                key: String,
                value: String?,
            ) = apply { puts[key] = value }

            override fun putStringSet(
                key: String,
                values: MutableSet<String>?,
            ) = apply { puts[key] = values }

            override fun putInt(
                key: String,
                value: Int,
            ) = apply { puts[key] = value }

            override fun putLong(
                key: String,
                value: Long,
            ) = apply { puts[key] = value }

            override fun putFloat(
                key: String,
                value: Float,
            ) = apply { puts[key] = value }

            override fun putBoolean(
                key: String,
                value: Boolean,
            ) = apply { puts[key] = value }

            override fun remove(key: String) = apply { removes += key }

            override fun clear() = apply { removes += values.keys }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                removes.forEach(values::remove)
                values.putAll(puts)
                writes++
            }
        }
    }
}
