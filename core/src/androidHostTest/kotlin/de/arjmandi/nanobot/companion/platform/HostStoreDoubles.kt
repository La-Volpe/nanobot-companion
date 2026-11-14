package de.arjmandi.nanobot.companion.platform

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The two seams [KeystoreSecureStore] needs, in forms the host JVM can run. The real
 * ones are `SharedPreferences` and the Android Keystore, which only exist on a device;
 * what the host proves is the store's contract and its on-disk format.
 */
internal class FilePreferenceStore(private val file: File) : PreferenceStore {

    private val values = java.util.Properties()

    init {
        if (file.isFile) file.inputStream().use { values.load(it) }
    }

    override fun read(key: String): String? = values.getProperty(key)

    override fun write(key: String, value: String?) {
        if (value == null) values.remove(key) else values.setProperty(key, value)
        file.parentFile.mkdirs()
        file.outputStream().use { values.store(it, null) }
    }
}

/**
 * AES-GCM with one key per JVM, standing in for the Keystore key that survives a
 * process restart. A per-instance key would make the "reopen the store" test fail for
 * a reason the real implementation does not have.
 */
internal class JceSecretBox : SecretBox {

    private val key: SecretKey = sharedKey
    private val random = SecureRandom()

    override fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return cipher.doFinal(blob.copyOfRange(12, blob.size))
    }

    private companion object {
        val sharedKey: SecretKey by lazy { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
    }
}
