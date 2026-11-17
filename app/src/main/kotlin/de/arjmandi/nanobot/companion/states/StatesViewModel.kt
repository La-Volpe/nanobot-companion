package de.arjmandi.nanobot.companion.states

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.facade.ConnectionFailure

/**
 * The states that are not part of an answer (the Android twin of iOS's `StatesViewModel`), with
 * the platform's own surface: the design's Android column puts feedback in a snackbar above the
 * composer, so the transient states (a delivery that failed, a retry with its clock, a compaction
 * in flight) share one snackbar with a precedence table instead of stacking banners.
 *
 * One owner per state is the rule. The banner belongs to the connection and nothing else. The goal
 * is a strip and never a transcript row. A notice that needs a decision is a row and never a
 * snackbar. A settled subtask is not in the dock.
 */
class StatesViewModel(
    private val now: () -> Long = { System.currentTimeMillis() },
    private val onDismiss: (String) -> Unit = {},
    private val onResend: (String) -> Unit = {},
    private val onRecovery: (String, StateActionId) -> Unit = { _, _ -> },
    private val onCancelSubagent: (String) -> Unit = {},
    private val onRetryConnection: () -> Unit = {},
) {

    var dot: ConnectionDot by mutableStateOf(ConnectionDot.Idle)
        private set

    var banner: ConnectionBanner? by mutableStateOf(null)
        private set

    var rows: List<StateRow> by mutableStateOf(emptyList())
        private set

    var goalStrip: String? by mutableStateOf(null)
        private set

    var dock: List<DockRow> by mutableStateOf(emptyList())
        private set

    /** The one transient line above the composer. */
    var snackbar: Snackbar? by mutableStateOf(null)
        private set

    /** Dismissed client-owned notices: the core drops its own copy, and this is the same answer sooner. */
    private val dismissed = mutableSetOf<String>()

    private var delivery: DeliveryFailure? by mutableStateOf(null)
    private var notices: List<ThreadNotice> by mutableStateOf(emptyList())

    /** One arrival for both feeds: the socket decides the banner, the thread decides the rows. */
    fun update(
        connection: ConnectionView,
        notices: List<ThreadNotice>,
        subagents: List<SubagentRow>,
        nowMs: Long,
    ) {
        apply(connection)
        this.notices = notices
        rows = rows(notices, nowMs)
        goalStrip = notices.filterIsInstance<ThreadNotice.Goal>().firstOrNull { it.active }?.summary
        dock = subagents.filter { it.isActive }.map { DockRow(it.id, it.label, it.caption) }
        snackbar = snackbar(nowMs)
    }

    /** The thread's own states, kept apart from the connection so the two cannot overwrite each other. */
    fun update(notices: List<ThreadNotice>, subagents: List<SubagentRow> = emptyList(), nowMs: Long) {
        this.notices = notices
        rows = rows(notices, nowMs)
        goalStrip = notices.filterIsInstance<ThreadNotice.Goal>().firstOrNull { it.active }?.summary
        dock = subagents.filter { it.isActive }.map { DockRow(it.id, it.label, it.caption) }
        snackbar = snackbar(nowMs)
    }

    fun update(connection: ConnectionView) {
        apply(connection)
    }

    // Delivery.

    fun deliveryFailed(text: String) {
        delivery = DeliveryFailure(text = text, resending = false)
        snackbar = snackbar(now())
    }

    fun deliverySucceeded() {
        delivery = null
        snackbar = snackbar(now())
    }

    fun resend() {
        val current = delivery ?: return
        delivery = current.copy(resending = true)
        onResend(current.text)
    }

    // Notices.

    fun dismiss(id: String) {
        dismissed += id
        rows = rows.filterNot { it.id == id }
        onDismiss(id)
    }

    fun perform(action: StateActionId, row: StateRow) {
        when (action) {
            StateActionId.Dismiss -> dismiss(row.id)
            StateActionId.Continue -> onRecovery(row.id, action)
        }
    }

    fun cancelSubagent(id: String) {
        onCancelSubagent(id)
        dock = dock.filterNot { it.id == id }
    }

    fun retryConnection() {
        onRetryConnection()
    }

    /** The snackbar's own action: resend the failed message. */
    fun performSnackbarAction() {
        when (snackbar?.action) {
            StateActionId.Continue -> resend()
            StateActionId.Dismiss, null -> Unit
        }
    }

    private fun apply(connection: ConnectionView) {
        when (connection.status) {
            ConnectionStatus.Idle -> {
                dot = ConnectionDot.Idle
                banner = null
            }

            ConnectionStatus.Connecting -> {
                dot = ConnectionDot.Connecting
                banner = ConnectionBanner(ConnectionBannerText.Connecting, showsRetry = false)
            }

            ConnectionStatus.Open -> {
                dot = ConnectionDot.Open
                banner = null
            }

            // A socket that was up and dropped is reconnecting; one that never connected is off.
            ConnectionStatus.Offline -> {
                dot = if (connection.everConnected) ConnectionDot.Reconnecting else ConnectionDot.Offline
                banner = ConnectionBanner(
                    text = if (connection.everConnected) ConnectionBannerText.Reconnecting else ConnectionBannerText.Disconnected,
                    showsRetry = !connection.everConnected,
                )
            }

            ConnectionStatus.Failed -> {
                dot = ConnectionDot.Failed
                banner = ConnectionBanner(connection.failure?.let(::bannerText) ?: ConnectionBannerText.HandshakeFailed, showsRetry = true)
            }
        }
    }

    /** The precedence table: a decision first, then the quiet ones, then a model fallback. */
    private fun rows(notices: List<ThreadNotice>, nowMs: Long): List<StateRow> = notices
        .filterNot { dismissed.contains(it.id) }
        .mapNotNull { notice ->
            when (notice) {
                is ThreadNotice.Recovery -> StateRow(
                    id = notice.id,
                    kind = StateRowKind.Recovery,
                    text = StateRowText(R.string.states_recovery, notice.reason),
                    detailRes = if (notice.canContinue) R.string.states_recovery_restorable else null,
                    value = null,
                    actions = if (notice.canContinue) {
                        listOf(StateAction("Continue", StateActionId.Continue), StateAction("Dismiss", StateActionId.Dismiss))
                    } else {
                        listOf(StateAction("Dismiss", StateActionId.Dismiss))
                    },
                    isError = false,
                )

                is ThreadNotice.Compaction -> StateRow(
                    id = notice.id,
                    kind = StateRowKind.Compaction,
                    text = compactionWording(notice.phase),
                    detailRes = null,
                    value = null,
                    actions = emptyList(),
                    isError = notice.phase == "failed",
                )

                is ThreadNotice.Sandbox -> StateRow(
                    id = notice.id,
                    kind = StateRowKind.Sandbox,
                    text = sandboxWording(notice.issue),
                    detailRes = null,
                    value = null,
                    actions = listOf(StateAction("Dismiss", StateActionId.Dismiss)),
                    isError = true,
                )

                is ThreadNotice.Failure -> StateRow(
                    id = notice.id,
                    kind = StateRowKind.Failure,
                    text = StateRowText(R.string.states_turn_failed, notice.message),
                    detailRes = null,
                    value = notice.errorKind,
                    actions = emptyList(),
                    isError = true,
                )

                is ThreadNotice.PresetFallback -> StateRow(
                    id = notice.id,
                    kind = StateRowKind.PresetFallback,
                    text = StateRowText(R.string.states_preset_fallback, notice.modelName),
                    detailRes = null,
                    value = null,
                    actions = emptyList(),
                    isError = false,
                )

                // A retry belongs to the snackbar (it is transient), and the goal is a strip.
                is ThreadNotice.Retry, is ThreadNotice.Goal -> null
            }
        }
        .sortedBy { precedence(it.kind) }

    private fun precedence(kind: StateRowKind): Int = when (kind) {
        StateRowKind.Recovery -> 0
        StateRowKind.Compaction -> 1
        StateRowKind.Sandbox -> 2
        StateRowKind.Failure -> 3
        StateRowKind.PresetFallback -> 4
    }

    /**
     * Which transient line shows, if any: the message that did not go out first (it is the one with
     * an action), then a retry with its clock, then a compaction in flight.
     */
    private fun snackbar(nowMs: Long): Snackbar? {
        delivery?.let { failed ->
            return Snackbar(
                kind = SnackbarKind.Delivery,
                text = StateRowText(R.string.states_not_sent),
                action = StateActionId.Continue,
            )
        }
        notices.filterIsInstance<ThreadNotice.Retry>().firstOrNull()?.let { retry ->
            // Rounded up: with half a second left the row still says 1s, and it never says 0s
            // while the gateway is still waiting.
            val seconds = retry.retryAtMs?.let { (it - nowMs + 999).coerceAtLeast(0) / 1000 }
            return Snackbar(
                kind = SnackbarKind.Retry,
                text = if (retry.errorKind == "timeout") StateRowText(R.string.states_retry_timeout) else StateRowText(R.string.states_retry_error),
                secondsRemaining = seconds,
            )
        }
        notices.filterIsInstance<ThreadNotice.Compaction>().firstOrNull { it.phase == "started" }?.let {
            return Snackbar(kind = SnackbarKind.Compaction, text = StateRowText(R.string.states_compaction_started))
        }
        return null
    }

    private fun compactionWording(phase: String): StateRowText = when (phase) {
        "failed" -> StateRowText(R.string.states_compaction_failed)
        "cancelled" -> StateRowText(R.string.states_compaction_cancelled)
        "started" -> StateRowText(R.string.states_compaction_started)
        else -> StateRowText(R.string.states_compaction_done)
    }

    private fun sandboxWording(issue: SandboxIssue): StateRowText = StateRowText(
        when (issue) {
            SandboxIssue.LauncherMissing -> R.string.notice_sandbox_missing
            SandboxIssue.Path -> R.string.notice_sandbox_path
            SandboxIssue.Permissions -> R.string.notice_sandbox_permissions
            SandboxIssue.Startup -> R.string.notice_sandbox_startup
        },
    )

    private fun bannerText(failure: ConnectionFailure): ConnectionBannerText = when (failure) {
        ConnectionFailure.AuthFailed -> ConnectionBannerText.AuthFailed
        ConnectionFailure.TlsRejected -> ConnectionBannerText.TlsRejected
        ConnectionFailure.Unreachable -> ConnectionBannerText.Unreachable
        ConnectionFailure.Timeout -> ConnectionBannerText.Timeout
        ConnectionFailure.ProtocolTooNew -> ConnectionBannerText.ProtocolTooNew
        ConnectionFailure.MissingCapability -> ConnectionBannerText.MissingCapability
        ConnectionFailure.MissingField -> ConnectionBannerText.MissingField
        ConnectionFailure.ServerError -> ConnectionBannerText.ServerError
    }

    // The DEBUG menu's two injections: the same state objects the live socket fills.

    fun debugFail(failure: ConnectionFailure, detail: String?) {
        update(ConnectionView(ConnectionStatus.Failed, everConnected = false, failure = failure, detail = detail))
    }

    fun debugInject(state: DebugState.ChatState) {
        when (state) {
            DebugState.ChatState.Offline -> update(ConnectionView(ConnectionStatus.Offline, everConnected = false))
            DebugState.ChatState.Reconnecting -> update(ConnectionView(ConnectionStatus.Offline, everConnected = true))
            DebugState.ChatState.Compaction -> update(
                connection = ConnectionView(ConnectionStatus.Open, everConnected = true),
                notices = listOf(ThreadNotice.Compaction("debug-compaction", "succeeded", null)),
                subagents = emptyList(),
                nowMs = now(),
            )

            DebugState.ChatState.Recovery -> update(
                connection = ConnectionView(ConnectionStatus.Open, everConnected = true),
                notices = listOf(ThreadNotice.Recovery("debug-recovery", "awaiting_user", true, "A task was interrupted while writing.")),
                subagents = emptyList(),
                nowMs = now(),
            )

            DebugState.ChatState.Sandbox -> update(
                connection = ConnectionView(ConnectionStatus.Open, everConnected = true),
                notices = listOf(ThreadNotice.Sandbox("debug-sandbox", SandboxIssue.LauncherMissing)),
                subagents = emptyList(),
                nowMs = now(),
            )

            DebugState.ChatState.Subagent -> update(
                connection = ConnectionView(ConnectionStatus.Open, everConnected = true),
                notices = emptyList(),
                subagents = listOf(SubagentRow("debug-sub", "audit attachment limits", "running", 2, 4.5)),
                nowMs = now(),
            )

            DebugState.ChatState.Queued -> update(
                connection = ConnectionView(ConnectionStatus.Open, everConnected = true),
                notices = emptyList(),
                subagents = emptyList(),
                nowMs = now(),
            )

            DebugState.ChatState.NotSent -> {
                update(ConnectionView(ConnectionStatus.Open, everConnected = true))
                deliveryFailed("One more: does the queue survive a reload?")
            }
        }
    }

    fun debugClear() {
        delivery = null
        update(
            connection = ConnectionView(ConnectionStatus.Open, everConnected = true),
            notices = emptyList(),
            subagents = emptyList(),
            nowMs = now(),
        )
    }
}
