package de.arjmandi.nanobot.companion.composer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

/**
 * The composer's rules (plan.md A5, the Android twin of iOS's `ComposerViewModelTests`): which
 * button the primary action is, what a queued prompt does, the copy confirmation, the usage ring,
 * the model sheet, the access chip, the one overflow, and the two palettes. Every branch the
 * composable would otherwise hold lives here.
 */
class ComposerViewModelTest {

    private class FakeDriver : ComposerDriving {
        val sent = mutableListOf<String>()
        val enqueued = mutableListOf<String>()
        var stops = 0
        var sendNowCount = 0
        var discarded = 0
        val presets = mutableListOf<String>()
        val accesses = mutableListOf<WorkspaceAccess>()
        var palettes = 0
        var paletteSheet: PaletteSheet? = null

        override suspend fun sendPrompt(text: String): Boolean {
            sent += text
            return true
        }

        override fun enqueue(text: String) {
            enqueued += text
        }

        override suspend fun stop() {
            stops += 1
        }

        override suspend fun sendQueuedNow() {
            sendNowCount += 1
        }

        override fun discardQueued() {
            discarded += 1
        }

        override suspend fun setModelPreset(preset: String) {
            presets += preset
        }

        override suspend fun setWorkspaceScope(access: WorkspaceAccess) {
            accesses += access
        }

        override suspend fun loadPalette(text: String, cursor: Int): PaletteSheet? {
            palettes += 1
            return paletteSheet
        }
    }

    /** A sleep the test owns, so "confirms for 1.5 s" is asserted instead of slept through. */
    private class Gate {
        val durations = mutableListOf<Long>()
        private var waiter: CompletableDeferred<Unit>? = null

        suspend fun sleep(ms: Long) {
            durations += ms
            val pending = CompletableDeferred<Unit>()
            waiter = pending
            pending.await()
        }

        fun finish() {
            waiter?.complete(Unit)
            waiter = null
        }
    }

    private fun model(
        driver: FakeDriver? = null,
        gate: Gate? = null,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
    ): ComposerViewModel = ComposerViewModel(driver ?: FakeDriver(), sleep = (gate ?: Gate())::sleep, scope = scope)

    private fun preset(name: String, active: Boolean = false) = ChatModelPreset(
        name = name,
        model = "model-$name",
        provider = "ollama",
        contextWindowTokens = 32_768,
        active = active,
        isDefault = name == "default",
    )

    /** The plan's red line, and the WebUI's own rule: `showStopButton = isStreaming && !canSend`. */
    @Test
    fun stopVsSendRule() {
        val model = model()

        model.update(streaming = true)
        assertTrue(model.showsStop, "a running turn with an empty field is a Stop button")

        model.draft = "one more thing"
        assertFalse(model.showsStop, "typing turns the button back into Send")
        assertTrue(model.canSend)

        model.draft = "   "
        assertFalse(model.canSend, "whitespace is not a message")
        assertTrue(model.showsStop, "and an empty field during a turn is Stop again")

        model.update(streaming = false)
        assertFalse(model.showsStop)
    }

    /**
     * The plan's named red line. The design's Android column says the message list and the session
     * actions collapse into one overflow so the title keeps its room: one menu, holding the actions
     * the iOS bar carries as glyphs, and no second place for them to live.
     */
    @Test
    fun overflowMenuHoldsSessionActions() {
        val model = model()

        assertFalse(model.overflowOpen, "the overflow starts closed")
        assertEquals(
            listOf(SessionAction.MessageList, SessionAction.SessionDetails, SessionAction.NewChat),
            model.sessionActions,
            "one overflow holds the bar's session actions, in the design's order",
        )

        model.openOverflow()
        assertTrue(model.overflowOpen)

        model.choose(SessionAction.NewChat)
        assertFalse(model.overflowOpen, "picking a row closes the menu")
        assertEquals(SessionAction.NewChat, model.chosenAction, "and reports the choice to the shell, once")

        model.consumeAction()
        assertNull(model.chosenAction, "the shell consumes it, so a redraw does not repeat it")
    }

    @Test
    fun typingDuringATurnQueuesInsteadOfStopping() = runTest {
        val driver = FakeDriver()
        val model = model(driver)
        model.update(streaming = true)

        model.draft = "  check the tests too "
        model.primaryAction()

        assertEquals(listOf("check the tests too"), driver.enqueued, "Send during a turn queues the prompt")
        assertEquals(0, driver.stops, "it does not stop the turn")
        assertEquals("", model.draft, "the field is handed to the chip")

        model.update(streaming = false)
        model.draft = "next question"
        model.primaryAction()
        assertEquals(listOf("next question"), driver.sent)
    }

    /** The design's three composer states: microphone, Send, Stop (`composerState`). */
    @Test
    fun thePrimaryButtonIsAMicrophoneUntilThereIsText() {
        val model = model()
        assertEquals(PrimaryState.Voice, model.primaryState(recording = false))

        model.draft = "hello"
        assertEquals(PrimaryState.Send, model.primaryState(recording = false))

        model.update(streaming = true)
        model.draft = ""
        assertEquals(PrimaryState.Stop, model.primaryState(recording = false), "a running turn with an empty field")

        model.draft = "queued text"
        assertEquals(PrimaryState.Send, model.primaryState(recording = false), "typing during a turn offers Send (which queues)")

        assertEquals(PrimaryState.Stop, model.primaryState(recording = true), "a recording in flight is stopped by the same button")
    }

    @Test
    fun anEmptySendDoesNothing() = runTest {
        val driver = FakeDriver()
        val model = model(driver)

        model.primaryAction()

        assertTrue(driver.sent.isEmpty())
        assertTrue(driver.enqueued.isEmpty())
        assertEquals(0, driver.stops)
    }

