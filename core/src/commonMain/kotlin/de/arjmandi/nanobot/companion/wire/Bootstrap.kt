package de.arjmandi.nanobot.companion.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The protocol this client speaks; the gateway states a range and this is the one we have. */
internal const val CLIENT_WEBUI_PROTOCOL: Int = 1

/**
 * `GET /webui/bootstrap`: the single-use socket token, the limits the
 * composer validates against, the protocol contract, and the host's action flags.
 */
internal data class BootstrapPayload(
    val token: String,
    val wsUrl: String,
    val wsPath: String,
    val expiresInSeconds: Long,
    val apiToken: String?,
    val modelName: String?,
    val limits: Limits,
    val contract: WebuiContract,
    val hostActions: HostActions,
    val gatewayId: String?,
)

/** The protocol range and named capabilities from `terminal.webui`. */
internal data class WebuiContract(
    val version: String?,
    val minProtocol: Int,
    val maxProtocol: Int,
    val capabilities: List<String>,
) {
    internal fun supports(feature: Feature): Boolean = capabilities.contains(feature.capability) && problems(Feature.Core, checkProtocol = false) == null

    internal fun problems(feature: Feature, checkProtocol: Boolean = true): ProtocolError? {
        if (checkProtocol && minProtocol > CLIENT_WEBUI_PROTOCOL) {
            return ProtocolError.ProtocolTooNew(hostMinProtocol = minProtocol, clientProtocol = CLIENT_WEBUI_PROTOCOL)
        }
        if (!capabilities.contains(feature.capability)) {
            return ProtocolError.MissingCapability(feature = feature.id, capability = feature.capability)
        }
        return null
    }
}

/**
 * `runtime_capabilities` is a dict of host action flags, not protocol capabilities.
 * Settings uses them to decide which buttons exist; nothing in the wire layer may treat
 * them as a version gate.
 */
internal data class HostActions(
    val canRestartEngine: Boolean,
    val canOpenLogs: Boolean,
    val canExportDiagnostics: Boolean,
)

/** What the composer validates locally before it sends; defaults mirror ingress_policy.py. */
public data class Limits(
    public val maxFrameBytes: Long = 32L * 1024 * 1024,
    public val envelopeReserveBytes: Long = 64L * 1024,
    public val maxTextBytes: Long = 64L * 1024,
    public val attachments: AttachmentLimits = AttachmentLimits(),
)

public data class AttachmentLimits(
    public val maxCount: Int = 4,
    public val maxFileBytes: Long = 6L * 1024 * 1024,
    public val maxTotalBytes: Long = 24L * 1024 * 1024,
)

/** A feature the client can drop when the host does not advertise it. */
internal enum class Feature(val id: String, val capability: String) {
    Core(id = "core", capability = "webui.core.v1"),
    Subagents(id = "subagents", capability = "webui.subagents.v1"),
    SubagentEvents(id = "subagent-events", capability = "webui.subagents.events.v1"),
    AutomationChat(id = "automation-chat", capability = "webui.automation-chat.v1"),
    BinaryAttachments(id = "binary-attachments", capability = "webui.attachments.binary.v1"),
    PromptNavigation(id = "prompt-navigation", capability = "webui.prompt-navigation.v1"),
}

internal sealed interface ProtocolError {
    data class MissingField(val field: String) : ProtocolError
    data class ProtocolTooNew(val hostMinProtocol: Int, val clientProtocol: Int) : ProtocolError
    data class MissingCapability(val feature: String, val capability: String) : ProtocolError
}

internal sealed interface BootstrapResult {
    data class Parsed(val payload: BootstrapPayload) : BootstrapResult
    data class Failed(val error: ProtocolError) : BootstrapResult
}

internal object BootstrapParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): BootstrapResult {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return BootstrapResult.Failed(ProtocolError.MissingField("body"))

        val token = root.text("token") ?: return BootstrapResult.Failed(ProtocolError.MissingField("token"))
        val wsUrl = root.text("ws_url") ?: return BootstrapResult.Failed(ProtocolError.MissingField("ws_url"))
        val contract = contractOf(root.child("terminal")?.child("webui"))
            ?: return BootstrapResult.Failed(ProtocolError.MissingField("terminal.webui"))

        return BootstrapResult.Parsed(
            BootstrapPayload(
                token = token,
                wsUrl = wsUrl,
                wsPath = root.text("ws_path") ?: "/",
                expiresInSeconds = root.long("expires_in") ?: 0,
                apiToken = root.text("api_token"),
                modelName = root.text("model_name"),
                limits = limitsOf(root.child("limits")),
                contract = contract,
                hostActions = hostActionsOf(root.child("runtime_capabilities")),
                gatewayId = root.child("terminal")?.text("gatewayId"),
            ),
        )
    }

    private fun contractOf(webui: JsonObject?): WebuiContract? {
        if (webui == null) return null
        val min = webui.integer("min_protocol") ?: return null
        val max = webui.integer("max_protocol") ?: return null
        return WebuiContract(
            version = webui.text("version"),
            minProtocol = min,
            maxProtocol = max,
            capabilities = webui.texts("capabilities"),
        )
    }

    private fun limitsOf(limits: JsonObject?): Limits {
        val transport = limits?.child("transport")
        val message = limits?.child("message")
        val attachments = limits?.child("attachments")
        return Limits(
            maxFrameBytes = transport?.long("max_frame_bytes") ?: Limits().maxFrameBytes,
            envelopeReserveBytes = transport?.long("envelope_reserve_bytes") ?: Limits().envelopeReserveBytes,
            maxTextBytes = message?.long("max_text_bytes") ?: Limits().maxTextBytes,
            attachments = AttachmentLimits(
                maxCount = attachments?.integer("max_count") ?: AttachmentLimits().maxCount,
                maxFileBytes = attachments?.long("max_file_bytes") ?: AttachmentLimits().maxFileBytes,
                maxTotalBytes = attachments?.long("max_total_bytes") ?: AttachmentLimits().maxTotalBytes,
            ),
        )
    }

    /**
     * `runtime_capabilities`: what the *host* can be asked to do (AGENTS.md draws the same line:
     * not `terminal.webui` capabilities). `canRestartEngine` is parsed and deliberately unread by
     * anything else: the owner's Overview scope is the usage totals and the version row, not a
     * restart control, and a restart button nobody asked for is a way to bounce a gateway from a
     * phone by accident. It stays parsed because `BootstrapParserTest` pins the host's own flags,
     * and a control added later must read this rather than assume it.
     */
    private fun hostActionsOf(flags: JsonObject?): HostActions = HostActions(
        canRestartEngine = flags?.flag("can_restart_engine") ?: false,
        canOpenLogs = flags?.flag("can_open_logs") ?: false,
        canExportDiagnostics = flags?.flag("can_export_diagnostics") ?: false,
    )
}
