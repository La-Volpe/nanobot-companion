package de.arjmandi.nanobot.companion.fixture

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Fixture files are evidence, so they live as real files under
 * `commonTest/resources/fixtures` where review can read them (plan.md §3.3).
 * The JVM test task runs in the module directory, but not every Gradle task does,
 * so resolve the root by walking up instead of trusting `user.dir`.
 */
internal object Fixtures {
    private val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "core/src/commonTest/resources/fixtures")
            if (candidate.isDirectory) return@lazy candidate
            val local = File(dir, "src/commonTest/resources/fixtures")
            if (local.isDirectory) return@lazy local
            dir = dir.parentFile
        }
        error("fixtures/ not found above ${System.getProperty("user.dir")}; run :core-cli record first")
    }

    const val DIR_NAME: String = "fixtures"

    val sets: List<File>
        get() = root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }

    /** The set the codec is written against: the newest recording wins. */
    val latest: File
        get() = sets.lastOrNull() ?: error("no fixture set under $root; run :core-cli record")

    fun manifest(set: File = latest): JsonObject = readJson(File(set, "manifest.json"))

    fun readJson(file: File): JsonObject = Json.parseToJsonElement(file.readText()) as JsonObject

    fun files(set: File = latest): List<File> =
        set.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(set).path }.toList()

    /** `ws/00-ready.json`, `ws/01-session_updated.json`, … in arrival order. */
    fun wsFrames(set: File = latest): List<JsonObject> =
        File(set, "ws").listFiles().orEmpty()
            .filter { it.name.endsWith(".json") }
            .sortedBy { it.name }
            .map(::readJson)

    fun events(set: File = latest): List<String> =
        wsFrames(set).map { it.event() }

    fun JsonObject.event(): String =
        (this["event"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "<missing>"
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.arr(key: String): kotlinx.serialization.json.JsonArray? =
    this[key] as? kotlinx.serialization.json.JsonArray

internal fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
