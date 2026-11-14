package de.arjmandi.nanobot.companion.core

import de.arjmandi.nanobot.companion.bridge.Companion
import de.arjmandi.nanobot.companion.facade.ChatHandle
import de.arjmandi.nanobot.companion.facade.ConnectResult
import de.arjmandi.nanobot.companion.facade.ConnectionFailure
import de.arjmandi.nanobot.companion.facade.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What a connect attempt came back as, as a screen sees it: no framework types in the signature,
 * one outcome (the Android twin of iOS's `GatewayOutcome`).
 */
sealed interface GatewayOutcome {
    data class Connected(val modelName: String?) : GatewayOutcome
    data class Failed(val failure: ConnectionFailure, val detail: String?) : GatewayOutcome
}

/** The one command the connect screen sends, so its view model is testable without a socket. */
interface GatewayConnecting {
    suspend fun connect(address: String, key: String, allowUnverified: Boolean): GatewayOutcome
    fun disconnect()
}

/** The connection as the bar and the banner draw it (A1, A8). */
enum class ConnectionStatus { Idle, Connecting, Open, Offline, Failed }

/** The connection plus whether it has ever been open: a dropped socket reconnects, a cold one does not. */
data class ConnectionView(
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val everConnected: Boolean = false,
    val failure: ConnectionFailure? = null,
    val detail: String? = null,
)

/** One drawer row, as Android needs it: the title to search and the preview to show (A2). */
data class SessionSummary(
    val key: String,
    val chatId: String,
    val title: String,
    val preview: String,
    val updatedAtMs: Long?,
) {
    /** The gateway titles a chat from its own first message; an untitled row still needs a name. */
    val displayTitle: String get() = title.ifEmpty { "Untitled session" }
}

/** The drawer's rows, as the list draws them (A2). */
data class SessionListView(
    val rows: List<SessionSummary> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * The one object in the app that holds a `NanobotCore` (plan.md §8, the Android twin of iOS's
 * `CoreHolder`). Everything the screens can show is a `StateFlow` here; the screens read these and
 * send commands, and no composable ever touches the facade.
 *
 * [openStack] is the seam: the app hands it the real `openCompanion`, a test hands it a scripted
 * socket, which is how a view model is tested without a gateway.
 */
class CoreHolder(
    private val scope: CoroutineScope,
    private val openStack: (address: String, secret: String, allowUnverified: Boolean) -> Companion,
) : GatewayConnecting {

    private val _connection = MutableStateFlow(ConnectionView())
    val connection: StateFlow<ConnectionView> = _connection.asStateFlow()

    private val _sessions = MutableStateFlow(SessionListView())
    val sessions: StateFlow<SessionListView> = _sessions.asStateFlow()

    private var stack: Companion? = null
    private val observing = mutableListOf<Job>()

    /** A chat handle for the thread (A3). Null before a gateway is open. */
    fun chat(id: String?): ChatHandle? = stack?.core?.chat(chatId = id)

    /**
     * The driver the chat screen holds for one chat, or null before a gateway is open. Two calls
     * for the same chat share the core's session, so the thread is one value however many screens
     * ask for it.
     */
    fun chatDriver(id: String?): CoreChatDriver? {
        val handle = chat(id) ?: return null
        return CoreChatDriver(handle, scope)
    }

    fun isOpen(): Boolean = stack != null

    override suspend fun connect(address: String, key: String, allowUnverified: Boolean): GatewayOutcome {
        close()
        val opened = openStack(address, key, allowUnverified)
        stack = opened
        observe(opened)
        return try {
            when (val result = opened.core.connect()) {
                is ConnectResult.Connected -> GatewayOutcome.Connected(result.modelName)
                is ConnectResult.Failed -> GatewayOutcome.Failed(result.failure, result.detail)
            }
        } catch (failure: Throwable) {
            // A thrown call is the platform's failure, not the gateway's: nothing answered.
            GatewayOutcome.Failed(ConnectionFailure.Unreachable, failure.message)
        }
    }

    override fun disconnect() {
        close()
        _connection.value = ConnectionView()
        _sessions.value = SessionListView()
    }

    /** The drawer read, and the one a `session_updated` frame triggers inside the core. */
    suspend fun refreshSessions() {
        val core = stack?.core ?: return
        runCatching { core.refreshSessions() }
    }

    /** Settings → Overview and the model sheet both read this (A5); nothing branches here. */
    suspend fun refreshSettings() {
        val core = stack?.core ?: return
        runCatching { core.refreshSettings() }
    }

    fun close() {
        observing.forEach { it.cancel() }
        observing.clear()
        stack?.close()
        stack = null
    }

    /**
     * The connection flow and the session flow, kept for the life of one stack. The core emits on
     * its own dispatchers, so the collection is what turns a frame into state a composable sees.
     */
    private fun observe(opened: Companion) {
        observing += scope.launch {
            opened.core.connection.collect { state ->
                _connection.value = when (state) {
                    is ConnectionState.Open -> ConnectionView(ConnectionStatus.Open, everConnected = true)
                    is ConnectionState.Connecting -> _connection.value.copy(status = ConnectionStatus.Connecting)
                    is ConnectionState.Offline -> _connection.value.copy(status = ConnectionStatus.Offline)
                    is ConnectionState.Failed -> ConnectionView(
                        status = ConnectionStatus.Failed,
                        everConnected = _connection.value.everConnected,
                        failure = state.failure,
                        detail = state.detail,
                    )

                    else -> ConnectionView()
                }
            }
        }
        observing += scope.launch {
            opened.core.sessions.collect { state ->
                _sessions.value = SessionListView(
                    rows = state.rows.map { row ->
                        SessionSummary(
                            key = row.key,
                            chatId = row.chatId,
                            title = row.title,
                            preview = row.preview,
                            updatedAtMs = row.updatedAtMs,
                        )
                    },
                    loaded = state.loaded,
                )
            }
        }
    }
}
