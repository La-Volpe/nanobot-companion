package de.arjmandi.nanobot.companion.cli.record

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Normalises a recorded frame before it is written to a fixture (plan.md C2.5).
 *
 * Two different things have to go. Credentials and identifiers of this machine or
 * this login must never be in the repository. Values that change between two runs
 * of the same scripted turn only produce a noisy diff on the next recording.
 *
 * Aliases for uuids, hashes and stream ids are kept for the whole recording, so a
 * chat id means the same alias in the bootstrap, the socket frames and the history
 * page. ISO timestamps are ranked inside each document instead, because the order
 * carries meaning (C5.1 sorts by `updated_at`) while the absolute moment does not.
 */
internal class Redactor(
    private val homeDir: String,
    private val workspaceDir: String,
) {

    private val aliases = LinkedHashMap<String, String>()
    private val applied = LinkedHashSet<String>()

    val rules: List<String> get() = applied.toList()

    fun redact(document: JsonElement): JsonElement {
        val rankedDates = datesIn(document)
            .distinct()
            .sorted()
            .withIndex()
            .associate { (rank, value) -> value to NORMALISED_DATE.plusSeconds(rank.toLong()).format(ISO_FORMAT) }
        return rewrite(document, rankedDates)!!
    }

    /** Ranked dates have to be known before the walk; the walk itself is a pure rewrite. */
    private fun datesIn(value: JsonElement): List<String> = when (value) {
        is JsonObject -> value.values.flatMap(::datesIn)
        is JsonArray -> value.flatMap(::datesIn)
        is JsonPrimitive -> if (value.isString && ISO_VALUE.matches(value.content)) listOf(value.content) else emptyList()
    }

    private fun rewrite(value: JsonElement, dates: Map<String, String>): JsonElement? = when (value) {
        is JsonObject -> JsonObject(
            value.entries.associate { (key, child) -> key to rewriteField(key, child, dates) },
        )
        is JsonArray -> JsonArray(value.mapNotNull { rewrite(it, dates) })
        is JsonPrimitive -> rewriteString(value.content, dates)?.let { JsonPrimitive(it) } ?: value
    }

    private fun rewriteField(key: String, value: JsonElement, dates: Map<String, String>): JsonElement {
        if (value is JsonPrimitive && value.isString) {
            when (key) {
                in CREDENTIAL_FIELDS -> {
                    applied += "credential fields (${CREDENTIAL_FIELDS.joinToString()}) become REDACTED"
                    return JsonPrimitive(REDACTED)
                }
                in OPAQUE_FIELDS -> {
                    applied += "opaque ids ($OPAQUE_FIELDS) become stream-<n>"
                    return JsonPrimitive(alias(value.content, "stream"))
                }
            }
            return rewriteString(value.content, dates)?.let { JsonPrimitive(it) } ?: value
        }
        if (key in TIMING_FIELDS) {
            applied += "timing fields ($TIMING_FIELDS) become 0"
            return JsonPrimitive(0)
        }
        return rewrite(value, dates)!!
    }

    private fun rewriteString(content: String, dates: Map<String, String>): String? {
        dates[content]?.let {
            applied += "ISO-8601 timestamps are re-ranked from 2000-01-01 so order survives and the recording date does not"
            return it
        }
        if (workspaceDir.isNotEmpty() && content.startsWith(workspaceDir)) {
            applied += "the recording workspace path becomes <workspace>"
            return "<workspace>" + content.removePrefix(workspaceDir)
        }
        if (homeDir.isNotEmpty() && content.startsWith(homeDir)) {
            applied += "the recorder's home directory becomes <home>"
            return "<home>" + content.removePrefix(homeDir)
        }
        UUID.find(content)?.let { match ->
            applied += "uuid identifiers become UUID-<n>, stable for the whole recording"
            return content.replaceRange(match.range, alias(match.value, "UUID"))
        }
        HEX.find(content)?.let { match ->
            applied += "hex identifiers (<16 chars>) become HASH-<n>"
            return content.replaceRange(match.range, alias(match.value, "HASH"))
        }
        return null
    }

    private fun alias(value: String, prefix: String): String = aliases.getOrPut(value) {
        "$prefix-${aliases.values.count { it.startsWith("$prefix-") } + 1}"
    }

    private companion object {
        const val REDACTED = "REDACTED"
        val CREDENTIAL_FIELDS = listOf("token", "api_token", "secret", "password")
        val OPAQUE_FIELDS = listOf("stream_id")
        val TIMING_FIELDS = listOf(
            "created_at_ms", "updated_at_ms", "started_at", "started_at_ms", "ended_at",
            "latency_ms", "ttft_ms", "generation_ms",
        )
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val HEX = Regex("[0-9a-f]{16,}")
        val ISO_VALUE = Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?$""")
        val NORMALISED_DATE: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0, 0)
        val ISO_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
    }
}
