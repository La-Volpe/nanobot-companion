package de.arjmandi.nanobot.companion.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The store's contract, asserted on every platform: a written file is still there
 * after the app restarts, a deleted one is gone, and a name cannot climb out of the
 * app's container. Subclasses supply a real directory; nothing here is mocked.
 */
abstract class FileStoreContract {

    /** A store over a directory no other test touches. */
    protected abstract fun newStore(): FileStore

    /** A second store over the same directory: the app relaunched. */
    protected abstract fun reopenStore(): FileStore

    @Test
    fun writeThenReadSurvivesRestart() {
        assertNull(newStore().read("threads/abc.json"), "a missing file is null")

        newStore().write("threads/abc.json", """{"messages":3}""")

        assertEquals(
            """{"messages":3}""",
            reopenStore().read("threads/abc.json"),
            "a cache written before launch must be readable after it",
        )
    }

    @Test
    fun rejectsTraversalPaths() {
        val store = newStore()

        assertFailsWith<IllegalArgumentException> { store.write("../escaped.json", "x") }
        assertFailsWith<IllegalArgumentException> { store.read("../escaped.json") }
        assertFailsWith<IllegalArgumentException> { store.delete("../../Library/Preferences/x") }
        assertFailsWith<IllegalArgumentException> { store.write("/etc/passwd", "x") }
        assertFailsWith<IllegalArgumentException> { store.write("  ", "x") }
    }

    @Test
    fun deleteRemovesTheFile() {
        val store = newStore()
        store.write("draft.json", "hello")

        store.delete("draft.json")

        assertNull(store.read("draft.json"))
        // Deleting a name that is already gone is not an error: disconnect twice.
        store.delete("draft.json")
    }

    @Test
    fun overwritesInPlace() {
        val store = newStore()
        store.write("draft.json", "first")
        store.write("draft.json", "second")

        assertEquals("second", reopenStore().read("draft.json"))
    }
}
