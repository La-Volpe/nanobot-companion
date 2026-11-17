package de.arjmandi.nanobot.companion.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** `version{current, commit}`: the two lines Overview shows. */
public data class GatewayVersion(public val current: String, public val commit: String)

/** Where the gateway runs; Overview's "server" line, and the only place it can come from. */
public data class GatewayEnvironment(
    public val pythonVersion: String?,
    public val os: String?,
    public val osVersion: String?,
    public val architecture: String?,
)

/** The transcription bounds the app validates against, beside the ones `ready.limits` carries. */
public data class TranscriptionSettings(
    public val enabled: Boolean,
    public val provider: String?,
    public val model: String?,
    public val maxDurationSeconds: Int?,
    public val maxUploadMb: Int?,
)

/**
 * One day of `/api/settings/usage`, as far as Overview draws it. The payload carries a dozen
 * more fields per day (the cache split, the request counts, the per-source breakdown), and the
 * card draws one bar per day from the day's total, so those are not parsed: a field nobody reads
 * is a field that can silently rot. The card's own window is `UsageWindow`, which needs no more
 * than a date and a number.
 */
public data class UsageDay(public val date: String, public val totalTokens: Long = 0)

/**
 * `/api/settings/usage`, as far as Overview's card draws it: the days behind the strip, the
 * requests under the headline, and the cache hit rate beside them, which are the three figures
 * the design's usage card shows. Nothing else from the payload is parsed:
 * `providers_30d` (the card's per-model legend) has no reader here, and neither does the year of
 * totals, the streaks or the per-source split.
 */
public data class UsageTotals(
    public val days: List<UsageDay> = emptyList(),
    public val requests30d: Int = 0,
    /** 0..1; null when the gateway had no denominator to divide by, which is not "0%". */
    public val cacheHitRate: Double? = null,
)

/**
 * One model preset, as the model sheet offers it. `/model <preset>` is the switch, so the
 * sheet needs the names the gateway actually has; `active` and `isDefault` are the gateway's
 * own marks rather than a comparison this client re-derives.
 */
public data class ModelPreset(
    public val name: String,
    public val model: String,
    public val provider: String?,
    public val contextWindowTokens: Int?,
    public val active: Boolean = false,
    public val isDefault: Boolean = false,
)

/**
 * Settings → Overview, as one immutable value. Deliberately absent: uptime.
 * The gateway's payload has no uptime field, and the design says so rather than guessing at
 * one from a process start time this client cannot see.
 */
public data class SettingsState(
    public val version: GatewayVersion? = null,
    public val environment: GatewayEnvironment? = null,
    public val modelName: String? = null,
    public val modelPreset: String? = null,
    public val provider: String? = null,
    public val contextWindowTokens: Int? = null,
    public val transcription: TranscriptionSettings? = null,
    public val usage: UsageTotals? = null,
    /** The presets the model sheet offers, in the gateway's own order. */
    public val modelPresets: List<ModelPreset> = emptyList(),
    public val loaded: Boolean = false,
)

/** Readers for the two settings documents; a missing field is `null`, never a throw. */
public object SettingsPayloads {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    public fun settings(text: String): SettingsState {
        val root = root(text) ?: return SettingsState(loaded = true)
        val version = root.child("version")
        val environment = root.child("environment")
        val agent = root.child("agent")
        val transcription = root.child("transcription")
        return SettingsState(
            version = version?.let { child ->
                val current = child.text("current") ?: return@let null
                GatewayVersion(current = current, commit = child.text("commit").orEmpty())
            },
            environment = environment?.let {
                GatewayEnvironment(
                    pythonVersion = it.text("python_version"),
                    os = it.text("os"),
                    osVersion = it.text("os_version"),
                    architecture = it.text("architecture"),
                )
            },
            modelName = agent?.text("model"),
            modelPreset = agent?.text("model_preset"),
            provider = agent?.text("resolved_provider") ?: agent?.text("provider"),
            contextWindowTokens = agent?.integer("context_window_tokens"),
            transcription = transcription?.let {
                TranscriptionSettings(
                    enabled = it.flag("enabled"),
                    provider = it.text("provider"),
                    model = it.text("model"),
                    maxDurationSeconds = it.integer("max_duration_sec"),
                    maxUploadMb = it.integer("max_upload_mb"),
                )
            },
            modelPresets = root.items("model_presets").mapNotNull(::preset),
            loaded = true,
        )
    }

    /** A preset with no name or no model cannot be switched to, so it is not offered. */
    private fun preset(json: JsonObject): ModelPreset? {
        val name = json.text("name") ?: return null
        val model = json.text("model") ?: return null
        return ModelPreset(
            name = name,
            model = model,
            provider = json.text("resolved_provider") ?: json.text("provider"),
            contextWindowTokens = json.integer("context_window_tokens"),
            active = json.flag("active"),
            isDefault = json.flag("is_default"),
        )
    }

    public fun usage(text: String): UsageTotals {
        val root = root(text) ?: return UsageTotals()
        return UsageTotals(
            days = root.items("days").mapNotNull(::day),
            requests30d = root.integer("requests_30d") ?: 0,
            // A rate is a number on the gateway; `decimal` reads a numeric string too, and a value
            // redacted to something unreadable is no rate rather than a wrong percentage.
            cacheHitRate = root.decimal("cache_read_rate_30d"),
        )
    }

    /** A day with no date cannot be placed on the strip, so it is not a bar. */
    private fun day(json: JsonObject): UsageDay? {
        val date = json.text("date")?.takeIf(String::isNotBlank) ?: return null
        return UsageDay(date = date, totalTokens = json.long("total_tokens") ?: 0)
    }

    private fun root(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}
