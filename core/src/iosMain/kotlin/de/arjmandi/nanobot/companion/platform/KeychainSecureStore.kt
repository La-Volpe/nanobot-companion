package de.arjmandi.nanobot.companion.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.readBytes
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * Secrets on iOS: generic-password Keychain items, one service per namespace.
 *
 * The Keychain itself is reached through [KeychainBackend] because a Kotlin/Native
 * test binary for the simulator is not an app bundle and gets `errSecNotAvailable`
 * from Security.framework. The store's contract and the query marshalling are tested
 * here; the three `SecItem*` calls are covered by the app's own test suite in phase 2.
 */
public class KeychainSecureStore internal constructor(
    private val backend: KeychainBackend,
) : SecureStore {

    public constructor(service: String) : this(SecurityKeychainBackend(service))

    override fun put(alias: String, value: String) {
        // SecItemAdd fails with errSecDuplicateItem on an existing alias, so a rotated
        // key replaces instead of silently staying stale.
        backend.delete(alias)
        check(backend.add(alias, value.encodeToByteArray())) { "the keychain refused to store $alias" }
    }

    override fun get(alias: String): String? = backend.read(alias)?.decodeToString()

    override fun remove(alias: String) {
        backend.delete(alias)
    }
}

/** What the store needs; the Security framework is one implementation of it. */
internal interface KeychainBackend {
    fun add(alias: String, value: ByteArray): Boolean
    fun read(alias: String): ByteArray?
    fun delete(alias: String): Boolean
}

@OptIn(ExperimentalForeignApi::class)
internal class SecurityKeychainBackend(private val service: String) : KeychainBackend {

    override fun add(alias: String, value: ByteArray): Boolean =
        SecItemAdd(KeychainQuery(service, alias).addQuery(value), null) == errSecSuccess

    override fun read(alias: String): ByteArray? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(KeychainQuery(service, alias).copyQuery(), result.ptr)
        if (status != errSecSuccess) return@memScoped null
        val data: CFDataRef = result.value?.let { it.reinterpret() } ?: return@memScoped null
        data.toByteArray()
    }

    override fun delete(alias: String): Boolean =
        // Deleting a missing item is not an error for this API.
        SecItemDelete(KeychainQuery(service, alias).baseQuery()) == errSecSuccess
}

/**
 * The dictionary shapes the Keychain expects, kept separate from the calls that use
 * them so the shape can be asserted without touching the simulator's keychain.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KeychainQuery(private val service: String, private val alias: String) {

    internal fun entries(matchOne: Boolean = false): List<KeychainEntry> = buildList {
        add(KeychainEntry.Constant("class", kSecClass, kSecClassGenericPassword))
        add(KeychainEntry.Text("service", kSecAttrService, service))
        add(KeychainEntry.Text("account", kSecAttrAccount, alias))
        if (matchOne) {
            add(KeychainEntry.Constant("returnData", kSecReturnData, kCFBooleanTrue))
            add(KeychainEntry.Constant("matchLimit", kSecMatchLimit, kSecMatchLimitOne))
        }
    }

    fun baseQuery(): CFDictionaryRef = dictionary(entries())

    fun copyQuery(): CFDictionaryRef = dictionary(entries(matchOne = true))

    /**
     * A write carries an accessibility class; a read and a delete do not, so an item stored before
     * this line existed is still readable and still removable.
     *
     * The class is `AfterFirstUnlock*` rather than the default: iOS starts the app before the owner
     * has unlocked it (after a restart, from a background wake), and a when-unlocked item is
     * unreadable then: the stored gateway looks forgotten and the app asks for it again, which is
     * exactly the report this fixes. The key is still encrypted at rest either way.
     *
     * `ThisDeviceOnly` on top of that, deliberately: without it the item rides an encrypted backup
     * onto the next phone, so one gateway's key outlives the device it was typed on. The cost is
     * typing it once after a restore, which is cheaper than a secret nobody remembers copying.
     */
    fun addQuery(value: ByteArray): CFDictionaryRef =
        dictionary(
            entries() +
                KeychainEntry.Constant(
                    "accessible",
                    kSecAttrAccessible,
                    kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                ) +
                KeychainEntry.Data("valueData", kSecValueData, value),
        )
}

@OptIn(ExperimentalForeignApi::class)
internal sealed interface KeychainEntry {
    val name: String
    val key: CFStringRef?

    class Constant(override val name: String, override val key: CFStringRef?, val value: CFTypeRef?) : KeychainEntry

    class Text(override val name: String, override val key: CFStringRef?, val text: String) : KeychainEntry

    class Data(override val name: String, override val key: CFStringRef?, val bytes: ByteArray) : KeychainEntry
}

@OptIn(ExperimentalForeignApi::class)
private fun dictionary(entries: List<KeychainEntry>): CFMutableDictionaryRef {
    val dict = CFDictionaryCreateMutable(null, entries.size.convert(), null, null)!!
    for (entry in entries) {
        when (entry) {
            is KeychainEntry.Constant -> CFDictionarySetValue(dict, entry.key, entry.value)
            is KeychainEntry.Text -> CFDictionarySetValue(dict, entry.key, entry.text.cfString())
            is KeychainEntry.Data -> CFDictionarySetValue(dict, entry.key, entry.bytes.cfData())
        }
    }
    return dict
}

@OptIn(ExperimentalForeignApi::class)
internal fun CFDictionaryRef.valueFor(key: CFStringRef?): CFTypeRef? = CFDictionaryGetValue(this, key)

@OptIn(ExperimentalForeignApi::class)
private fun String.cfString(): CFStringRef = memScoped {
    CFStringCreateWithCString(null, this@cfString.cstr, kCFStringEncodingUTF8)!!
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.cfData(): CFDataRef = usePinned { pinned ->
    CFDataCreate(null, pinned.addressOf(0).reinterpret<UByteVar>(), size.convert())!!
}

@OptIn(ExperimentalForeignApi::class)
internal fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    val pointer: CPointer<ByteVar> = CFDataGetBytePtr(this)?.reinterpret() ?: return ByteArray(0)
    return pointer.readBytes(length)
}
