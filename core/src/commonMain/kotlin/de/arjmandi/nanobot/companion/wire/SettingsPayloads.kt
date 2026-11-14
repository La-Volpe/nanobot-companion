package de.arjmandi.nanobot.companion.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** `version{current, commit}` — the two lines Overview shows (C7.1, design README). */
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
 * One source's share of a day's spend (C7.1). The gateway's sources are its own names —
 * `user`, `api`, `cron`, `dream`, `system` (`nanobot/llm_usage/context.py:8`) — so this is a
 * map keyed by what the gateway said rather than an enum this client would have to grow.
 */
public data class UsageSourceTotals(
    public val inputTokens: Long = 0,
    public val outputTokens: Long = 0,
    public val totalTokens: Long = 0,
    public val requests: Int = 0,
    public val failedRequests: Int = 0,
)

public data class UsageDay(
    public val date: String,
    public val inputTokens: Long = 0,
    public val outputTokens: Long = 0,
    public val totalTokens: Long = 0,
    public val requests: Int = 0,
    public val failedRequests: Int = 0,
    public val sources: Map<String, UsageSourceTotals> = emptyMap(),
)

/** `/api/settings/usage`: the totals beside the day list, which is what Overview sums. */
public data class UsageTotals(
    public val totalTokens: Long = 0,
    public val totalTokens30d: Long = 0,
    public val totalTokens365d: Long = 0,
    public val requests30d: Int = 0,
    public val failedRequests30d: Int = 0,
    public val activeDays30d: Int = 0,
    public val currentStreakDays: Int = 0,
    public val longestStreakDays: Int = 0,
    public val peakDayTokens: Long = 0,
    public val cacheReadTokens30d: Long = 0,
    public val updatedAt: String? = null,
    public val days: List<UsageDay> = emptyList(),
)

/**
 * One model preset (plan.md C6.2, I5's model sheet). `/model <preset>` is the switch, so the
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
 * Settings → Overview (plan.md C7.1), as one immutable value. Deliberately absent: uptime.
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
    /** The presets the model sheet offers, in the gateway's own order (C6.2). */
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
            totalTokens = root.long("total_tokens") ?: 0,
            totalTokens30d = root.long("total_tokens_30d") ?: 0,
            totalTokens365d = root.long("total_tokens_365d") ?: 0,
            requests30d = root.integer("requests_30d") ?: 0,
            failedRequests30d = root.integer("failed_requests_30d") ?: 0,
            activeDays30d = root.integer("active_days_30d") ?: 0,
            currentStreakDays = root.integer("current_streak_days") ?: 0,
            longestStreakDays = root.integer("longest_streak_days") ?: 0,
            peakDayTokens = root.long("peak_day_tokens") ?: 0,
            cacheReadTokens30d = root.long("cache_read_tokens_30d") ?: 0,
            updatedAt = root.text("updated_at"),
            days = root.items("days").map(::day),
        )
    }

    private fun day(json: JsonObject): UsageDay = UsageDay(
        date = json.text("date").orEmpty(),
        inputTokens = json.long("input_tokens") ?: 0,
        outputTokens = json.long("output_tokens") ?: 0,
        totalTokens = json.long("total_tokens") ?: 0,
        requests = json.integer("requests") ?: 0,
        failedRequests = json.integer("failed_requests") ?: 0,
        sources = json.child("sources")?.entries.orEmpty().mapNotNull { (name, value) ->
            (value as? JsonObject)?.let { source ->
                name to UsageSourceTotals(
                    inputTokens = source.long("input_tokens") ?: 0,
                    outputTokens = source.long("output_tokens") ?: 0,
                    totalTokens = source.long("total_tokens") ?: 0,
                    requests = source.integer("requests") ?: 0,
                    failedRequests = source.integer("failed_requests") ?: 0,
                )
            }
        }.toMap(),
    )

    private fun root(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
}
