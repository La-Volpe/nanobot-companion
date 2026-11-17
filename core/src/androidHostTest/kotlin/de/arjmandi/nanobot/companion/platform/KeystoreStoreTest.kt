package de.arjmandi.nanobot.companion.platform

import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Android secret store is AES-GCM over the Android Keystore `EncryptedFile`
 * equivalent: ciphertext in the preferences file, key in the Keystore.
 * The Keystore itself needs a device, so this test drives the store's own logic with
 * a JCE box and a file-backed preference store, and asserts the property that matters
 * on disk: the plaintext never appears there.
 */
class KeystoreStoreTest {

    private fun tempStore(): Pair<KeystoreSecureStore, File> {
        val dir = Files.createTempDirectory("nanobot-keystore").toFile()
        val prefs = File(dir, "secure_prefs.properties")
        return KeystoreSecureStore(FilePreferenceStore(prefs), JceSecretBox()) to prefs
    }

    @Test
    fun roundTripAndUsesCiphertextOnDisk() {
        val (store, prefs) = tempStore()
        assertTrue(store.get("gateway.secret") == null, "a missing alias is null")

        store.put("gateway.secret", "s3cr3t-value")
        assertEquals("s3cr3t-value", store.get("gateway.secret"))

        // The store survives a restart: a fresh instance over the same file reads it back.
        val reopened = KeystoreSecureStore(FilePreferenceStore(prefs), JceSecretBox())
        assertEquals("s3cr3t-value", reopened.get("gateway.secret"), "the secret survives a process restart")

        assertFalse(prefs.readText().contains("s3cr3t-value"), "the plaintext must never be on disk")
        assertTrue(prefs.length() > 0, "the ciphertext is in the preference file")

        reopened.remove("gateway.secret")
        assertTrue(KeystoreSecureStore(FilePreferenceStore(prefs), JceSecretBox()).get("gateway.secret") == null)
    }

    @Test
    fun encryptsTheSameValueToDifferentBytes() {
        val (store, prefs) = tempStore()

        store.put("gateway.secret", "s3cr3t-value")
        val first = prefs.readText()
        store.put("gateway.secret", "s3cr3t-value")
        val second = prefs.readText()

        assertNotEquals(first, second, "GCM uses a fresh IV per write, so equal plaintext differs on disk")
    }
}
