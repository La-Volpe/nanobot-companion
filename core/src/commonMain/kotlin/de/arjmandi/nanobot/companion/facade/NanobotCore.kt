package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.engine.EngineHost
import de.arjmandi.nanobot.companion.engine.EngineOpen
import de.arjmandi.nanobot.companion.engine.TurnEngine
import de.arjmandi.nanobot.companion.platform.CoreDeps
import de.arjmandi.nanobot.companion.projection.ChatRouter
import de.arjmandi.nanobot.companion.projection.ChatSet
import de.arjmandi.nanobot.companion.projection.Effect
import de.arjmandi.nanobot.companion.projection.PaletteCatalog
import de.arjmandi.nanobot.companion.projection.SessionListState
import de.arjmandi.nanobot.companion.projection.NoticeDecision
import de.arjmandi.nanobot.companion.projection.ScreenPresence
import de.arjmandi.nanobot.companion.projection.TaskNotice
import de.arjmandi.nanobot.companion.projection.TaskNoticeRule
import de.arjmandi.nanobot.companion.projection.ThreadCache
import de.arjmandi.nanobot.companion.projection.ThreadReducer
import de.arjmandi.nanobot.companion.projection.TurnControl
import de.arjmandi.nanobot.companion.projection.ThreadState
import de.arjmandi.nanobot.companion.transport.AutomationsClient
import de.arjmandi.nanobot.companion.transport.AutomationsOutcome
import de.arjmandi.nanobot.companion.transport.BootstrapClient
import de.arjmandi.nanobot.companion.transport.CandidateFile
import de.arjmandi.nanobot.companion.transport.ConnectionAttempt
import de.arjmandi.nanobot.companion.transport.CoreError
import de.arjmandi.nanobot.companion.transport.PaletteClient
import de.arjmandi.nanobot.companion.transport.PaletteOutcome
import de.arjmandi.nanobot.companion.projection.HistoryReplay
import de.arjmandi.nanobot.companion.transport.ThreadPageClient
import de.arjmandi.nanobot.companion.transport.ThreadPageOutcome
import de.arjmandi.nanobot.companion.transport.SessionListClient
import de.arjmandi.nanobot.companion.transport.SettingsClient
import de.arjmandi.nanobot.companion.transport.SettingsOutcome
import de.arjmandi.nanobot.companion.transport.SessionListOutcome
import de.arjmandi.nanobot.companion.transport.UploadClient
import de.arjmandi.nanobot.companion.transport.UploadOutcome
import de.arjmandi.nanobot.companion.transport.WsSession
import de.arjmandi.nanobot.companion.wire.AttachedFrame
import de.arjmandi.nanobot.companion.wire.AutomationJob
import de.arjmandi.nanobot.companion.wire.AutomationsParser
import de.arjmandi.nanobot.companion.wire.AutomationsState
import de.arjmandi.nanobot.companion.wire.BootstrapPayload
import de.arjmandi.nanobot.companion.wire.ErrorFrame
import de.arjmandi.nanobot.companion.wire.Frame
import de.arjmandi.nanobot.companion.wire.Outbound
import de.arjmandi.nanobot.companion.wire.QuotedContext
import de.arjmandi.nanobot.companion.wire.ReadyFrame
import de.arjmandi.nanobot.companion.wire.RecoveryStateFrame
import de.arjmandi.nanobot.companion.wire.TranscriptionErrorFrame
import de.arjmandi.nanobot.companion.wire.TranscriptionResultFrame
import de.arjmandi.nanobot.companion.wire.SessionUpdatedFrame
import de.arjmandi.nanobot.companion.wire.SessionRow
import de.arjmandi.nanobot.companion.wire.TurnEndFrame
import de.arjmandi.nanobot.companion.wire.TurnModelUpdatedFrame
import de.arjmandi.nanobot.companion.wire.SettingsState
import de.arjmandi.nanobot.companion.wire.UploadCapability
import de.arjmandi.nanobot.companion.wire.UploadReference
import de.arjmandi.nanobot.companion.wire.WebuiResponseFrame
import io.ktor.client.HttpClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where the app keeps the gateway secret in the platform secure store. */
public const val GATEWAY_SECRET_KEY: String = "gateway.secret"

/** How many announced notice keys the core remembers: enough for a replayed batch, not a log. */
private const val ANNOUNCED_MEMORY: Int = 32

/**
 * The whole public surface. Everything below it returns effects as data; this
 * is where they are executed, and where the state a screen reads lives.
 *
 * Calls are expected from the platform's main thread, which is how SwiftUI and Compose drive
 * it. The frame pump arrives from the compute dispatcher and writes only through the session
 * [MutableStateFlow]s, so a reader never sees a half-applied frame.
 */
