package de.arjmandi.nanobot.companion.platform

/**
 * The only place a gateway secret may live. Aliases are short names like `gateway.secret`;
 * the implementation decides where the bytes go (Keychain on iOS, Keystore-wrapped
 * ciphertext on Android), and never exposes them in the plain file store.
 */
public interface SecureStore {
    public fun put(alias: String, value: String)

    public fun get(alias: String): String?

    public fun remove(alias: String)
}
