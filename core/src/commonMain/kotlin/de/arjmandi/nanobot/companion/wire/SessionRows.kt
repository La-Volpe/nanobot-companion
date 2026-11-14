package de.arjmandi.nanobot.companion.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The drawer's rows (plan.md C5.1). Timestamps arrive as ISO-8601 *strings* (D15.2), and
 * the raw string is kept beside the parsed instant so a row can be shown even if the
 * parser meets a format it does not know.
 */
public data class SessionRow(
    public val key: String,
    public val chatId: String,
    public val title: String,
    public val preview: String,
    public val modelPreset: String?,
    public val accessMode: String?,
    public val projectName: String?,
    public val createdAt: String?,
    public val updatedAt: String?,
    public val updatedAtMs: Long?,
    public val recoveryState: String?,
    /** The stable handle a prompt mentions this chat by; absent on a row the gateway did not mint (C6.4). */
    public val handleId: String? = null,
    public val handleName: String? = null,
)

internal object SessionListParser {

    fun parse(text: String): List<SessionRow> {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
        val rows = (root["sessions"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return emptyList()
        return rows.map(::row).sortedByDescending { it.updatedAtMs ?: Long.MIN_VALUE }
    }

    private fun row(json: JsonObject): SessionRow {
        val key = json.text("key") ?: ""
        val scope = json["workspace_scope"] as? JsonObject
        val created = json.text("created_at")
        val updated = json.text("updated_at")
        val handle = json["handle"] as? JsonObject
        return SessionRow(
            key = key,
            chatId = key.removePrefix("websocket:"),
            title = json.text("title").orEmpty(),
            preview = json.text("preview").orEmpty(),
            modelPreset = json.text("model_preset"),
            accessMode = scope?.text("access_mode"),
            projectName = scope?.text("project_name"),
            createdAt = created,
            updatedAt = updated,
            updatedAtMs = updated?.let(Iso8601::toEpochMillis),
            recoveryState = json.text("recovery_state"),
            handleId = handle?.text("id"),
            handleName = handle?.text("name"),
        )
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * ISO-8601 to epoch millis without a date library (plan.md D4). Only the shape the
 * gateway emits — `2026-11-14T03:23:21.667888`, no zone suffix — and a trailing `Z` is
 * treated as UTC, which is what the gateway means.
 */
internal object Iso8601 {

    fun toEpochMillis(text: String): Long? {
        val trimmed = text.trim().removeSuffix("Z")
        val date = trimmed.substringBefore('T')
        val time = trimmed.substringAfter('T', missingDelimiterValue = "")
        val dateParts = date.split('-')
        if (dateParts.size != 3 || time.isEmpty()) return null
        val year = dateParts[0].toIntOrNull() ?: return null
        val month = dateParts[1].toIntOrNull() ?: return null
        val day = dateParts[2].toIntOrNull() ?: return null
        val timeParts = time.split(':')
        if (timeParts.size < 3) return null
        val hour = timeParts[0].toIntOrNull() ?: return null
        val minute = timeParts[1].toIntOrNull() ?: return null
        val secondsPart = timeParts[2]
        val second = secondsPart.substringBefore('.').toIntOrNull() ?: return null
        val fraction = secondsPart.substringAfter('.', missingDelimiterValue = "")
        val millis = fraction.take(3).padEnd(3, '0').toIntOrNull() ?: 0
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59 || second !in 0..60) return null

        val days = daysFromCivil(year, month, day)
        return ((days * 86_400L) + hour * 3_600L + minute * 60L + second) * 1_000L + millis
    }

    /** Howard Hinnant's days-from-civil; the civil calendar since 1970-01-01. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val monthPrime = (month + 9) % 12
        val dayOfYear = (153 * monthPrime + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097L + dayOfEra - 719_468L
    }
}
