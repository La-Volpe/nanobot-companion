package de.arjmandi.nanobot.companion.facade

import de.arjmandi.nanobot.companion.wire.ModelPreset
import de.arjmandi.nanobot.companion.wire.SettingsPayloads
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The model sheet's list. The owner's decisions: the built-in preset "shouldn't be visible
 * at all", and "Manage presets" is meaningless on a phone. The flag is real: this is
 * `/api/settings` from their gateway on 2026-11-15, cut to the fields that matter.
 */
class ModelPresetsTest {

    private val payload = """
        {"agent":{"model":"glm-5.3-flash:cloud","provider":"ollama","model_preset":"primary",
                  "context_window_tokens":1000000},
         "model_presets":[
           {"name":"default","model":"anthropic/claude-opus-4-5","provider":"auto",
            "context_window_tokens":200000,"active":false,"is_default":true},
           {"name":"primary","model":"glm-5.3-flash:cloud","provider":"ollama",
            "context_window_tokens":1000000,"active":true,"is_default":false},
           {"name":"Vision and OCR","model":"gemma4:31b-cloud","provider":"ollama",
            "context_window_tokens":256000,"active":false,"is_default":false}]}
    """.trimIndent()

    @Test
    fun theBuiltInPresetIsNotOffered() {
        val settings = SettingsPayloads.settings(payload)

        val offered = ModelPresets.offered(settings)

        assertEquals(listOf("primary", "Vision and OCR"), offered.map { it.name }, "the gateway's order, minus its built-in")
        assertEquals(3, settings.modelPresets.size, "the read keeps every preset; only the sheet's list is narrowed")
    }

    @Test
    fun aGatewayWithNoPresetsOffersNone() {
        assertEquals(emptyList(), ModelPresets.offered(SettingsPayloads.settings("{}")))
        assertEquals(
            listOf("only"),
            ModelPresets.offered(
                de.arjmandi.nanobot.companion.wire.SettingsState(
                    modelPresets = listOf(ModelPreset(name = "only", model = "m", provider = null, contextWindowTokens = 1_000)),
                ),
            ).map { it.name },
            "a list with no built-in mark is offered whole",
        )
    }

    /**
     * The tick follows the chat, not the gateway's configuration. The owner's chat ran
     * `default` while the sheet kept ticking `primary`, so a tap looked like nothing happening.
     */
    @Test
    fun theTickFollowsTheChatsOwnPreset() {
        val settings = SettingsPayloads.settings(payload)

        val offered = ModelPresets.offered(settings, chosen = "Vision and OCR")

        assertEquals(listOf("Vision and OCR"), offered.filter { it.active }.map { it.name }, "the chat is on this one")
        assertEquals(
            listOf("primary"),
            ModelPresets.offered(settings).filter { it.active }.map { it.name },
            "and with no chat preset the gateway's own answer stands",
        )
    }
}
