package io.cocode.app.ui

import android.content.SharedPreferences
import io.cocode.app.runtime.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UiPrefsTest {
    private val LOCAL_T3 = ServerKind.T3.localTargetId
    private val local = "http://127.0.0.1:3000"
    private val remote = "https://t3.example.com"

    @Test
    fun `each instance keeps its own page across switches and Home`() {
        val store = MemoryPrefs()
        val prefs = UiPrefs(store)
        prefs.setLastTargetId(LOCAL_T3)
        prefs.setPage(LOCAL_T3, "/?session=ses_local", local)
        prefs.setLastTargetId("r1")
        prefs.setPage("r1", "/?session=ses_remote", remote)
        prefs.clearLastTarget()

        assertNull(prefs.lastTargetId)
        assertEquals(RememberedPage("/?session=ses_local", local), prefs.pageFor(LOCAL_T3))
        assertEquals(RememberedPage("/?session=ses_remote", remote), prefs.pageFor("r1"))

        // A new process reads the same pages back.
        val reread = UiPrefs(store)
        assertEquals(RememberedPage("/?session=ses_local", local), reread.pageFor(LOCAL_T3))
        assertEquals(RememberedPage("/?session=ses_remote", remote), reread.pageFor("r1"))
    }

    @Test
    fun `removed servers lose their page`() {
        val store = MemoryPrefs()
        val prefs = UiPrefs(store)
        prefs.setPage(LOCAL_T3, "/", local)
        prefs.setPage("r1", "/a", remote)
        prefs.setPage("r2", "/b", remote)

        prefs.forgetPage("r1")
        prefs.retainPages(setOf(LOCAL_T3))

        assertNull(prefs.pageFor("r1"))
        assertNull(prefs.pageFor("r2"))
        assertEquals(RememberedPage("/", local), prefs.pageFor(LOCAL_T3))
        assertEquals(setOf("page_path:local-t3", "page_origin:local-t3"), store.all.keys.filter { it.startsWith("page_") }.toSet())
    }

    @Test
    fun `the single page of older versions moves to its instance`() {
        val store = MemoryPrefs()
        store.edit()
            .putString(UiPrefs.KEY_LAST_TARGET_ID, "r1")
            .putString("last_target_path", "/?session=ses_old")
            .putString("last_target_origin", remote)
            .apply()

        val prefs = UiPrefs(store)

        assertEquals("r1", prefs.lastTargetId)
        assertEquals(RememberedPage("/?session=ses_old", remote), prefs.pageFor("r1"))
        assertFalse(store.all.containsKey("last_target_path"))
        assertFalse(store.all.containsKey("last_target_origin"))
        assertEquals(RememberedPage("/?session=ses_old", remote), UiPrefs(store).pageFor("r1"))
    }

    @Test
    fun `an old page without an instance is dropped`() {
        val store = MemoryPrefs()
        store.edit().putString("last_target_path", "/x").putString("last_target_origin", remote).apply()

        val prefs = UiPrefs(store)

        assertEquals(emptySet<String>(), store.all.keys)
        assertNull(prefs.pageFor("r1"))
    }

    /** Enough of SharedPreferences for UiPrefs; edits apply at once. */
    @Test
    fun `the old local record and page move to T3 Code once`() {
        val store = MemoryPrefs()
        store.edit()
            .putString(UiPrefs.KEY_LAST_TARGET_ID, "local")
            .putString(UiPrefs.PAGE_PATH_PREFIX + "local", "/?session=old")
            .putString(UiPrefs.PAGE_ORIGIN_PREFIX + "local", "http://127.0.0.1:3773")
            .apply()

        val prefs = UiPrefs(store)

        assertEquals("local-t3", prefs.lastTargetId)
        assertEquals(RememberedPage("/?session=old", "http://127.0.0.1:3773"), prefs.pageFor("local-t3"))
        assertNull(prefs.pageFor("local"))
        assertEquals("local-t3", store.getString(UiPrefs.KEY_LAST_TARGET_ID, null))
        assertFalse(store.contains(UiPrefs.PAGE_PATH_PREFIX + "local"))
        assertEquals("/?session=old", store.getString(UiPrefs.PAGE_PATH_PREFIX + "local-t3", null))
        // Read again: nothing left to migrate, same result.
        assertEquals("local-t3", UiPrefs(store).lastTargetId)
    }

    private class MemoryPrefs : SharedPreferences {
        private val values = LinkedHashMap<String, Any?>()

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
            }
        }
    }
}
