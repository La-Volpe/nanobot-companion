package de.arjmandi.nanobot.companion.wire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Optional-heavy payload reads: a missing field is `null`, never an exception. */
internal fun JsonObject.text(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.integer(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.decimal(name: String): Double? = (this[name] as? JsonPrimitive)?.doubleOrNull

internal fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull ?: false

internal fun JsonObject.child(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.items(name: String): List<JsonObject> =
    (this[name] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

internal fun JsonObject.texts(name: String): List<String> =
    (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
