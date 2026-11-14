package de.arjmandi.nanobot.companion.platform

/**
 * The fake every facade and engine test composes its deps from. It is deliberately in
 * the test source set: production code must take a real store, never this one.
 */
public class InMemorySecureStore : SecureStore {

    private val values = mutableMapOf<String, String>()

    override fun put(alias: String, value: String) {
        values[alias] = value
    }

    override fun get(alias: String): String? = values[alias]

    override fun remove(alias: String) {
        values.remove(alias)
    }
}