public class NanobotCore internal constructor(
    public val profile: GatewayProfile,
    public val deps: CoreDeps,
    private val attempt: ConnectionAttempt,
    private val http: HttpClient?,
) {

    public constructor(profile: GatewayProfile, deps: CoreDeps, http: HttpClient) : this(
        profile = profile,
        deps = deps,
        attempt = ConnectionAttempt(
            bootstrap = { secret -> BootstrapClient(http, profile.url).fetch(secret) },
            openSocket = WsSession(http)::connect,
        ),
        http = http,
    )

    private val scope: CoroutineScope = CoroutineScope(deps.dispatchers.compute + SupervisorJob())

    private val host: EngineHost = object : EngineHost() {
        override suspend fun opening(payload: BootstrapPayload) = onOpening(payload)
        override suspend fun frame(frame: Frame): List<Effect> = onFrame(frame)
        override suspend fun closed(error: CoreError?) = onClosed(error)
        override suspend fun effect(effect: Effect) = onEffect(effect)
    }

    /**
     * What the chip names: a preset, and the model and window that preset runs. One value,
     * because they are one answer: the name from one preset beside another's window is how a
     * brand-new chat read `GLM` over a 256 K ring.
     */
    private data class ModelOnChip(val preset: String?, val name: String?, val window: Int?)

    private val engine: TurnEngine = TurnEngine(scope, attempt, host)

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val _sessions = MutableStateFlow(SessionListState())

    /** What the two composer palettes are drawn from; commands and apps arrive on demand. */
    private val _palette = MutableStateFlow(PaletteCatalog())

    /** Settings → Overview; read when the screen opens, not at connect. */
    private val _settings = MutableStateFlow(SettingsState())

    /** The automations page; the list is a read, each action is a socket mutation. */
    private val _automations = MutableStateFlow(AutomationsState())

    private val _taskNotice = MutableStateFlow<TaskNotice?>(null)

    /** Where the user is, as the notice rule needs it: in front of the app, and on which chat. */
    private var _foreground: Boolean = false
    private var visibleSession: ChatSession? = null

    /** The last announced keys, so a replayed settle frame is not a second banner. */
    private val announced = ArrayDeque<String>()

    /** Sessions the client knows about, keyed by chat id; the map is swapped, never mutated. */
    private val registry = MutableStateFlow<Map<String, ChatSession>>(emptyMap())

    /** The chat a `new_chat` will become; it has no id until the gateway mints one. */
    private val pending = MutableStateFlow<ChatSession?>(null)

    private val pendingTranscriptions = mutableMapOf<String, CompletableDeferred<TranscriptionReply>>()

    /** One entry per `webui_request` in flight; the pump resolves it from the answer frame. */
    private val pendingRequests = mutableMapOf<String, CompletableDeferred<WebuiReply>>()

    /**
     * What a `webui_request` can come back as: the HTTP route's own answer, its refusal, or
     * nothing at all because the socket was gone or the gateway never answered.
     */
    private sealed interface WebuiReply {
        data class Ok(val result: kotlinx.serialization.json.JsonElement?) : WebuiReply
        data class Refused(val status: Int, val message: String?) : WebuiReply
        data object NotConnected : WebuiReply
        data object Timeout : WebuiReply
    }

    /** What a `transcribe_audio` request can come back as. */
    private sealed interface TranscriptionReply {
        data class Text(val value: String) : TranscriptionReply
        data class Refused(val detail: String) : TranscriptionReply
    }

    private var chatSet: ChatSet = ChatSet()
    private var payload: BootstrapPayload? = null
    private var uploadCapability: UploadCapability? = null

    /** The chat the last `ready` minted, so a handle opened after connect can still bind. */
    private var greetingChatId: String? = null
    private var sequence: Int = 0

    /** The in-flight drawer read; a second `session_updated` replaces it instead of queueing. */
    private var drawerRefresh: Job? = null

    /** Chats whose stored history this socket has already replayed. */
    private val hydrated = mutableSetOf<String>()

    public val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    public val sessions: StateFlow<SessionListState> = _sessions.asStateFlow()
    public val palette: StateFlow<PaletteCatalog> = _palette.asStateFlow()
    public val settings: StateFlow<SettingsState> = _settings.asStateFlow()
    public val automations: StateFlow<AutomationsState> = _automations.asStateFlow()

    /**
     * The last banner the notice rule allowed, or null when it allowed none. A platform posts
     * on a [TaskNotice.key] it has not posted and cancels by chat when the user opens the chat.
     */
    public val taskNotice: StateFlow<TaskNotice?> = _taskNotice.asStateFlow()

    /** Whether the app is in front of the user; the shell reports it (`scenePhase`, `onStart`). */
    public fun setForeground(foreground: Boolean) {
        _foreground = foreground
        // The engine asks the socket whether it is still there while the user is; a return asks
        // at once, so a connection that died in a pocket shows the banner the moment the app is
        // looked at instead of a green dot over a socket that cannot carry anything.
        engine.setWatched(foreground)
    }

    /** The chat whose thread the shell is showing, or null when no chat is on screen. */
    public fun markOnScreen(handle: ChatHandle?) {
        visibleSession = handle?.session
    }

    /** Jobs the core started and has not joined: a leak check reads this. */
    public fun activeChildJobs(): Int = engine.activeChildJobs()

    /**
     * Bootstrap, handshake, `ready`. Every call mints a fresh single-use token, so this is also
     * the reconnect path, and there is no separate "resume".
     */
    @Throws(Throwable::class)
    public suspend fun connect(): ConnectResult {
        _connection.value = ConnectionState.Connecting
        return when (val opened = engine.connect(profile.secret)) {
            is EngineOpen.Failed -> {
                val mapped = opened.error.connectionFailure()
                _connection.value = mapped
                ConnectResult.Failed(error = opened.error, failure = mapped.failure, detail = mapped.detail)
            }

            is EngineOpen.Ready -> {
                // No `Open` here: the pump sets it when it reduces `ready`, and that is the only
                // place it can be set in order. Assigning it again on the way out of `connect()`
                // clobbered the `Offline` a socket that died during the handshake had already
                // reported: a green dot over a socket nobody was reading, which is how a
                // closed-by-the-gateway connection stayed "Open" for the life of the app.
                ConnectResult.Connected(opened.payload.modelName)
            }
        }
    }

    /** Close the socket. [forget] also drops the stored key (Settings → Gateway). */
    @Throws(Throwable::class)
    public suspend fun disconnect(forget: Boolean) {
        engine.close()
        _connection.value = ConnectionState.Idle
        _sessions.value = SessionListState()
        _palette.value = PaletteCatalog()
        _settings.value = SettingsState()
        _automations.value = AutomationsState()
        if (forget) deps.secureStore.remove(GATEWAY_SECRET_KEY)
    }

    /**
     * The handle for one chat, or for the new chat when [chatId] is null. Asking twice for the
     * same chat yields the same handle, so a view can call this on every appearance without
     * resetting what the socket already filled in.
     */
    public fun chat(chatId: String?): ChatHandle {
        val session = if (chatId == null) newChatSession() else registered(chatId)
        // A read may already have answered for this chat; the chip does not wait for a turn.
        mirrorComposer(session, session.id.value)
        return ChatHandle(this, session)
    }

    /**
     * The one session a "new chat" means. Before the greeting there is nothing to name, so it is
     * the single pending slot; after it, it is the session the greeting minted. A fresh session
     * per ask was two chats wearing one name: the shell asks twice (once to mark the chat for the
     * notice rule, once for the screen), and the second one was never in the registry, so the
     * send filled a thread nobody drew and its history stayed unread forever.
     */
    private fun newChatSession(): ChatSession {
        val greeted = greetingChatId
        if (greeted != null) return registered(greeted)
        val created = ChatSession(id = MutableStateFlow(null))
        pending.update { current -> current ?: created }
        return pending.value ?: created
    }

    /** One session per chat id, whichever caller asked first. */
    private fun registered(chatId: String): ChatSession {
        val created = ChatSession(id = MutableStateFlow(chatId))
        registry.update { current -> current + (chatId to (current[chatId] ?: created)) }
        return registry.value.getValue(chatId)
    }

    /** Refresh the drawer from `GET /api/sessions` with the bootstrap `api_token` as a bearer. */
    @Throws(Throwable::class)
    public suspend fun refreshSessions() {
        val token = payload?.apiToken
        val client = http
        if (token == null || client == null) {
            _sessions.value = SessionListState(rows = _sessions.value.rows, loaded = true)
            return
        }
        val outcome = SessionListClient(client, profile.url).fetch(token)
        val rows = (outcome as? SessionListOutcome.Loaded)?.rows ?: _sessions.value.rows
        // A refresh that fails keeps the rows it had: an empty drawer is worse than a stale one. It
        // also says so, because a drawer that reports "no sessions yet" after a 500 is lying about
        // the gateway rather than about itself.
        _sessions.value = SessionListState(
            rows = rows,
            loaded = true,
            failed = outcome is SessionListOutcome.Failed,
        )
        // The rows carry each chat's own `model_preset`, which outranks the gateway's current one.
        settlePicks(rows)
        republishComposerModels()
        // The mention sheet names chats by the same rows, so it is refreshed with them.
        _palette.value = _palette.value.copy(sessions = PaletteCatalog.sessionsIn(rows))
    }

    /** The automations page (`GET /api/webui/automations`). */
    @Throws(Throwable::class)
    public suspend fun refreshAutomations(): CoreError? {
        val token = payload?.apiToken ?: return automationsFailed(CoreError.AuthFailed)
        val client = http ?: return automationsFailed(CoreError.Unreachable)
        return when (val outcome = AutomationsClient(client, profile.url).fetch(token)) {
            is AutomationsOutcome.Loaded -> {
                _automations.value = AutomationsState(jobs = outcome.jobs, loaded = true)
                null
            }
            is AutomationsOutcome.Failed -> automationsFailed(outcome.error)
        }
    }

    /**
     * A read that did not answer leaves the state saying so, and keeps the jobs it already had:
     * a stale page beats a blank one, and the page needs the difference between "refused" and
     * "none" to avoid saying the wrong thing.
     */
    private fun automationsFailed(error: CoreError): CoreError {
        _automations.value = _automations.value.copy(loaded = true, failed = true)
        return error
    }

    /**
     * The automations read runs beside the pump, like the settings one: `/api/webui/automations`
     * is an HTTP round trip. A reconnect blanks the page's state (`disconnect`), so the new socket
     * asks again rather than leaving the page empty until someone taps it.
     */
    private fun readAutomationsLater() {
        scope.launch {
            if (payload?.apiToken == null || http == null) return@launch
            refreshAutomations()
        }
    }

    /**
     * One automation action, sent as a socket mutation. A successful action answers
     * with the whole list, so the page never has to re-read to show the switch flipped.
     */
    @Throws(Throwable::class)
    public suspend fun runAutomation(
        id: String,
        action: AutomationActionKind,
        chatChange: AutomationChatChange? = null,
    ): AutomationActionResult {
        if (action == AutomationActionKind.ChangeChat && chatChange == null) {
            // The gateway refuses an empty `values` object with a 400; refusing here saves a
            // round trip and names the mistake in the code rather than in a status.
            return AutomationActionResult.Refused(HTTP_400, "automation_chat_values_required")
        }
        val payload = buildJsonObject {
            put("id", JsonPrimitive(id))
            if (chatChange != null) {
                put(
                    "values",
                    buildJsonObject {
                        put("target_id", JsonPrimitive(chatChange.targetId))
                        put("revision", JsonPrimitive(chatChange.revision))
                        put("message", JsonPrimitive(chatChange.message))
                    },
                )
            }
        }
        return when (val reply = mutation(action.wire, payload)) {
            WebuiReply.NotConnected -> AutomationActionResult.NotConnected
            WebuiReply.Timeout -> AutomationActionResult.Timeout
            is WebuiReply.Refused -> AutomationActionResult.Refused(reply.status, reply.message)
            is WebuiReply.Ok -> {
                val jobs = AutomationsParser.parseElement(reply.result as? JsonObject)
                if (jobs.isNotEmpty()) _automations.value = AutomationsState(jobs = jobs, loaded = true)
                AutomationActionResult.Done(jobs)
            }
        }
    }

    /**
     * Version, environment and usage (`GET /api/settings`, `/api/settings/usage`). A
     * refused read leaves the screen empty rather than showing a stale number as if it were
     * current, and the error comes back so the screen can offer a retry.
     */
    @Throws(Throwable::class)
    public suspend fun refreshSettings(): CoreError? {
        val token = payload?.apiToken ?: return CoreError.AuthFailed
        val client = http ?: return CoreError.Unreachable
        return when (val outcome = SettingsClient(client, profile.url).fetch(token)) {
            is SettingsOutcome.Loaded -> {
                _settings.value = outcome.state
                // The settings read is the first thing that names a chat's model, so the chips
                // that were waiting on it take it now.
                republishComposerModels()
                null
            }
            is SettingsOutcome.Failed -> outcome.error
        }
    }

    /**
     * The slash palette and the CLI app mentions (`GET /api/commands`,
     * `GET /api/settings/cli-apps`). The WebUI loads these when the sheet opens rather than at
     * connect, and returns the error so the sheet can say "try again" instead of showing a half
     * list.
     */
    @Throws(Throwable::class)
    public suspend fun refreshPalettes(): CoreError? {
        val token = payload?.apiToken ?: return CoreError.AuthFailed
        val client = http ?: return CoreError.Unreachable
        return when (val outcome = PaletteClient(client, profile.url).fetch(token)) {
            is PaletteOutcome.Loaded -> {
                _palette.value = _palette.value.withReads(outcome.commands, outcome.apps)
                null
            }
            is PaletteOutcome.Failed -> outcome.error
        }
    }

    // --- EngineHost: the only place frames become state -------------------------------

    private suspend fun onOpening(payload: BootstrapPayload) {
        this.payload = payload
        // The chip is the gateway's own answer and it has one for a chat that has no turns yet,
        // so it is read once per socket rather than as a side effect of opening a chat with an
        // id: a brand-new chat has none, and it named nothing until a turn ran.
        readModelsLater()
        readAutomationsLater()
        // A pick belongs to one chat on one socket: `/model` answers with a turn and no frame, so
        // nothing ever cleared it, and the next socket's chats wore the last one's preset.
        // The greeting's drawer read puts back the preset each chat really is on, from the gateway.
        registry.value.values.forEach { session ->
            session.pickedPreset = null
            session.pickAnswered = false
        }
        // The greeting chat was minted by the socket that is gone; a "new chat" asked for on a
        // fresh socket must not reopen it while the new greeting is still on its way.
        greetingChatId = null
        // A new socket was told nothing: the gateway subscribes per connection (`runtime.py`
        // `_attach`, `_conn_chats`), so an `attached` from the socket before it says nothing about
        // this one. Without this, `open()` after a reconnect asks for nothing and an answer that
        // was streaming when the phone slept never arrives. The threads stay; only
        // the facts that belong to one connection are dropped.
        chatSet = chatSet.copy(attached = emptySet(), attaching = emptySet())
        // The limits the composer validates against are the gateway's, not ours.
        registry.value.values.forEach { session -> session.composer.update { it.copy(limits = payload.limits) } }
    }

    private suspend fun onFrame(frame: Frame): List<Effect> {
        when (frame) {
            is ReadyFrame -> {
                _connection.value = ConnectionState.Open
                // The upload capability is per connection and short-lived; it is never persisted.
                uploadCapability = frame.upload
                greetingChatId = frame.chatId
                // The greeting is what mints the first chat, so a `chat(null)` handle learns
                // its id here rather than inventing one.
                bindPending(frame.chatId)
                refreshDrawerLater()
            }

            is AttachedFrame -> bindPending(frame.chatId)
            is SessionUpdatedFrame -> refreshDrawerLater()
            is TranscriptionResultFrame -> frame.requestId?.let { id ->
                pendingTranscriptions.remove(id)?.complete(TranscriptionReply.Text(frame.text.orEmpty()))
            }

            is TranscriptionErrorFrame -> frame.requestId?.let { id ->
                pendingTranscriptions.remove(id)?.complete(TranscriptionReply.Refused(frame.detail ?: "transcription_failed"))
            }

            is WebuiResponseFrame -> frame.requestId?.let { id ->
                pendingRequests.remove(id)?.complete(
                    if (frame.ok) {
                        WebuiReply.Ok(frame.result)
                    } else {
                        WebuiReply.Refused(frame.errorStatus ?: HTTP_500, frame.errorMessage)
                    },
                )
            }

            else -> Unit
        }

        // The gateway naming the model a turn ran is the answer to a `/model` pick, so the
        // client's own guess steps aside before the row is mirrored.
        if (frame is TurnModelUpdatedFrame && !frame.modelPreset.isNullOrBlank()) {
            frame.chatId?.let { chatId -> registry.value[chatId]?.pickedPreset = null }
        }

        val routed = ChatRouter.reduce(chatSet, frame, deps.clock.nowMs())
        chatSet = routed.set
        routed.set.threads.forEach { (chatId, thread) -> publish(chatId, thread) }

        // A settled turn releases the prompt that was waiting for it, now that the
        // thread it belongs to has taken its answer.
        if (frame is TurnEndFrame) {
            releaseQueued(frame.chatId, frame.turnId)
            // `/model` is answered by a turn and nothing else, so the settled turn is the first
            // moment the chat's session row can confirm a pick or contradict it:
            // `set_session_model_preset` committed the metadata before the reply went out.
            frame.chatId?.let { chatId ->
                val session = registry.value[chatId]
                if (session?.pickedPreset != null) {
                    session.pickAnswered = true
                    refreshDrawerLater()
                }
            }
        }

        // The frame that ends a turn or stops a recovery is the only thing the gateway sends
        // that a user away from the app is waiting on, so it is also the only thing that may
        // become a banner (the WebUI's `lib/browser-notifications.ts` reads the same two).
        raiseTaskNotice(frame)

        // A refused scope switch is the only way the gateway answers `set_workspace_scope`
        // (inbound_commands.py:283), so the chip goes back rather than staying wrong.
        if (frame is ErrorFrame && frame.detail == SCOPE_REJECTED) {
            frame.chatId?.let { chatId ->
                registry.value[chatId]?.let { session ->
                    session.composer.update { composer ->
                        composer.copy(accessMode = session.scopeBeforeSwitch ?: composer.accessMode)
                    }
                    session.scopeBeforeSwitch = null
                }
            }
        }
        return routed.effects
    }

    /**
     * One decision per settled frame, taken by the pure rule in `projection`. A chat the shell is
     * showing while the app is in front of the user is silent; anything else that a user is
     * waiting on becomes [taskNotice] once.
     */
    private fun raiseTaskNotice(frame: Frame) {
        val event = when (frame) {
            is TurnEndFrame -> TaskNoticeRule.turnEnd(
                chatId = frame.chatId,
                turnId = frame.turnId,
                outcome = frame.outcome,
                failureKind = frame.failureKind,
            )

            is RecoveryStateFrame -> TaskNoticeRule.recoveryState(
                chatId = frame.chatId,
                recoveryId = frame.recoveryId,
                status = frame.status,
            )

            else -> null
        } ?: return
        val presence = ScreenPresence(
            isForeground = _foreground,
            // Resolved here rather than by the shell: a chat the greeting minted has no id
            // until it is bound, and the shell only knows the id it asked for.
            visibleChatId = visibleSession?.id?.value,
        )
        val decision = TaskNoticeRule.decide(event, presence, announced.toList())
        if (decision !is NoticeDecision.Deliver) return
        announced.addLast(decision.notice.key)
        while (announced.size > ANNOUNCED_MEMORY) announced.removeFirst()
        _taskNotice.value = decision.notice
    }

    private suspend fun onClosed(error: CoreError?) {
        _connection.value = if (error == null) ConnectionState.Offline else error.connectionFailure()
    }

    private suspend fun onEffect(effect: Effect) {
        when (effect) {
            is Effect.PersistCache -> persist(effect.chatId)
            // The socket is the engine's own; a delivery settle and a queue flush land with
            // the composer tasks that produce them.
            is Effect.Send, is Effect.SettleDelivery, Effect.ScheduleQueueFlush -> Unit
        }
    }

    // --- what a handle asks for --------------------------------------------------------

    /**
     * The bytes go to `POST {ready.upload.path}` with the capability the greeting handed out;
     * the reference stays with the session until a message carries it.
     */
    internal suspend fun upload(session: ChatSession, file: PickedFile): UploadResult {
        val capability = uploadCapability ?: return UploadResult.Failed(CoreError.Unreachable)
        val client = http ?: return UploadResult.Failed(CoreError.Unreachable)
        val candidate = CandidateFile(name = file.name, mimeType = file.mimeType, bytes = file.bytes.size.toLong())
        return when (val outcome = UploadClient(client, profile.url).upload(capability, candidate, file.bytes)) {
            is UploadOutcome.Uploaded -> {
                session.media += UploadReference(reference = outcome.reference, name = file.name)
                UploadResult.Uploaded(outcome.reference)
            }

            is UploadOutcome.Failed -> UploadResult.Failed(outcome.error)
        }
    }

    /** A client turn id: stamped with the injected clock so a test can predict it. */
    internal fun nextTurnId(): String = "companion-${deps.clock.nowMs()}-${sequence++}"

    internal suspend fun sendDraft(
        chatId: String,
        draft: OutboundDraft,
        fromQueue: Boolean = false,
    ): SendReceipt {
        val session = registry.value[chatId]
        val text = draft.text.trim()
        // The draft is the composer's memory: it goes in before the attempt and only leaves
        // when the socket took the message. A released prompt is not the draft, so what
        // the reader has typed meanwhile stays where it is.
        if (!fromQueue) session?.composer?.update { it.copy(draft = draft.text) }

        if (text.isEmpty()) return SendReceipt.Rejected(SendRejection.EmptyDraft)
        val limits = session?.composer?.value?.limits ?: payload?.limits
        if (limits != null && text.encodeToByteArray().size > limits.maxTextBytes) {
            return SendReceipt.Rejected(SendRejection.FrameTooLarge)
        }

        val turnId = nextTurnId()
        val quote = draft.quote?.let(QuotedContext::of)
        // Attachments are references: whatever was uploaded and not yet sent rides this message.
        // A composer that hands the send the references its own cards hold names the same file
        // twice (once in the draft, once staged on the session), and the gateway refuses that
        // whole message (`attachment_rejected`, "Duplicate attachment reference"), so one file is
        // one reference however the two lists overlapped.
        val media = (draft.media.map { UploadReference(reference = it.reference, name = it.name) } +
            session?.media.orEmpty()).distinctBy { it.reference }
        val message = Outbound.Message(
            chatId = chatId,
            turnId = turnId,
            content = text,
            media = media,
            quotedContext = quote,
        )
        // A chat the socket has not attached gets its attach first, and the message waits
        // behind it: sending first would race the attach and lose the turn.
        val routed = ChatRouter.send(chatSet, chatId, message)
        chatSet = routed.set
        val outcomes = routed.effects.mapNotNull { (it as? Effect.Send)?.frame }.map { engine.send(it) }
        if (!outcomes.all { it }) return SendReceipt.Rejected(SendRejection.NotConnected)

        // Only a message the socket took gets a bubble; the thread the frame will land in is
        // the one the router already holds.
        val thread = ThreadReducer.addLocalPrompt(chatSet.thread(chatId), turnId, text, quote?.text, deps.clock.nowMs())
        chatSet = chatSet.copy(threads = chatSet.threads + (chatId to thread))
        session?.thread?.value = thread
        if (!fromQueue) session?.composer?.update { it.copy(draft = "") }
        session?.media?.clear()
        return SendReceipt.Accepted(turnId)
    }

    internal suspend fun write(frame: Outbound): Boolean = engine.send(frame)

    /** A command that changed what the row draws asks for a refresh once, which a refused pick must. */
    internal fun remirrorComposer(session: ChatSession, chatId: String?) {
        mirrorComposer(session, chatId)
    }

    /**
     * One `attach` for a chat the screen opened, then the history page. The socket's `attached`
     * carries the session's runtime facts, not its turns, so an opened chat only shows what came
     * before once the WebUI's own page read has been reduced into the same thread, by the same
     * reducer as a live turn.
     */
    internal suspend fun openChat(chatId: String) {
        // Once per chat per socket: the page cannot have changed under a chat this core has
        // already replayed, and a second read would double nothing but the round trip. The read
        // is in flight from the moment it is asked for, before the attach goes out, so a screen
        // that has just asked never draws "nothing here" over a page that is on its way.
        val fresh = hydrated.add(chatId)
        if (fresh) registry.value[chatId]?.history?.value = HistoryStatus.Loading
        val routed = ChatRouter.open(chatSet, chatId)
        chatSet = routed.set
        routed.effects.mapNotNull { (it as? Effect.Send)?.frame }.forEach { engine.send(it) }
        coroutineScope {
            if (fresh) launch { replayHistory(chatId) }
        }
    }

    /**
     * The settings read runs beside the pump: `/api/settings` is an HTTP round trip, and a
     * connect that awaited one would be as slow as the gateway's slowest read.
     */
    private fun readModelsLater() {
        scope.launch { readModelsForChip() }
    }

    /** Once per socket: the gateway's own answer to "which preset is this chat on". */
    private suspend fun readModelsForChip() {
        if (_settings.value.loaded || payload?.apiToken == null || http == null) return
        refreshSettings()
    }

    /**
     * The stored turns of one chat, under whatever the socket already showed. A read that fails
     * leaves the thread as it is: an empty thread is honest, invented rows are not. It does not
     * leave the thread *looking* empty, though: the status says the page never came.
     */
    private suspend fun replayHistory(chatId: String) {
        val session = registry.value[chatId]
        val token = payload?.apiToken
        val client = http
        if (token == null || client == null) {
            // Nothing can be read yet: the socket is not open far enough to have a token. The
            // thread stays as the socket left it, and the status says the page was not read.
            session?.history?.value = HistoryStatus.Failed
            return
        }
        session?.history?.value = HistoryStatus.Loading
        val outcome = ThreadPageClient(client, profile.url).fetch(token, SESSION_KEY_PREFIX + chatId)
        if (outcome is ThreadPageOutcome.Failed) {
            session?.history?.value = HistoryStatus.Failed
            return
        }
        val page = (outcome as ThreadPageOutcome.Loaded).page
        val stored = HistoryReplay.fromEvents(page, chatId, deps.clock.nowMs())
        val merged = HistoryReplay.merge(stored = stored, live = chatSet.thread(chatId))
        chatSet = chatSet.copy(threads = chatSet.threads + (chatId to merged))
        publish(chatId, merged)
        session?.history?.value = HistoryStatus.Loaded
    }

    /**
     * Audio goes out as a data URL with a request id, and comes back on that id
     * (`transcription_ws.py`). The request is remembered here so the pump can resolve it.
     */
    internal suspend fun transcribe(
        chatId: String,
        session: ChatSession,
        pcm: ByteArray,
        durationMs: Long,
    ): TranscriptionResult {
        val requestId = "tr-${deps.clock.nowMs()}-${sequence++}"
        val reply = CompletableDeferred<TranscriptionReply>()
        pendingTranscriptions[requestId] = reply
        session.draftBeforeRecording = session.composer.value.draft
        val sent = engine.send(Outbound.TranscribeAudio(requestId = requestId, audio = pcm, durationMs = durationMs))
        if (!sent) {
            pendingTranscriptions.remove(requestId)
            return TranscriptionResult.Rejected(TranscriptionRejection.NotConnected)
        }
        val arrived = withTimeoutOrNull(TRANSCRIPTION_TIMEOUT_MS) { reply.await() }
        pendingTranscriptions.remove(requestId)
        return when (arrived) {
            null -> TranscriptionResult.Failed(TRANSCRIPTION_TIMEOUT)
            is TranscriptionReply.Refused -> TranscriptionResult.Failed(arrived.detail)
            is TranscriptionReply.Text -> {
                session.composer.update { it.copy(draft = session.draftBeforeRecording + arrived.value) }
                TranscriptionResult.Transcribed(arrived.value)
            }
        }
    }

    internal fun threadOf(chatId: String): ThreadState = chatSet.thread(chatId)

    /**
     * The queued prompt goes out when its turn settles. `TurnControl` decides which prompt that
     * is; the send is the same one a typed prompt takes, so a released prompt gets the same
     * optimistic bubble and the same limits instead of a second path that could drift.
     */
    private suspend fun releaseQueued(chatId: String?, turnId: String?) {
        if (chatId == null) return
        val session = registry.value[chatId] ?: return
        if (session.control.queued.none { it.chatId == chatId }) return
        val reduction = TurnControl.onTurnEnd(session.control, chatId, turnId.orEmpty())
        session.control = reduction.state
        session.mirrorQueue()
        val released = reduction.effects.mapNotNull { (it as? Effect.Send)?.frame }
            .filterIsInstance<Outbound.Message>()
            .firstOrNull() ?: return
        sendDraft(chatId, OutboundDraft(text = released.content), fromQueue = true)
    }

    // --- internals ---------------------------------------------------------------------

    /**
     * A recovery decision: `recovery.continue` or `recovery.dismiss` with the chat and
     * the recovery id, answered like any other mutation.
     */
    internal suspend fun recoveryAction(chatId: String, recoveryId: String, action: String): AutomationActionResult {
        val payload = buildJsonObject {
            put("chat_id", JsonPrimitive(chatId))
            put("recovery_id", JsonPrimitive(recoveryId))
        }
        return when (val reply = mutation(action, payload)) {
            WebuiReply.NotConnected -> AutomationActionResult.NotConnected
            WebuiReply.Timeout -> AutomationActionResult.Timeout
            is WebuiReply.Refused -> AutomationActionResult.Refused(reply.status, reply.message)
            is WebuiReply.Ok -> AutomationActionResult.Accepted
        }
    }

    /** One subtask's cancellation, answered like any other mutation (`subagent.cancel`). */
    internal suspend fun subagentCancel(chatId: String, taskId: String): AutomationActionResult {
        val payload = buildJsonObject {
            put("session_key", JsonPrimitive(chatId))
            put("task_id", JsonPrimitive(taskId))
        }
        return when (val reply = mutation("subagent.cancel", payload)) {
            WebuiReply.NotConnected -> AutomationActionResult.NotConnected
            WebuiReply.Timeout -> AutomationActionResult.Timeout
            is WebuiReply.Refused -> AutomationActionResult.Refused(reply.status, reply.message)
            is WebuiReply.Ok -> AutomationActionResult.Accepted
        }
    }

    /**
     * One `webui_request` and its answer. The WebUI replays a pending mutation with
     * the same request id after a reconnect (`nanobot-client.ts:646`); this client does not,
     * so a request that outlives its socket comes back as [AutomationActionResult.Timeout]
     * and the caller decides whether to ask again.
     */
    private suspend fun mutation(action: String, payload: JsonObject): WebuiReply {
        val requestId = "companion-${deps.clock.nowMs()}-${sequence++}"
        val reply = CompletableDeferred<WebuiReply>()
        pendingRequests[requestId] = reply
        if (!engine.send(Outbound.WebuiRequest(requestId = requestId, action = action, payload = payload))) {
            pendingRequests.remove(requestId)
            return WebuiReply.NotConnected
        }
        val arrived = withTimeoutOrNull(MUTATION_TIMEOUT_MS) { reply.await() }
        pendingRequests.remove(requestId)
        // A request the gateway never answered is a timeout, not a refusal it never sent.
        return arrived ?: WebuiReply.Timeout
    }

    /**
     * The drawer read runs beside the pump, not inside it: `/api/sessions` is an HTTP round
     * trip, and a frame handler that awaits one makes `connect()` as slow as the gateway's
     * slowest read, while a pump that awaits one stops delivering frames while it waits.
     */
    private fun refreshDrawerLater() {
        drawerRefresh?.cancel()
        drawerRefresh = scope.launch { refreshSessions() }
    }

    private fun bindPending(chatId: String?) {
        if (chatId.isNullOrBlank()) return
        val waiting = pending.value ?: return
        if (waiting.id.value != null) return
        waiting.id.value = chatId
        registry.update { current -> current + (chatId to (current[chatId] ?: waiting)) }
        pending.value = null
        chatSet = chatSet.copy(threads = chatSet.threads + (chatId to chatSet.thread(chatId)))
        mirrorComposer(waiting, chatId)
    }

    /** The composer mirrors the thread's model and usage: the ring is drawn from here. */
    private fun publish(chatId: String, thread: ThreadState) {
        val session = registry.value[chatId] ?: return
        session.thread.value = thread
        mirrorComposer(session, chatId)
    }

    /**
     * One place where what the thread knows becomes what the row draws. A settings read lands
     * after the frames that used it, so this runs again when the presets do (`republish`), and
     * the ring's denominator is resolved the same way both times.
     */
    private fun mirrorComposer(session: ChatSession, chatId: String?) {
        val model = composerModel(chatId, session.pickedPreset)
        session.composer.update { composer ->
            val thread = session.thread.value
            // A new preset drops the old name and the old window together: they described the
            // preset the chat was on, and keeping half of the pair is the lie this fixes.
            val kept = composer.modelPreset == model.preset
            val name = model.name ?: composer.modelName.takeIf { kept }
            val window = model.window ?: composer.contextWindowTokens.takeIf { kept }
            val usage = thread.usage ?: composer.usage
            composer.copy(
                modelPreset = model.preset,
                modelName = name,
                contextWindowTokens = window,
                usage = usage,
                contextPercent = ContextUsage.percent(usage?.contextTokens, window),
            )
        }
    }

    /**
     * A pick is the client's guess; the chat's session row is the gateway's answer to it. `/model
     * <preset>` writes the session's own metadata and publishes nothing (`loop.py:587
     * set_session_model_preset`), and a settings re-read does not change either. Measured
     * 2026-11-16: `GET /api/settings` still said `agent.model_preset: primary` after a switch to a
     * real `broken` while `GET /api/sessions` answered the chat's row with `model_preset: broken`.
     * So a row either confirms the pick and takes over from it, or names something else and the
     * pick goes: both are the gateway refusing the client the last word, which is the point.
     *
     * Only a pick whose own turn has ended is settled: before that the gateway may not have
     * committed anything, and a read that answers early (a title arriving mid-switch) is not an
     * answer about the pick. A chat with no row says nothing either, so a pick with nothing against
     * it stands.
     */
    private fun settlePicks(rows: List<SessionRow>) {
        registry.value.forEach { (chatId, session) ->
            if (session.pickedPreset == null || !session.pickAnswered) return@forEach
            if (rows.none { row -> row.chatId == chatId }) return@forEach
            session.pickedPreset = null
            session.pickAnswered = false
        }
    }

    /**
     * What the composer's chip should name, in the WebUI's order (`ThreadShell.tsx`
     * `activeModelPreset`): the chat's own pick, the preset this chat's own turn reported, then
     * the one the gateway stored for the chat, then the one the gateway is on. A chat that
     * overrides the global preset must not be labelled with the global one, which is why the
     * chat's own answer leads.
     *
     * Whichever wins names its own model and its own window; [modelName] and
     * [ContextUsage.window] both answer for that preset and no other.
     */
    private fun composerModel(chatId: String?, picked: String? = null): ModelOnChip {
        val thread = chatId?.let { chatSet.thread(it) }
        val row = chatId?.let { id -> _sessions.value.rows.firstOrNull { it.chatId == id } }
        val settings = _settings.value
        val preset = picked?.takeIf(String::isNotBlank)
            ?: thread?.modelPreset?.takeIf(String::isNotBlank)
            ?: row?.modelPreset?.takeIf(String::isNotBlank)
            // `agent.model_preset` is the configured one; `active` is the gateway's own mark
            // for the same question, and one of the two is present on every real payload.
            ?: settings.modelPreset?.takeIf(String::isNotBlank)
            ?: settings.modelPresets.firstOrNull { it.active }?.name
        return ModelOnChip(
            preset = preset,
            name = modelName(thread, settings, preset),
            window = ContextUsage.window(thread ?: ThreadState(chatId = chatId.orEmpty()), settings, preset),
        )
    }

    /**
     * The model [preset] runs. A frame's `model_name` arrived with the preset that frame named
     * (`turn_model_updated`), so it answers for that preset and no other; the gateway's configured
     * `agent.model` is the name of the preset it configured, and nothing else's: the owner's chip
     * said `GLM` (the configured `primary`) over `Vision and OCR`'s window.
     */
    private fun modelName(thread: ThreadState?, settings: SettingsState, preset: String?): String? {
        val reported = thread?.modelName?.takeIf(String::isNotBlank)?.takeIf {
            preset == null || thread.modelPreset == null || thread.modelPreset == preset
        }
        val entry = settings.modelPresets.firstOrNull { it.name == preset }?.model?.takeIf(String::isNotBlank)
        val configured = settings.modelName?.takeIf(String::isNotBlank)?.takeIf {
            preset == null ||
                preset == settings.modelPreset ||
                settings.modelPresets.any { row -> row.active && row.name == preset }
        }
        return reported ?: entry ?: configured
    }

    /** A settings or drawer read can answer for a chat that has had no turn yet. */
    private fun republishComposerModels() {
        registry.value.forEach { (chatId, session) -> mirrorComposer(session, chatId) }
        pending.value?.let { mirrorComposer(it, it.id.value) }
    }

    private fun persist(chatId: String) {
        val session = registry.value[chatId] ?: return
        val name = chatId.removePrefix("websocket:").replace(NOT_A_FILE_CHAR, "-")
        deps.files.write("thread-$name.json", ThreadCache.encode(session.thread.value))
    }

    private companion object {
        val NOT_A_FILE_CHAR: Regex = Regex("[^A-Za-z0-9._-]")

        /** A chat id is a session key's own uuid: the gateway stores sessions as `websocket:<id>`. */
        const val SESSION_KEY_PREFIX: String = "websocket:"

        /** The gateway's own word for a refused scope; read from inbound_commands.py. */
        const val SCOPE_REJECTED: String = "workspace_scope_rejected"

        /** The gateway answers within seconds or not at all; a minute is generous. */
        /** `API_MUTATION_TIMEOUT_MS` in the WebUI; past it the gateway is not going to answer. */
        const val MUTATION_TIMEOUT_MS: Long = 20_000
        const val HTTP_400: Int = 400
        const val HTTP_500: Int = 500
        const val TRANSCRIPTION_TIMEOUT_MS: Long = 60_000
        const val TRANSCRIPTION_TIMEOUT: String = "transcription_timeout"
    }
}
