package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.transport.AttachmentRejection
import de.arjmandi.nanobot.companion.transport.CoreError
import de.arjmandi.nanobot.companion.wire.Limits
import de.arjmandi.nanobot.companion.wire.Usage

/** Where the gateway is and the secret that opens it (plan.md §2). */
public data class GatewayProfile(public val url: String, public val secret: String)

/**
 * The connection dot and the banner (plan.md §2, C7.4). Four states, because a user needs to
 * tell "not started" from "starting" from "lost it" from "this will not work".
 */
public sealed interface ConnectionState {
    public data object Idle : ConnectionState
    public data object Connecting : ConnectionState
    public data object Open : ConnectionState

    /** The socket went away and nothing is retrying yet. */
    public data object Offline : ConnectionState

    public data class Failed(
        public val failure: ConnectionFailure,
        public val retryable: Boolean = failure.retryable,
        /** The one thing the sentence needs to be specific: a capability name, a version pair. */
        public val detail: String? = null,
    ) : ConnectionState
}

/**
 * The eight entries of the transport taxonomy as the UI sees them (plan.md §6, C7.4): one copy
 * key each, and whether "try again" is an honest offer. The platform owns the sentence.
 */
public enum class ConnectionFailure(public val copyKey: String, public val retryable: Boolean) {
    AuthFailed("connection.authFailed", false),
    TlsRejected("connection.tlsRejected", false),
    Unreachable("connection.unreachable", true),
    Timeout("connection.timeout", true),
    ProtocolTooNew("connection.protocolTooNew", false),
    MissingCapability("connection.missingCapability", false),
    MissingField("connection.missingField", false),
    ServerError("connection.serverError", true),
}

public sealed interface ConnectResult {
    public data class Connected(public val modelName: String?) : ConnectResult

    /**
     * A refused connect, carrying both the transport's word ([error]) and the state a screen
     * renders ([failure], [detail]). The mapping from one to the other is the core's and stays
     * internal: a platform that re-derived it would be a second taxonomy to keep in step (C7.4).
     */
    public data class Failed(
        public val error: CoreError,
        public val failure: ConnectionFailure,
        public val detail: String? = null,
    ) : ConnectResult
}

/** What the composer hands the facade (plan.md C6.1). Media travels as references (C6.6). */
public data class OutboundDraft(
    public val text: String,
    public val quote: String? = null,
    public val media: List<AttachmentRef> = emptyList(),
)

/** An uploaded file as the message names it: the gateway's reference, never the bytes. */
public data class AttachmentRef(public val reference: String, public val name: String? = null)

/** One picked file, before it is uploaded. */
public class PickedFile(
    public val name: String,
    public val mimeType: String,
    public val bytes: ByteArray,
)

/** What an upload produced (plan.md C6.6): a reference to put on a message, or why not. */
public sealed interface UploadResult {
    public data class Uploaded(public val reference: String) : UploadResult
    public data class Rejected(public val reason: AttachmentRejection) : UploadResult
    public data class Failed(public val error: CoreError) : UploadResult
}

public sealed interface SendReceipt {
    public data class Accepted(public val turnId: String) : SendReceipt
    public data class Rejected(public val reason: SendRejection) : SendReceipt
}

/**
 * Local refusals, typed so the platform holds the sentence (plan.md §10, C6.1). A send the
 * gateway refuses arrives as a turn that fails, not as one of these.
 */
public enum class SendRejection(public val copyKey: String) {
    NotConnected("send.notConnected"),
    EmptyDraft("send.empty"),

    /** The frame the gateway would refuse is refused here, so the draft is not lost to a 400. */
    FrameTooLarge("send.frameTooLarge"),
}

/** What a recording produced (plan.md C6.5): text, a local refusal, or the gateway's reason. */
public sealed interface TranscriptionResult {
    public data class Transcribed(public val text: String) : TranscriptionResult
    public data class Rejected(public val reason: TranscriptionRejection) : TranscriptionResult

    /** The gateway's `transcription_error.detail`, or a local timeout. */
    public data class Failed(public val detail: String) : TranscriptionResult
}

/**
 * The two bounds are the gateway's own (`transcription_ws.py`, 650 ms and 120 s), so a tap
 * that never became audio costs no round trip.
 */
public enum class TranscriptionRejection(public val copyKey: String) {
    TooShort("transcribe.tooShort"),
    TooLong("transcribe.tooLong"),
    NotConnected("transcribe.notConnected"),
}

/** `set_workspace_scope` takes exactly these two words (plan.md §0, C6.3). */
public enum class WorkspaceScope(public val wire: String) {
    Default("default"),
    Full("full"),
}

/**
 * Everything the composer row shows (plan.md §2). `usage` is the ring and
 * `contextWindowTokens` is the ring's denominator; both come from `turn_model_updated` and
 * `turn_end` (D15.4), never from a separate call.
 */
public data class ComposerState(
    public val modelPreset: String? = null,
    public val modelName: String? = null,
    public val contextWindowTokens: Int? = null,
    public val usage: Usage? = null,
    public val accessMode: WorkspaceScope = WorkspaceScope.Default,
    /** What is typed but not sent; a refused send leaves it here (C6.1). */
    public val draft: String = "",
    /** `ready.limits`: the composer validates against the gateway's own numbers (C6.1, C6.6). */
    public val limits: Limits? = null,
)

/**
 * The five things an automation row can do (plan.md C7.2). There is no sixth: an automation
 * is created by asking the agent, and the gateway has no create endpoint to call — so a
 * "new automation" button belongs in the composer, not here (design README).
 */
public enum class AutomationActionKind(public val wire: String) {
    Enable("automation.enable"),
    Disable("automation.disable"),
    Run("automation.run"),
    Delete("automation.delete"),
    ChangeChat("automation.change_chat"),
}

/**
 * Re-binding an automation to another chat. The revision is the gateway's own optimistic
 * guard (`binding_revision`): a stale one refuses with `automation_chat_conflict` instead of
 * silently moving a job two clients both edited.
 */
public data class AutomationChatChange(
    public val targetId: String,
    public val revision: String,
    public val message: String,
)

/** What one action did, typed per action so the platform can pick the sentence (C7.2). */
public sealed interface AutomationActionResult {
    public data class Done(public val jobs: List<de.arjmandi.nanobot.companion.wire.AutomationJob>) : AutomationActionResult

    /** The gateway's own status and words: 403 protected, 409 busy, 404 gone, 400 invalid. */
    public data class Refused(public val status: Int, public val message: String?) : AutomationActionResult

    /** The mutation was accepted and answers with no list: a recovery decision, not a job page. */
    public data object Accepted : AutomationActionResult

    public data object NotConnected : AutomationActionResult
    public data object Timeout : AutomationActionResult
}
