package de.arjmandi.nanobot.companion.facade

/**
 * The model in use (T45), as Settings → Overview names it.
 *
 * The owner's rule: every name, size and ring on screen describes the model answering in the open
 * chat, never the gateway's configured default. So this is built from the chat's composer — the
 * value the chip prints and the ring divides by (`ContextUsage`, T37) — and never from `agent.*`
 * in `/api/settings`. That departs from the WebUI deliberately: its Overview row prints
 * `settings.agent.model` (`webui/src/components/settings/overview/OverviewSettings.tsx:78`),
 * because a browser page has no chat to ask; the phone has the chat in front of it, and printing a
 * configuration beside a ring that describes a different model is how a screen lies. A caller with
 * no `ModelInUse` at all has no chat open, and says that instead.
 */
public data class ModelInUse(
    public val modelName: String?,
    public val preset: String?,
    /** The denominator the ring uses, resolved by `ContextUsage.window` (T37). */
    public val windowTokens: Int?,
) {

    /** The row's second line: the preset this chat is on, when the gateway has named one. */
    public val presetLabel: String?
        get() = preset?.takeIf(String::isNotBlank)?.let { "Preset $it" }

    /** `1M`, `200K`, `32.8K`: the chip's own token copy (T36); the row adds the unit. */
    public val windowLabel: String?
        get() = windowTokens?.let(TokenCount::modelWindow)
}
