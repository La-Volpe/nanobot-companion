package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.transport.AttachmentRejection
import de.arjmandi.nanobot.companion.transport.CoreError
import de.arjmandi.nanobot.companion.wire.Limits
import de.arjmandi.nanobot.companion.wire.ModelPreset
import de.arjmandi.nanobot.companion.wire.SettingsState
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

/**
 * Whether a chat's stored turns have been read (D46). The socket's `attach` carries no history, so
 * a thread with no rows is two different things: a conversation nobody has sent to, and one whose
 * page has not arrived. A screen that draws the first while the second is true says "nothing here"
 * about a conversation it has not looked at yet — which is what this state exists to prevent.
 */
public enum class HistoryStatus {
    /** The page read is not finished: the thread knows only what the socket showed it. */
    Loading,

    /** The page arrived. An empty thread is now a fact about the gateway. */
    Loaded,

    /** The read was refused or never left. The thread is not the whole conversation and says so. */
    Failed,
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

/**
 * What a preset pick did (T39). `Sent` is not `Applied`: `/model <preset>` is a turn, and the
 * gateway's answer — its "Switched model preset" message, or its refusal — is that turn's text.
 * What this covers is the half the client owns: a pick with nowhere to go says so.
 */
public sealed interface PresetResult {
    public data object Sent : PresetResult
    public data class Rejected(public val reason: PresetRejection) : PresetResult
}

public enum class PresetRejection(public val copyKey: String) {
    /** The chat the gateway minted has no id yet, so `/model` has nowhere to go (C5.3). */
    NotConnected("preset.notConnected"),

    /** The socket refused the frame: the chip must not keep naming a model the chat is not on. */
    NotSent("preset.notSent"),
}

/**
 * The presets a phone offers in the model sheet (T40). The gateway's built-in preset is not a
 * choice — it is what runs when nothing else is configured — and the flag is the payload's own
 * (`/api/settings` `model_presets[].is_default`), not a name this client hard-codes.
 *
 * [chosen] is the preset this chat is on when the client knows one, and it becomes the tick:
 * the gateway's `active` flag is about its configuration, and a chat can be on another preset
 * entirely — the owner's chat ran `default` while the sheet ticked `primary` (T37, T39).
 */
public object ModelPresets {
    public fun offered(settings: SettingsState, chosen: String? = null): List<ModelPreset> =
        offered(settings, chosen, settings.modelPresets)

    /**
     * The same narrowing over a list the caller already holds: a debug launch seeds its presets
     * instead of reading them, and a second copy of "which one is a choice" is how the two
     * platforms would drift apart again.
     */
    public fun offered(settings: SettingsState, chosen: String?, presets: List<ModelPreset>): List<ModelPreset> =
        presets
            .filterNot { it.isDefault }
            .map { preset -> if (chosen.isNullOrBlank()) preset else preset.copy(active = preset.name == chosen) }
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
 * `contextWindowTokens` is the ring's denominator; both come from the frames that carry the
 * turn's own usage (D15.4), with the preset's configured window as the fallback and never an
 * invented number (`ContextUsage`, T37). [contextPercent] is computed here so the two phones
 * cannot round the same two numbers differently.
 */
public data class ComposerState(
    public val modelPreset: String? = null,
    public val modelName: String? = null,
    public val contextWindowTokens: Int? = null,
    public val usage: Usage? = null,
    /** `Math.min(100, Math.round(used / window * 100))`, null before there is a window. */
    public val contextPercent: Int? = null,
    public val accessMode: WorkspaceScope = WorkspaceScope.Default,
    /** What is typed but not sent; a refused send leaves it here (C6.1). */
    public val draft: String = "",
    /** `ready.limits`: the composer validates against the gateway's own numbers (C6.1, C6.6). */
    public val limits: Limits? = null,
    /**
     * The one prompt waiting for the running turn to end (C5.5, I5). Typing while a turn runs
     * makes the primary button Send again, and pressing it queues rather than stopping; the
     * chip above the field shows this and the turn's `turn_end` releases it.
     */
    public val queued: String? = null,
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
