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
import de.arjmandi.nanobot.companion.projection.ThreadCache
import de.arjmandi.nanobot.companion.projection.ThreadReducer
import de.arjmandi.nanobot.companion.projection.ThreadState
import de.arjmandi.nanobot.companion.transport.AutomationsClient
import de.arjmandi.nanobot.companion.transport.AutomationsOutcome
import de.arjmandi.nanobot.companion.transport.BootstrapClient
import de.arjmandi.nanobot.companion.transport.CandidateFile
import de.arjmandi.nanobot.companion.transport.ConnectionAttempt
import de.arjmandi.nanobot.companion.transport.CoreError
import de.arjmandi.nanobot.companion.transport.PaletteClient
import de.arjmandi.nanobot.companion.transport.PaletteOutcome
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
import de.arjmandi.nanobot.companion.wire.TranscriptionErrorFrame
import de.arjmandi.nanobot.companion.wire.TranscriptionResultFrame
import de.arjmandi.nanobot.companion.wire.SessionUpdatedFrame
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where the app keeps the gateway secret in the platform secure store (plan.md D5). */
public const val GATEWAY_SECRET_KEY: String = "gateway.secret"

/**
 * The whole public surface (plan.md C8.1). Everything below it returns effects as data; this
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

    private val engine: TurnEngine = TurnEngine(scope, attempt, host)

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    private val _sessions = MutableStateFlow(SessionListState())

    /** What the two composer palettes are drawn from (C6.4); commands and apps arrive on demand. */
    private val _palette = MutableStateFlow(PaletteCatalog())

    /** Settings → Overview (C7.1); read when the screen opens, not at connect. */
    private val _settings = MutableStateFlow(SettingsState())

    /** The automations page (C7.2); the list is a read, each action is a socket mutation. */
    private val _automations = MutableStateFlow(AutomationsState())

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

    /** The chat the last `ready` minted, so a handle opened after connect can still bind (C5.3). */
    private var greetingChatId: String? = null
    private var sequence: Int = 0

    /** The in-flight drawer read; a second `session_updated` replaces it instead of queueing. */
    private var drawerRefresh: Job? = null

    public val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    public val sessions: StateFlow<SessionListState> = _sessions.asStateFlow()
    public val palette: StateFlow<PaletteCatalog> = _palette.asStateFlow()
    public val settings: StateFlow<SettingsState> = _settings.asStateFlow()
    public val automations: StateFlow<AutomationsState> = _automations.asStateFlow()

    /** Jobs the core started and has not joined: a leak check reads this (plan.md §3.7). */
    public fun activeChildJobs(): Int = engine.activeChildJobs()

    /**
     * Bootstrap, handshake, `ready` (plan.md C8.1). Every call mints a fresh single-use
     * token, so this is also the reconnect path — there is no separate "resume" (C3.4).
     */
    public suspend fun connect(): ConnectResult {
        _connection.value = ConnectionState.Connecting
        return when (val opened = engine.connect(profile.secret)) {
            is EngineOpen.Failed -> {
                _connection.value = opened.error.connectionFailure()
                ConnectResult.Failed(opened.error)
            }

            is EngineOpen.Ready -> {
                _connection.value = ConnectionState.Open
                ConnectResult.Connected(opened.payload.modelName)
            }
        }
    }

    /** Close the socket. [forget] also drops the stored key (Settings → Gateway). */
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
        if (chatId == null) {
            // Before the greeting there is no chat to name; after it, the gateway has already
            // told us which chat a "new chat" became, so a handle opened late still binds.
            val greeted = greetingChatId
            val created = ChatSession(id = MutableStateFlow(greeted))
            pending.update { current -> current ?: created }
            val bound = pending.value ?: created
            if (greeted != null) {
                // The greeting already named the chat, so this handle is registered straight
                // away and the frames that arrive find it.
                bound.id.value = greeted
                registry.update { current -> current + (greeted to (current[greeted] ?: bound)) }
                pending.value = null
            }
            return ChatHandle(this, bound)
        }
        val created = ChatSession(id = MutableStateFlow(chatId))
        registry.update { current -> current + (chatId to (current[chatId] ?: created)) }
        return ChatHandle(this, registry.value.getValue(chatId))
    }

    /** Refresh the drawer from `GET /api/sessions` (plan.md C5.1, D15.6). */
    public suspend fun refreshSessions() {
        val token = payload?.apiToken
        val client = http
        if (token == null || client == null) {
            _sessions.value = SessionListState(rows = _sessions.value.rows, loaded = true)
            return
        }
        val outcome = SessionListClient(client, profile.url).fetch(token)
        val rows = (outcome as? SessionListOutcome.Loaded)?.rows ?: _sessions.value.rows
        // A refresh that fails keeps the rows it had: an empty drawer is worse than a stale one.
        _sessions.value = SessionListState(rows = rows, loaded = true)
        // The mention sheet names chats by the same rows, so it is refreshed with them (C6.4).
        _palette.value = _palette.value.copy(sessions = PaletteCatalog.sessionsIn(rows))
    }

    /** The automations page (`GET /api/webui/automations`, C7.2). */
    public suspend fun refreshAutomations(): CoreError? {
        val token = payload?.apiToken ?: return CoreError.AuthFailed
        val client = http ?: return CoreError.Unreachable
        return when (val outcome = AutomationsClient(client, profile.url).fetch(token)) {
            is AutomationsOutcome.Loaded -> {
                _automations.value = AutomationsState(jobs = outcome.jobs, loaded = true)
                null
            }
            is AutomationsOutcome.Failed -> outcome.error
        }
    }

    /**
     * One automation action, sent as a socket mutation (C7.2). A successful action answers
     * with the whole list, so the page never has to re-read to show the switch flipped.
     */
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
     * Version, environment and usage (`GET /api/settings`, `/api/settings/usage`, C7.1). A
     * refused read leaves the screen empty rather than showing a stale number as if it were
     * current, and the error comes back so the screen can offer a retry.
     */
    public suspend fun refreshSettings(): CoreError? {
        val token = payload?.apiToken ?: return CoreError.AuthFailed
        val client = http ?: return CoreError.Unreachable
        return when (val outcome = SettingsClient(client, profile.url).fetch(token)) {
            is SettingsOutcome.Loaded -> {
                _settings.value = outcome.state
                null
            }
            is SettingsOutcome.Failed -> outcome.error
        }
    }

    /**
     * The slash palette and the CLI app mentions (`GET /api/commands`,
     * `GET /api/settings/cli-apps`, C6.4). The WebUI loads these when the sheet opens rather
     * than at connect, and returns the error so the sheet can say "try again" instead of
     * showing a half list.
     */
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
        // The limits the composer validates against are the gateway's, not ours (C6.1).
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
                // its id here rather than inventing one (C5.3).
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

        val routed = ChatRouter.reduce(chatSet, frame, deps.clock.nowMs())
        chatSet = routed.set
        routed.set.threads.forEach { (chatId, thread) -> publish(chatId, thread) }

        // A refused scope switch is the only way the gateway answers `set_workspace_scope`
        // (inbound_commands.py:283), so the chip goes back rather than staying wrong (C6.3).
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
     * The bytes go to `POST {ready.upload.path}` with the capability the greeting handed out
     * (C3.6, D15.5); the reference stays with the session until a message carries it (C6.6).
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

    internal suspend fun sendDraft(chatId: String, draft: OutboundDraft): SendReceipt {
        val session = registry.value[chatId]
        val text = draft.text.trim()
        // The draft is the composer's memory: it goes in before the attempt and only leaves
        // when the socket took the message (C6.1).
        session?.composer?.update { it.copy(draft = draft.text) }

        if (text.isEmpty()) return SendReceipt.Rejected(SendRejection.EmptyDraft)
        val limits = session?.composer?.value?.limits ?: payload?.limits
        if (limits != null && text.encodeToByteArray().size > limits.maxTextBytes) {
            return SendReceipt.Rejected(SendRejection.FrameTooLarge)
        }

        val turnId = nextTurnId()
        val quote = draft.quote?.let(QuotedContext::of)
        // Attachments are references: whatever was uploaded and not yet sent rides this message.
        val media = draft.media.map { UploadReference(reference = it.reference, name = it.name) } +
            session?.media.orEmpty()
        val message = Outbound.Message(
            chatId = chatId,
            turnId = turnId,
            content = text,
            media = media,
            quotedContext = quote,
        )
        // A chat the socket has not attached gets its attach first, and the message waits
        // behind it (C5.4): sending first would race the attach and lose the turn.
        val routed = ChatRouter.send(chatSet, chatId, message)
        chatSet = routed.set
        val outcomes = routed.effects.mapNotNull { (it as? Effect.Send)?.frame }.map { engine.send(it) }
        if (!outcomes.all { it }) return SendReceipt.Rejected(SendRejection.NotConnected)

        // Only a message the socket took gets a bubble; the thread the frame will land in is
        // the one the router already holds.
        val thread = ThreadReducer.addLocalPrompt(chatSet.thread(chatId), turnId, text, quote?.text, deps.clock.nowMs())
        chatSet = chatSet.copy(threads = chatSet.threads + (chatId to thread))
        session?.thread?.value = thread
        session?.composer?.update { it.copy(draft = "") }
        session?.media?.clear()
        return SendReceipt.Accepted(turnId)
    }

    internal suspend fun write(frame: Outbound): Boolean = engine.send(frame)

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

    // --- internals ---------------------------------------------------------------------

    /**
     * A recovery decision (C7.3): `recovery.continue` or `recovery.dismiss` with the chat and
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

    /**
     * One `webui_request` and its answer (C7.2). The WebUI replays a pending mutation with
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
     * slowest read — and a pump that awaits one stops delivering frames while it waits.
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
    }

    /** The composer mirrors the thread's model and usage: the ring is drawn from here (C6.7). */
    private fun publish(chatId: String, thread: ThreadState) {
        val session = registry.value[chatId] ?: return
        session.thread.value = thread
        session.composer.update { composer ->
            composer.copy(
                modelPreset = thread.modelPreset ?: composer.modelPreset,
                modelName = thread.modelName ?: composer.modelName,
                contextWindowTokens = thread.contextWindowTokens ?: composer.contextWindowTokens,
                usage = thread.usage ?: composer.usage,
            )
        }
    }

    private fun persist(chatId: String) {
        val session = registry.value[chatId] ?: return
        val name = chatId.removePrefix("websocket:").replace(NOT_A_FILE_CHAR, "-")
        deps.files.write("thread-$name.json", ThreadCache.encode(session.thread.value))
    }

    private companion object {
        val NOT_A_FILE_CHAR: Regex = Regex("[^A-Za-z0-9._-]")

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
