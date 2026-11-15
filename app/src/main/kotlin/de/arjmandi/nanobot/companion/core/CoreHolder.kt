package de.arjmandi.nanobot.companion.core

import de.arjmandi.nanobot.companion.bridge.Companion
import de.arjmandi.nanobot.companion.chat.ChatDriver
import de.arjmandi.nanobot.companion.composer.ChatModelPreset
import de.arjmandi.nanobot.companion.composer.ComposerDriving
import de.arjmandi.nanobot.companion.facade.ChatHandle
import de.arjmandi.nanobot.companion.facade.ConnectResult
import de.arjmandi.nanobot.companion.facade.ConnectionFailure
import de.arjmandi.nanobot.companion.facade.ConnectionState
import de.arjmandi.nanobot.companion.projection.TaskNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import de.arjmandi.nanobot.companion.composer.ComposerSnapshot
import de.arjmandi.nanobot.companion.wire.ModelPreset
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
    /** The last `GET /api/sessions` did not answer; the drawer says so instead of "none". */
    val failed: Boolean = false,
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

    private val _taskNotices = MutableStateFlow<TaskNotice?>(null)

    private var stack: Companion? = null
    private val observing = mutableListOf<Job>()

    /** A chat handle for the thread (A3). Null before a gateway is open. */
    fun chat(id: String?): ChatHandle? = stack?.core?.chat(chatId = id)

    /**
     * The last banner the core's rule allowed (the rule is `:core`, not this app): the activity
     * collects this and posts what it can, and this state is the only "there is something to
     * post". One notice per event: a replayed frame does not move it (`TaskNoticeRule`).
     */
    val taskNotices: StateFlow<TaskNotice?> = _taskNotices.asStateFlow()

    /** Whether the shell is in front of the user, which is half of the notice rule. */
    fun setForeground(foreground: Boolean) {
        stack?.core?.setForeground(foreground)
    }

    /**
     * The chat the shell is showing, as a handle so the core can resolve a chat the greeting has
     * not finished minting: `chat(null)` is the new chat the app is already on.
     */
    fun markOnScreen(chatId: String?) {
        val core = stack?.core ?: return
        core.markOnScreen(core.chat(chatId = chatId))
    }


    /**
     * One chat as the screens drive it (A3, A5): the thread's driver, the composer's commands, and
     * the composer's own state. Null before a gateway is open, which is how a screen knows it has
     * nothing behind it rather than a handle that answers nothing.
     */
    fun chatSession(id: String?): ChatSession? {
        val opened = stack ?: return null
        val handle = chat(id) ?: return null
        val composer = CoreComposerDriver(handle, opened.core, scope)
        return ChatSession(
            chat = CoreChatDriver(handle, scope, mediaBase = opened.core.profile.url),
            composer = composer,
            snapshots = composer.snapshot,
            attachments = CoreAttachmentDriver(handle),
            voice = CoreVoiceDriver(handle),
        )
    }

    /** Whether the last automations read failed, so an empty page can tell "none" from "no answer". */
    var automationsReadFailed: Boolean = false
        private set

    /** The automations page's read (A7). */
    suspend fun refreshAutomations() {
        val core = stack?.core ?: run {
            automationsReadFailed = true
            return
        }
        automationsReadFailed = runCatching { core.refreshAutomations() }.getOrNull() != null
    }

    /** The jobs the core last read. */
    val automationJobs: List<de.arjmandi.nanobot.companion.wire.AutomationJob>
        get() = stack?.core?.automations?.value?.jobs ?: emptyList()

    /** One automation action, over the same socket the thread uses. */
    suspend fun runAutomation(
        id: String,
        action: de.arjmandi.nanobot.companion.facade.AutomationActionKind,
    ): de.arjmandi.nanobot.companion.facade.AutomationActionResult {
        val core = stack?.core ?: return de.arjmandi.nanobot.companion.facade.AutomationActionResult.NotConnected
        return core.runAutomation(id, action, null)
    }

    /**
     * Settings → Overview, from the same read the model sheet uses (C7.1). No uptime: there is none.
     * Null when the read did not answer, so the section says so instead of drawing blank values.
     */
    suspend fun settingsSnapshot(): de.arjmandi.nanobot.companion.settings.SettingsSnapshot? {
        val core = stack?.core ?: return null
        val refusal = runCatching { core.refreshSettings() }.getOrElse { return null }
        if (refusal != null) return null
        val state = core.settings.value
        return de.arjmandi.nanobot.companion.settings.SettingsSnapshot(
            version = state.version?.current,
            commit = state.version?.commit,
            os = state.environment?.os,
            pythonVersion = state.environment?.pythonVersion,
            modelName = state.modelName,
            modelPreset = state.modelPreset,
            provider = state.provider,
            contextWindowTokens = state.contextWindowTokens,
            transcriptionProvider = state.transcription?.provider,
            transcriptionModel = state.transcription?.model,
            presets = state.modelPresets.map { it.name },
        )
    }

    /**
     * The model sheet's list, from the settings read the gateway already answers (C6.2). Null when
     * the read did not answer: the sheet says so instead of showing a list that is empty because
     * nobody looked.
     */
    suspend fun presets(): List<ChatModelPreset>? {
        val core = stack?.core ?: return null
        val refusal = runCatching { core.refreshSettings() }.getOrElse { return null }
        if (refusal != null) return null
        return core.settings.value.modelPresets.map(ModelPreset::toPreset)
    }

    fun isOpen(): Boolean = stack != null

    /**
     * A second handshake on the stack that is already open, which is what "Try again" and a phone
     * returning to the foreground both need: the core bootstraps a fresh single-use token on every
     * `connect()`, so this is a reconnect and not a resume (C3.4). A failure leaves the stack open,
     * so the banner keeps the taxonomy's sentence and a second tap can try again.
     */
    suspend fun reconnect(): Boolean {
        val core = stack?.core ?: return false
        return try {
            core.connect() is ConnectResult.Connected
        } catch (failure: Throwable) {
            false
        }
    }

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
        _taskNotices.value = null
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
        _taskNotices.value = null
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
            opened.core.taskNotice.collect { _taskNotices.value = it }
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
                    failed = state.failed,
                )
            }
        }
    }
}

/**
 * One chat's two bridges and the composer state they publish (A3, A5). The screens hold this
 * bundle, so a chat that has a gateway has both halves and a chat that does not has neither.
 */
data class ChatSession(
    val chat: ChatDriver,
    val composer: ComposerDriving,
    val snapshots: StateFlow<ComposerSnapshot>,
    val attachments: de.arjmandi.nanobot.companion.attachment.AttachmentDriving =
        de.arjmandi.nanobot.companion.attachment.EmptyAttachmentDriver(),
    val voice: de.arjmandi.nanobot.companion.voice.VoiceDriving = de.arjmandi.nanobot.companion.voice.EmptyVoiceDriver(),
)
