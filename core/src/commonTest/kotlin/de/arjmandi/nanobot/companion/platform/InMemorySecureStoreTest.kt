package de.arjmandi.nanobot.companion.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The secure store is the only place a gateway secret may live. The
 * in-memory fake is what every facade and engine test uses, so its contract is the
 * same three calls the Keychain and Keystore implementations owe.
 */
class InMemorySecureStoreTest {

    @Test
    fun roundTrip() {
        val store = InMemorySecureStore()

        assertNull(store.get("gateway.secret"), "a missing alias is null, never an empty string")

        store.put("gateway.secret", "s3cr3t-value")
        assertEquals("s3cr3t-value", store.get("gateway.secret"))

        store.put("gateway.secret", "rotated")
        assertEquals("rotated", store.get("gateway.secret"), "a second put replaces the first")
    }

    @Test
    fun removeForgetsTheAlias() {
        val store = InMemorySecureStore()
        store.put("gateway.secret", "s3cr3t-value")

        store.remove("gateway.secret")

        assertNull(store.get("gateway.secret"), "disconnect forgets the key (plan.md D5, I7)")
    }
}
