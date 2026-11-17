package de.arjmandi.nanobot.companion.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets on Android: AES-GCM under a key that never leaves the Android
 * Keystore, with the ciphertext and its IV in the app's preferences file. The Keystore
 * needs a device, so [PreferenceStore] and [SecretBox] are the two seams the host test
 * drives; production composes [SharedPreferenceStore] and [KeystoreSecretBox].
 */
internal class KeystoreSecureStore(
    private val prefs: PreferenceStore,
    private val box: SecretBox,
) : SecureStore {

    override fun put(alias: String, value: String) {
        prefs.write(alias, box.encrypt(value.encodeToByteArray()).toBase64())
    }

    override fun get(alias: String): String? =
        prefs.read(alias)?.let { box.decrypt(it.fromBase64()).decodeToString() }

    override fun remove(alias: String) {
        prefs.write(alias, null)
    }
}

/** `null` deletes the entry; the store never writes an empty secret. */
internal interface PreferenceStore {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

internal interface SecretBox {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}

private class SharedPreferenceStore(context: Context, namespace: String) : PreferenceStore {

    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit()
    }
}

/** IV is prepended to the ciphertext so one preference value carries everything needed. */
private class KeystoreSecretBox(private val keyAlias: String) : SecretBox {

    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, IV_BYTES)
        val body = blob.copyOfRange(IV_BYTES, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

// java.util.Base64 rather than android.util.Base64: same bytes, available at minSdk 26,
// and it works in a host test where the android.jar methods are stubs.
private fun ByteArray.toBase64(): String = java.util.Base64.getEncoder().encodeToString(this)

private fun String.fromBase64(): ByteArray = java.util.Base64.getDecoder().decode(this)

/** Production wiring for the Android app: Keystore key + private preferences file. */
public fun androidSecureStore(context: Context, namespace: String): SecureStore =
    KeystoreSecureStore(SharedPreferenceStore(context, namespace), KeystoreSecretBox(namespace))
