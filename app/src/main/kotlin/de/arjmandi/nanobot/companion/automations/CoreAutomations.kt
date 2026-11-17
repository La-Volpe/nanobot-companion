package de.arjmandi.nanobot.companion.automations

import de.arjmandi.nanobot.companion.core.CoreHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The automations page's driver over the real core: the one file here that knows what a
 * `CoreAutomationJob` is, so the screen and its tests speak [AutomationRow].
 */
class CoreAutomations(private val holder: CoreHolder) : AutomationsDriving {

    override suspend fun load(): List<AutomationRow>? {
        runCatching { holder.refreshAutomations() }
        val jobs = holder.automationJobs
        // A failed read with nothing to show is a failure; with rows in hand it is a stale page.
        return if (jobs.isEmpty() && holder.automationsReadFailed) null else jobs.map(::row)
    }

    override suspend fun act(act: AutomationAct, id: String): String? {
        val action = when (act) {
            AutomationAct.Run -> de.arjmandi.nanobot.companion.facade.AutomationActionKind.Run
            AutomationAct.Pause -> de.arjmandi.nanobot.companion.facade.AutomationActionKind.Disable
            AutomationAct.Resume -> de.arjmandi.nanobot.companion.facade.AutomationActionKind.Enable
            AutomationAct.Delete -> de.arjmandi.nanobot.companion.facade.AutomationActionKind.Delete
        }
        val result = holder.runAutomation(id, action)
        return refusal(result)
    }

    private fun row(job: de.arjmandi.nanobot.companion.wire.AutomationJob) = AutomationRow(
        id = job.id,
        name = job.name,
        schedule = scheduleText(job),
        detail = detailText(job),
        enabled = job.enabled,
        isSystem = job.protected,
    )

    /** The design's second line: a cron expression, an interval, a date, or a local trigger. */
    private fun scheduleText(job: de.arjmandi.nanobot.companion.wire.AutomationJob): String {
        val schedule = job.schedule
        return when (schedule.kind) {
            "cron" -> schedule.expr ?: "Cron"
            "every" -> {
                val every = schedule.everyMs ?: return "Repeating"
                val minutes = every / 60_000
                if (minutes % 60 == 0L) "Every ${minutes / 60} h" else "Every $minutes min"
            }
            "at" -> schedule.atMs?.let { "Once · ${stamp(it)}" } ?: "Once"
            "local" -> "Local trigger"
            else -> schedule.kind.ifEmpty { "No schedule" }
        }
    }

    /** The third line: what the job is doing, the next run when healthy, the error when not. */
    private fun detailText(job: de.arjmandi.nanobot.companion.wire.AutomationJob): String {
        val state = job.state
        state.lastError?.takeIf { it.isNotEmpty() }?.let { return it }
        if (state.pending) return "Running now"
        state.nextRunAtMs?.let { return "Next · ${stamp(it)}" }
        if (job.schedule.kind == "local") return "Fires when the gateway calls it"
        state.lastStatus?.let { return it.replaceFirstChar { c -> c.uppercase() } }
        return "No run yet"
    }

    /** Absolute and short: "Fri 08:00". A relative "in 3 h" would need a clock ticking under the
     *  screen, and reads worse on a page you open once. */
    private fun stamp(epochMs: Long): String =
        SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(epochMs))

    /** The gateway's own status, turned into one sentence. A protection is the gateway saying this
     *  job is its own, rather than an apology. */
    private fun refusal(
        result: de.arjmandi.nanobot.companion.facade.AutomationActionResult,
    ): String? = when (result) {
        is de.arjmandi.nanobot.companion.facade.AutomationActionResult.Done,
        is de.arjmandi.nanobot.companion.facade.AutomationActionResult.Accepted,
        -> null
        is de.arjmandi.nanobot.companion.facade.AutomationActionResult.Refused -> when (result.status) {
            403 -> "This job belongs to the gateway and cannot be changed from here."
            409 -> "That job is busy right now."
            404 -> "That automation is gone."
            else -> result.message ?: "The gateway refused that."
        }
        de.arjmandi.nanobot.companion.facade.AutomationActionResult.NotConnected ->
            "The gateway is not connected."
        de.arjmandi.nanobot.companion.facade.AutomationActionResult.Timeout ->
            "The gateway did not answer in time."
    }
}
