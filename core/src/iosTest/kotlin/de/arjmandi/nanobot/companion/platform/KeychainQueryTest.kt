package de.arjmandi.nanobot.companion.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFDataRef
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecMatchLimit
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * `SecItem*` are driven entirely by dictionaries, so the dictionaries are where a
 * Keychain bug lives. Building one does not touch the keychain, which means this part
 * is testable on the simulator even though the calls themselves need an app bundle.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainQueryTest {

    private val query = KeychainQuery("de.arjmandi.nanobot.companion", "gateway.secret")

    @Test
    fun addQueryNamesTheItemAndCarriesTheValue() {
        val add = query.addQuery("s3cr3t-value".encodeToByteArray())

        assertNotNull(add.valueFor(kSecClass), "every Keychain item needs a class")
        assertNotNull(add.valueFor(kSecAttrService), "the namespace is the service")
        assertNotNull(add.valueFor(kSecAttrAccount), "the alias is the account")
        assertEquals(
            "s3cr3t-value".encodeToByteArray().toList(),
            (add.valueFor(kSecValueData) as CFTypeRef?).asData().toList(),
            "the item's data is the secret itself, not a hash",
        )
    }

    /**
     * A launch is not always a phone someone is looking at: iOS starts the app before the owner
     * has unlocked it (a restart, a background wake), and an item written with the default
     * `kSecAttrAccessibleWhenUnlocked` is unreadable then, so the stored gateway looks missing and
     * the app asks for it again. `AfterFirstUnlock` is the class that survives that, and it is
     * still encrypted at rest — the one thing a gateway key needs.
     *
     * `ThisDeviceOnly` because of what the class also decides: a plain `AfterFirstUnlock` item is
     * carried by an encrypted backup onto the next phone, and a gateway key should die with the
     * device it was typed on. Retyping it after a restore is the cheaper of the two surprises.
     */
    @Test
    fun addQueryKeepsTheSecretReadableAfterTheFirstUnlock() {
        val add = query.addQuery("s3cr3t-value".encodeToByteArray())

        val accessible = add.valueFor(kSecAttrAccessible)
        assertNotNull(
            accessible,
            "an item with no accessibility class is when-unlocked, which a launch before the first " +
                "unlock cannot read",
        )
        assertTrue(
            accessible == kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            "the stored gateway must be readable by the launch that follows a restart, and must " +
                "not ride a backup onto another device",
        )
    }

    @Test
    fun copyQueryAsksForTheDataBack() {
        val copy = query.copyQuery()

        assertNotNull(copy.valueFor(kSecReturnData), "a read has to ask for kSecReturnData")
        assertNotNull(copy.valueFor(kSecMatchLimit), "a read has to bound the result to one item")
        assertNull(copy.valueFor(kSecValueData), "a read must not carry a value")
    }

    @Test
    fun baseQueryCarriesNoReadOrWriteClauses() {
        val base = query.baseQuery()

        assertNull(base.valueFor(kSecReturnData), "delete and update must not ask for data")
        assertNull(base.valueFor(kSecValueData), "delete and update must not send data")
        assertNotNull(base.valueFor(kSecClass))
    }

    @Test
    fun serviceSeparatesNamespaces() {
        fun textEntries(service: String): Map<String, String> =
            KeychainQuery(service, "gateway.secret").entries()
                .filterIsInstance<KeychainEntry.Text>()
                .associate { it.name to it.text }

        assertEquals("service.one", textEntries("service.one")["service"], "the service is the namespace")
        assertEquals("gateway.secret", textEntries("service.one")["account"], "the alias is the account")
        assertEquals("service.two", textEntries("service.two")["service"], "two namespaces never share an item")
    }

    private fun CFTypeRef?.asData(): ByteArray {
        val data: CFDataRef = this?.reinterpret() ?: error("expected CFData, got $this")
        return data.toByteArray()
    }
}
