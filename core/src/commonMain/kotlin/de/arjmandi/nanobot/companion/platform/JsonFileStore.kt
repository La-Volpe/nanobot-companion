package de.arjmandi.nanobot.companion.platform

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json

/**
 * Callers pass models, not strings: JSON encoding belongs next to the file it lands in,
 * so a cache format can change in one place. A stored value that no longer parses is
 * treated as absent — a new app version must not crash on an old cache.
 */
internal class JsonFileStore(
    private val files: FileStore,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {

    fun <T> read(name: String, serializer: DeserializationStrategy<T>): T? =
        files.read(name)?.let { text -> runCatching { json.decodeFromString(serializer, text) }.getOrNull() }

    fun <T> write(name: String, value: T, serializer: SerializationStrategy<T>) {
        files.write(name, json.encodeToString(serializer, value))
    }
}
