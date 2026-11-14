package de.arjmandi.nanobot.companion.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** When a job runs: `every` interval, `cron` expression, `at` a moment, `local` a named trigger. */
public data class AutomationSchedule(
    public val kind: String,
    public val atMs: Long? = null,
    public val everyMs: Long? = null,
    public val expr: String? = null,
    public val tz: String? = null,
)

/** The job's last run and its next one, which is what the row's second line shows. */
public data class AutomationRunState(
    public val nextRunAtMs: Long? = null,
    public val lastRunAtMs: Long? = null,
    public val lastStatus: String? = null,
    public val lastError: String? = null,
    public val pending: Boolean = false,
    public val runs: Int = 0,
)

/**
 * One automation (`/api/webui/automations`). `protected` marks a system job — heartbeat,
 * dream — which the gateway refuses to enable, run or delete with a 403, so the row shows
 * no switch for it rather than a switch that fails.
 */
public data class AutomationJob(
    public val id: String,
    public val name: String,
    public val enabled: Boolean,
    public val protected: Boolean = false,
    public val deleteAfterRun: Boolean = false,
    public val kind: String? = null,
    public val schedule: AutomationSchedule = AutomationSchedule(kind = ""),
    public val state: AutomationRunState = AutomationRunState(),
    public val message: String? = null,
    public val createdAtMs: Long? = null,
    public val updatedAtMs: Long? = null,
)

public data class AutomationsState(
    public val jobs: List<AutomationJob> = emptyList(),
    public val loaded: Boolean = false,
) {
    public fun job(id: String): AutomationJob? = jobs.firstOrNull { it.id == id }
}

public object AutomationsParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** `{jobs: [...]}` — the same document a successful action answers with, so one parser serves both. */
    public fun parse(text: String): List<AutomationJob> = parseElement(
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull(),
    )

    public fun parseElement(root: JsonObject?): List<AutomationJob> {
        val payload = root?.child("payload") ?: root
        return (payload?.items("jobs") ?: emptyList()).mapNotNull(::job)
    }

    private fun job(json: JsonObject): AutomationJob? {
        val id = json.text("id")?.takeIf { it.isNotBlank() } ?: return null
        val schedule = json.child("schedule")
        val state = json.child("state")
        val payload = json.child("payload")
        return AutomationJob(
            id = id,
            name = json.text("name") ?: id,
            enabled = json.flag("enabled"),
            protected = json.flag("protected"),
            deleteAfterRun = json.flag("delete_after_run"),
            kind = payload?.text("kind"),
            schedule = AutomationSchedule(
                kind = schedule?.text("kind").orEmpty(),
                atMs = schedule?.long("at_ms"),
                everyMs = schedule?.long("every_ms"),
                expr = schedule?.text("expr"),
                tz = schedule?.text("tz"),
            ),
            state = AutomationRunState(
                nextRunAtMs = state?.long("next_run_at_ms"),
                lastRunAtMs = state?.long("last_run_at_ms"),
                lastStatus = state?.text("last_status"),
                lastError = state?.text("last_error"),
                pending = state?.flag("pending") ?: false,
                runs = (state?.get("run_history") as? kotlinx.serialization.json.JsonArray)?.size ?: 0,
            ),
            message = payload?.text("message"),
            createdAtMs = json.long("created_at_ms"),
            updatedAtMs = json.long("updated_at_ms"),
        )
    }
}