    @Test
    fun copyConfirmsFor1_5s() = runTest {
        val gate = Gate()
        val model = model(gate = gate, scope = CoroutineScope(Dispatchers.Unconfined))

        model.copy("code-1")
        assertTrue(model.isCopied("code-1"), "the checkmark appears at once")
        assertFalse(model.isCopied("code-2"), "only the block that was copied")
        assertEquals(listOf(ComposerViewModel.COPY_CONFIRMATION_MS), gate.durations, "the design system's confirmation window")

        gate.finish()
        // The confirmation clears on the task the test just released.
        repeat(20) { kotlinx.coroutines.yield() }
        assertFalse(model.isCopied("code-1"), "and the checkmark goes away on its own")
    }

    @Test
    fun theRingReadsUsedTokensOverTheContextWindow() {
        val model = model()

        model.update(ComposerSnapshot(contextWindowTokens = 200_000, usedTokens = 84_000))
        assertEquals(42, model.usagePercent, "42% of the window, which is the number the pill shows")

        model.update(ComposerSnapshot(contextWindowTokens = 200_000, usedTokens = 999_999))
        assertEquals(100, model.usagePercent, "a ring never overflows")

        model.update(ComposerSnapshot())
        assertNull(model.usagePercent, "no window yet, no ring: a percentage of nothing is a lie")
    }

    @Test
    fun theModelSheetOffersTheGatewaysPresetsAndNamesTheActiveOne() {
        val model = model()

        model.update(presets = listOf(preset("default"), preset("primary", active = true)))
        model.openModelSheet()

        assertEquals(ComposerSheet.Model, model.sheet)
        assertEquals(listOf("default", "primary"), model.presets.map { it.name })
        assertEquals("primary", model.activePreset?.name)
    }

    @Test
    fun theModelSheetSaysWhetherItsListIsStillComing() = runTest {
        val model = model()
        model.openModelSheet()
        assertEquals(PresetState.Loading, model.presetState, "the sheet opens before the read answers")

        model.loadPresets { listOf(preset("default")) }
        assertEquals(PresetState.Loaded, model.presetState)
        assertEquals(listOf("default"), model.presets.map { it.name })
    }

    @Test
    fun aRefusedPresetReadSaysSoInsteadOfAnEmptySheet() = runTest {
        val model = model()
        model.openModelSheet()

        model.loadPresets { null }

        assertEquals(PresetState.Failed, model.presetState, "an empty sheet and a failed read are not the same screen")
        assertTrue(model.presets.isEmpty())
    }

    @Test
    fun pickingAPresetSendsTheModelCommand() = runTest {
        val driver = FakeDriver()
        val model = model(driver)
        model.update(presets = listOf(preset("default"), preset("primary")))
        model.openModelSheet()

        model.pick(preset("primary"))

        assertEquals(listOf("primary"), driver.presets, "the switch is the /model command on this chat (C6.2)")
        assertNull(model.sheet, "picking closes the sheet")
    }

    @Test
    fun theAccessChipSwitchesToFullAndBack() = runTest {
        val driver = FakeDriver()
        val model = model(driver)
        model.openAccessSheet()

        model.pick(WorkspaceAccess.Full)
        assertEquals(listOf(WorkspaceAccess.Full), driver.accesses)
        assertEquals(WorkspaceAccess.Full, model.snapshot.access, "the chip says Full until the gateway refuses it (C6.3)")

        model.openAccessSheet()
        model.pick(WorkspaceAccess.Default)
        assertEquals(listOf(WorkspaceAccess.Full, WorkspaceAccess.Default), driver.accesses)
        assertEquals(WorkspaceAccess.Default, model.snapshot.access)
    }

    @Test
    fun slashOpensTheCommandsSheetAndAPickInsertsIt() = runTest {
        val driver = FakeDriver()
        driver.paletteSheet = PaletteSheet(
            mode = PaletteMode.Slash,
            rows = listOf(PaletteRow(id = "c1", label = "/stop", subtitle = "Stop the running turn", group = PaletteGroups.Commands, insert = "/stop ")),
        )
        val model = model(driver)

        model.draft = "/sto"
        model.refreshPalette()
        assertEquals(1, driver.palettes)
        assertEquals(ComposerSheet.Palette, model.sheet)

        model.pick(driver.paletteSheet!!.rows.first())
        assertEquals("/stop ", model.draft, "the row's own token is what lands in the field")
        assertNull(model.sheet)
    }

    @Test
    fun deletingTheQueuedPromptClearsTheChip() {
        val driver = FakeDriver()
        val model = model(driver)
        model.update(ComposerSnapshot(queued = "waiting to send"))

        model.deleteQueued()

        assertEquals(1, driver.discarded)
        assertNull(model.snapshot.queued)
    }

    @Test
    fun sendNowTakesTheQueuedPromptOffTheChip() = runTest {
        val driver = FakeDriver()
        val model = model(driver)
        model.update(ComposerSnapshot(queued = "waiting to send"))

        model.sendQueuedNow()

        assertEquals(1, driver.sendNowCount)
        assertNull(model.snapshot.queued)
    }

    @Test
    fun theModelPillNamesWhatTheGatewayRuns() {
        val model = model()
        assertEquals("Model", model.modelLabel, "before any snapshot")

        model.update(ComposerSnapshot(modelName = "deepseek-v4.1-flash:cloud"))
        assertEquals("deepseek-v4.1-flash:cloud", model.modelLabel)

        model.update(ComposerSnapshot(modelName = "x", modelPreset = "primary"))
        assertEquals("primary", model.modelLabel, "the preset's name when there is one")
    }
}
