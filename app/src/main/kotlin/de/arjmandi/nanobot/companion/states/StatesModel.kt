package de.arjmandi.nanobot.companion.states

import de.arjmandi.nanobot.companion.facade.ConnectionFailure

/**
 * The states that are not part of an answer, as the app's own values: the
 * connection dot and banner, the notices, the goal strip, the subtask dock, a delivery that failed,
 * and the one snackbar above the composer.
 */

/** A notice as the core reduced it. The bridge maps `Notice` onto these; nothing else branches on it. */
sealed interface ThreadNotice {
    val id: String

    data class Compaction(override val id: String, val phase: String, val reason: String?) : ThreadNotice
    data class Recovery(
        override val id: String,
        val status: String,
        val canContinue: Boolean,
        val reason: String?,
    ) : ThreadNotice

    data class Retry(
        override val id: String,
        val attempt: Int,
        val maxAttempts: Int?,
        val errorKind: String?,
        val retryAtMs: Long?,
    ) : ThreadNotice

    data class Goal(override val id: String, val active: Boolean, val status: String?, val summary: String?) : ThreadNotice
    data class Failure(override val id: String, val errorKind: String?, val message: String?) : ThreadNotice
    data class PresetFallback(override val id: String, val modelName: String?) : ThreadNotice
    data class Sandbox(override val id: String, val issue: SandboxIssue) : ThreadNotice
}

/** What went wrong with the sandbox, as the core names it (one copy key each). */
enum class SandboxIssue(val copyKey: String) {
    LauncherMissing("notice.sandbox.missing"),
    Path("notice.sandbox.path"),
    Permissions("notice.sandbox.permissions"),
    Startup("notice.sandbox.startup"),
}

/** A subtask the parent agent owns (the core's `Subagent`, as a dock row). */
data class SubagentRow(
    val id: String,
    val label: String,
    val state: String,
    val iteration: Int,
    val elapsedSeconds: Double,
) {
    val isActive: Boolean get() = state in ACTIVE

    /** `Running · step 2`: the gateway does not say how many steps there will be, so this does not invent one. */
    val caption: String
        get() = "${when (state) { "queued" -> "Queued"; "stopping" -> "Stopping"; else -> "Running" }} · step $iteration"

    private companion object {
        val ACTIVE = setOf("queued", "running", "stopping")
    }
}

/** The connection as the earlier screens already model it, kept here so the states read one shape. */
data class ConnectionView(
    val status: ConnectionStatus,
    val everConnected: Boolean = false,
    val failure: ConnectionFailure? = null,
    val detail: String? = null,
)

enum class ConnectionStatus { Idle, Connecting, Open, Offline, Failed }

/** The dot's colours, one per state. */
enum class ConnectionDot { Idle, Connecting, Reconnecting, Open, Offline, Failed }

/** The banner's one line, by resource id, and whether "try again" is an honest offer. */
data class ConnectionBanner(val text: ConnectionBannerText, val showsRetry: Boolean)

enum class ConnectionBannerText {
    Connecting,
    Reconnecting,
    Disconnected,
    HandshakeFailed,
    AuthFailed,
    TlsRejected,
    Unreachable,
    Timeout,
    ProtocolTooNew,
    MissingCapability,
    MissingField,
    ServerError,
}

enum class StateRowKind { Recovery, Compaction, Sandbox, Failure, PresetFallback }

/** One notice row, with its actions named so the screen holds the copy. */
data class StateRow(
    val id: String,
    val kind: StateRowKind,
    val text: StateRowText,
    /** A second line, by resource id, when the row has one to say. */
    val detailRes: Int?,
    /** The gateway's own word where the row carries one (an `errorKind`). */
    val value: String?,
    val actions: List<StateAction>,
    val isError: Boolean,
)

/** What a row says, as a resource id and its one number where it has one. */
data class StateRowText(val res: Int, val argument: String? = null)

/** A row's action: continue, dismiss, or resend. */
data class StateAction(val title: String, val identifier: StateActionId)

enum class StateActionId { Continue, Dismiss }

/** A message the gateway did not take: the one state with a real retry. */
data class DeliveryFailure(val text: String, val resending: Boolean = false)

/** The dock row, as the view draws it. */
data class DockRow(val id: String, val label: String, val caption: String)

/**
 * The one transient line above the composer. One owner: the highest-precedence source wins and the
 * ones underneath are never thrown away, so settling the top one brings the next back.
 */
data class Snackbar(
    val kind: SnackbarKind,
    val text: StateRowText,
    /** The retry's countdown, rounded up; null for the sources that have no clock. */
    val secondsRemaining: Long? = null,
    val action: StateActionId? = null,
)

enum class SnackbarKind {
    Delivery,
    Retry,
    Compaction,
}

/** The three commands the states surfaces send to the chat. */
interface StateDriving {
    fun dismissNotice(id: String)
    suspend fun recovery(action: StateActionId)
    suspend fun cancelSubagent(id: String)
}

/** What the DEBUG menu can put the app into: the eight failures and the chat states, by hand. */
object DebugState {

    data class FailureScenario(val kind: ConnectionFailure, val title: String, val detail: String?)

    /** The eight taxonomy entries, with the detail the three that carry one need to be specific. */
    val failureScenarios: List<FailureScenario> = listOf(
        FailureScenario(ConnectionFailure.AuthFailed, "Auth failed", null),
        FailureScenario(ConnectionFailure.TlsRejected, "TLS rejected", null),
        FailureScenario(ConnectionFailure.Unreachable, "Unreachable", null),
        FailureScenario(ConnectionFailure.Timeout, "Timeout", null),
        FailureScenario(ConnectionFailure.ProtocolTooNew, "Protocol too new", "2/1"),
        FailureScenario(ConnectionFailure.MissingCapability, "Missing capability", "webui.subagents.v1"),
        FailureScenario(ConnectionFailure.MissingField, "Missing field", "token"),
        FailureScenario(ConnectionFailure.ServerError, "Server error", "503"),
    )

    /** The chat states the artifact's chips inject, minus the ones v1 does not draw. */
    enum class ChatState(val title: String) {
        Offline("Offline"),
        Reconnecting("Reconnecting"),
        Compaction("Compaction"),
        Recovery("Recovery"),
        Sandbox("Sandbox"),
        Subagent("Subagent task"),
        Queued("Queued prompt"),
        NotSent("Not sent"),
    }
}
