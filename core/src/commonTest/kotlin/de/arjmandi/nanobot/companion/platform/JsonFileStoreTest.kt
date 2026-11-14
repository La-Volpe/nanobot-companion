package de.arjmandi.nanobot.companion.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.Serializable

/**
 * Callers pass models: the JSON encoding lives in the store, so a cache format changes
 * in one place. A cache written by an older version must degrade to "absent" instead of
 * crashing the chat screen.
 */
class JsonFileStoreTest {

    @Serializable
    private data class Sidebar(val pinned: List<String>, val collapsed: Boolean = false)

    private class MemoryFileStore : FileStore {
        val values = mutableMapOf<String, String>()
        override fun read(name: String): String? = values[name]
        override fun write(name: String, text: String) {
            values[name] = text
        }
        override fun delete(name: String) {
            values.remove(name)
        }
    }

    @Test
    fun roundTripsAModel() {
        val files = MemoryFileStore()
        val store = JsonFileStore(files)

        store.write("sidebar.json", Sidebar(pinned = listOf("a", "b")), Sidebar.serializer())

        assertEquals(Sidebar(listOf("a", "b")), store.read("sidebar.json", Sidebar.serializer()))
        assertNull(store.read("missing.json", Sidebar.serializer()), "no file is no model")
    }

    @Test
    fun unreadableCacheIsAbsentNotFatal() {
        val files = MemoryFileStore()
        val store = JsonFileStore(files)

        files.write("sidebar.json", """{"pinned":"not-a-list"}""")
        assertNull(store.read("sidebar.json", Sidebar.serializer()), "a stale cache degrades to absent")

        files.write("sidebar.json", "{ truncated")
        assertNull(store.read("sidebar.json", Sidebar.serializer()), "a half-written cache degrades to absent")
    }
}
