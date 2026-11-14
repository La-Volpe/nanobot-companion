package de.arjmandi.nanobot.companion.platform

/** A file store in a map: engine tests assert on what was persisted without touching disk. */
public class InMemoryFileStore : FileStore {

    public val values: MutableMap<String, String> = mutableMapOf()

    override fun read(name: String): String? = values[name]

    override fun write(name: String, text: String) {
        values[name] = text
    }

    override fun delete(name: String) {
        values.remove(name)
    }
}

public class FakeAppInfo(
    override val version: String = "0.0.0-test",
    override val build: String = "0",
    override val platform: String = "test",
) : AppInfo
