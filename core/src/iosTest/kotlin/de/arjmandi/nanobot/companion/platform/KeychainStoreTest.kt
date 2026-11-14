package de.arjmandi.nanobot.companion.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The iOS secret store is the Keychain (`Security.framework`, plan.md D5). The store's
 * own contract — replace instead of duplicate, null for a missing alias, text in and
 * text out — is driven here over a recording backend, because a simulator test binary
 * is not an app bundle and Security.framework answers `errSecNotAvailable` to it.
 * `KeychainQueryTest` proves the marshalling that backend would use.
 */
class KeychainStoreTest {

    private class RecordingBackend : KeychainBackend {
        val values = mutableMapOf<String, ByteArray>()
        val calls = mutableListOf<String>()

        override fun add(alias: String, value: ByteArray): Boolean {
            calls += "add:$alias"
            values[alias] = value
            return true
        }

        override fun read(alias: String): ByteArray? {
            calls += "read:$alias"
            return values[alias]
        }

        override fun delete(alias: String): Boolean {
            calls += "delete:$alias"
            return values.remove(alias) != null
        }
    }

    @Test
    fun roundTrip() {
        val backend = RecordingBackend()
        val store = KeychainSecureStore(backend)

        assertNull(store.get("gateway.secret"), "a missing alias is null")
        store.put("gateway.secret", "s3cr3t-value")
        assertEquals("s3cr3t-value", store.get("gateway.secret"))
        store.put("gateway.secret", "rotated")
        assertEquals("rotated", store.get("gateway.secret"), "a rotated key replaces the item")
        store.remove("gateway.secret")
        assertNull(store.get("gateway.secret"), "remove deletes the item")

        assertEquals(
            listOf(
                "read:gateway.secret",
                "delete:gateway.secret", "add:gateway.secret", "read:gateway.secret",
                "delete:gateway.secret", "add:gateway.secret", "read:gateway.secret",
                "delete:gateway.secret", "read:gateway.secret",
            ),
            backend.calls,
            "a write deletes first, because SecItemAdd refuses a duplicate alias",
        )
    }

    @Test
    fun refusesToReportSuccessWhenTheKeychainRejectsTheWrite() {
        val backend = object : KeychainBackend by RecordingBackend() {
            override fun add(alias: String, value: ByteArray): Boolean = false
        }

        val failure = runCatching { KeychainSecureStore(backend).put("gateway.secret", "s3cr3t-value") }

        assertEquals(true, failure.isFailure, "a rejected SecItemAdd must not look like a stored secret")
    }
}
