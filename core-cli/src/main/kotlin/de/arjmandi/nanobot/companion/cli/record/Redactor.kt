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
 *
 * The fourth thing that has to go is the recording machine itself (T55): the gateway's
 * address, and the titles and previews of every session in the listing, which are not
 * this project's to publish just because the recorder could read them.
 */
/**
 * What a recording says instead of the gateway it was recorded from (T55). RFC 2606 reserves
 * `.example`, so the name can never resolve to somebody's server, and a fixture's provenance can
 * still say "this came over the WebSocket protocol" without naming a host.
 */
internal const val EXAMPLE_GATEWAY_URL: String = "http://gateway.example:8787"

internal class Redactor(
    private val homeDir: String,
    private val workspaceDir: String,
    private val gatewayUrl: String,
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
            // The agent's timezone is the recording machine's locale, under two names:
            // `agent.timezone` and the flattened `agents.defaults.timezone` (T26). It is a place,
            // not a fact about the gateway, so it becomes the one zone that names nobody.
            if (isTimezone(key)) {
                applied += "timezone fields become UTC"
                return JsonPrimitive(TIMEZONE)
            }
            when (key) {
                in CREDENTIAL_FIELDS -> {
                    applied += "credential fields (${CREDENTIAL_FIELDS.joinToString()}, and any *_key) become REDACTED"
                    return JsonPrimitive(REDACTED)
                }
                in OPAQUE_FIELDS -> {
                    applied += "opaque ids ($OPAQUE_FIELDS) become stream-<n>"
                    return JsonPrimitive(alias(value.content, "stream"))
                }
            }
            // `upload_key` and `x-api-key` are credentials by shape, not by name; `key` and
            // `session_key` are the gateway's own row id, which is not a secret (D15.6).
            if (isKeyShaped(key)) {
                applied += "credential fields (${CREDENTIAL_FIELDS.joinToString()}, and any *_key) become REDACTED"
                return JsonPrimitive(REDACTED)
            }
            return rewriteString(value.content, dates)?.let { JsonPrimitive(it) } ?: value
        }
        if (key == SESSIONS && value is JsonArray) return rewriteSessionRows(value, dates)
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
        // Not just at the front: a tool result is a sentence with a path in the middle of it
        // ("Successfully wrote 7 characters to /Users/…/tool-probe.txt").
        var rewritten = content
        if (workspaceDir.isNotEmpty() && rewritten.contains(workspaceDir)) {
            applied += "the recording workspace path becomes <workspace>"
            rewritten = rewritten.replace(workspaceDir, "<workspace>")
        }
        if (homeDir.isNotEmpty() && rewritten.contains(homeDir)) {
            applied += "the recorder's home directory becomes <home>"
            rewritten = rewritten.replace(homeDir, "<home>")
        }
        // The gateway it was recorded from is the one host a recording is guaranteed to name
        // (T55): in the manifest, in a tool result, in a provider's base url. Loopback names
        // nobody, and replacing it would rewrite every provider that runs on this machine.
        if (gatewayUrl.isNotEmpty()) {
            var scrubbed = rewritten
            if (scrubbed.contains(gatewayUrl)) scrubbed = scrubbed.replace(gatewayUrl, EXAMPLE_GATEWAY_URL)
            for (host in gatewayHosts()) {
                if (scrubbed.contains(host)) scrubbed = scrubbed.replace(host, EXAMPLE_GATEWAY_HOST)
            }
            if (scrubbed != rewritten) {
                applied += "the recorded gateway's address becomes $EXAMPLE_GATEWAY_URL"
                rewritten = scrubbed
            }
        }
        if (rewritten != content) return rewritten
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

    private fun isTimezone(key: String): Boolean =
        key == "timezone" || key.endsWith(".timezone") || key == "tz"

    private fun isKeyShaped(key: String): Boolean {
        val lower = key.lowercase()
        if (lower in KEY_SHAPED_EXEMPT) return false
        return lower.endsWith("_key") || lower.endsWith("-key") ||
            lower.endsWith("_secret") || lower.endsWith("_token")
    }

    /**
     * The recorded gateway's authority, and its bare host when that host names a machine (a
     * loopback address does not, and rewriting it would clobber every provider that runs there).
     */
    private fun gatewayHosts(): List<String> {
        val authority = gatewayUrl.substringAfter("://", gatewayUrl).substringBefore('/').trim()
        if (authority.isEmpty()) return emptyList()
        val host = authority.substringBefore(':').trim()
        return if (host.isEmpty() || host in LOOPBACK_HOSTS) listOf(authority) else listOf(authority, host)
    }

    /**
     * A session row's title and preview are text this project did not write: the gateway
     * summarised somebody's conversation into them. Only the rows the recorder created hold its
     * own scripted prompts, and telling those apart would need the chat ids the redactor does not
     * have — so every row is aliased. Distinct titles stay distinct and blank stays blank, which
     * is what the drawer and `title.ifBlank { preview }` actually read.
     */
    private fun rewriteSessionRows(rows: JsonArray, dates: Map<String, String>): JsonElement {
        applied += "session titles and previews become title-<n>/preview-<n>, because a gateway's " +
            "session list is not ours to publish"
        return JsonArray(
            rows.map { row ->
                val rewritten = rewrite(row, dates) as JsonObject
                JsonObject(
                    rewritten.mapValues { (name, child) ->
                        if (name in SESSION_TEXT_FIELDS && child is JsonPrimitive && child.isString) {
                            if (child.content.isBlank()) child else JsonPrimitive(alias(child.content, name))
                        } else {
                            child
                        }
                    },
                )
            },
        )
    }

    private fun alias(value: String, prefix: String): String = aliases.getOrPut(value) {
        "$prefix-${aliases.values.count { it.startsWith("$prefix-") } + 1}"
    }

    private companion object {
        const val REDACTED = "REDACTED"
        const val TIMEZONE = "UTC"

        const val EXAMPLE_GATEWAY_HOST = "gateway.example:8787"
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "[::1]", "0.0.0.0")

        // Was `token, api_token, secret, password` (T55). A gateway names a provider key
        // `api_key`, an OpenAI-shaped client sends `authorization`, a proxy logs `x-api-key`;
        // none of those were covered.
        val CREDENTIAL_FIELDS = listOf(
            "token", "api_token", "access_token", "refresh_token", "id_token", "session_token",
            "secret", "client_secret", "password", "passphrase",
            "api_key", "apikey", "apiKey", "authorization", "x-api-key", "x_api_key",
        )
        // `key` is a session row's id and `session_key` spells the same id out (D15.6).
        val KEY_SHAPED_EXEMPT = setOf("key", "session_key")
        const val SESSIONS = "sessions"
        val SESSION_TEXT_FIELDS = setOf("title", "preview")
        val OPAQUE_FIELDS = listOf("stream_id")
        // Every absolute moment in a payload churns between two recordings of the same
        // scenario. The cron fields are here because `next_run_at_ms` moved on the moment
        // the first recording finished (C7.2).
        val TIMING_FIELDS = listOf(
            "created_at_ms", "updated_at_ms", "started_at", "started_at_ms", "ended_at",
            "latency_ms", "ttft_ms", "generation_ms",
            "next_run_at_ms", "last_run_at_ms", "run_at_ms",
        )
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val HEX = Regex("[0-9a-f]{16,}")
        val ISO_VALUE = Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?$""")
        val NORMALISED_DATE: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0, 0)
        val ISO_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
    }
}
