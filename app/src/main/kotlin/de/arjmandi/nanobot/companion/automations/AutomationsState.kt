package de.arjmandi.nanobot.companion.automations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One row of the automations page: the design's card is a name, the schedule, what the
 *  job is doing, and either a switch or the "System" flag. */
data class AutomationRow(
    val id: String,
    val name: String,
    val schedule: String,
    val detail: String,
    val enabled: Boolean,
    /** A gateway-owned job: the gateway answers 403, so no switch is drawn. */
    val isSystem: Boolean,
)

/**
 * What the page draws. "The read has not answered" and "the gateway has nothing scheduled" are two
 * different screens, and the owner reported exactly that: an unread page used to be blank, which
 * reads as an empty one.
 */
enum class AutomationsPage { Loading, Empty, Rows, Failed }

/** The four things a row can do. Creating one is the agent's job, not a form's. */
enum class AutomationAct { Run, Pause, Resume, Delete }

/** What the page needs from the core, so the tests describe behaviour instead of a socket. */
interface AutomationsDriving {
    /** The rows, or null when the read failed (the page then keeps what it had). */
    suspend fun load(): List<AutomationRow>?

    /** The gateway's refusal sentence, or null when the act went through. */
    suspend fun act(act: AutomationAct, id: String): String?
}

/**
 * The automations page's state. It lists, runs, pauses and deletes; it never flips a row on its
 * own, because the gateway owns the job, so every act ends in a re-read.
 */
class AutomationsViewModel(private val driver: AutomationsDriving) {
    var rows by mutableStateOf<List<AutomationRow>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(false)
        private set

    /** One sentence for the last refusal, said the way every other failure in this app is. */
    var message by mutableStateOf<String?>(null)
        private set

    /** One truth for "the page really is empty": a failed read is not the same sentence. */
    val isEmpty: Boolean get() = page == AutomationsPage.Empty

    /** What the list area draws: a skeleton, the jobs, the "none" sentence, or the failure sentence. */
    val page: AutomationsPage
        get() = when {
            // Rows already on screen outrank any failure: a stale page beats a blank one.
            rows.isNotEmpty() -> AutomationsPage.Rows
            isLoading -> AutomationsPage.Loading
            message != null -> AutomationsPage.Failed
            loaded -> AutomationsPage.Empty
            else -> AutomationsPage.Loading
        }

    suspend fun load() {
        isLoading = true
        try {
            val fresh = driver.load()
            if (fresh == null) {
                message = "Could not read the automations from the gateway."
                return
            }
            rows = fresh
            loaded = true
            message = null
        } finally {
            isLoading = false
        }
    }

    /** The switch on a row: pause keeps the schedule, resume starts following it again. */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        perform(if (enabled) AutomationAct.Resume else AutomationAct.Pause, id)
    }

    suspend fun runNow(id: String) = perform(AutomationAct.Run, id)

    suspend fun delete(id: String) = perform(AutomationAct.Delete, id)

    private suspend fun perform(act: AutomationAct, id: String) {
        val refusal = driver.act(act, id)
        // Re-read rather than guess: a row that flipped locally while the gateway refused would
        // be a lie, and the refusal is the sentence the user needs.
        load()
        message = refusal
    }
}
