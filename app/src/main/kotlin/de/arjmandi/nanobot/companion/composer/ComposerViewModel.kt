package de.arjmandi.nanobot.companion.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The composer's state and the rules that decide what its one button does (plan.md A5).
 *
 * The button rule is the WebUI's, transcribed rather than invented
 * (`showStopButton = isStreaming && !canSend`): while a turn runs an empty field is a Stop button,
 * and typing turns it back into Send — which queues the prompt instead of stopping the turn.
 * Everything else here is a sheet, a chip, a confirmation or the bar's one overflow.
 */
class ComposerViewModel(
    private var driver: ComposerDriving,
    /** Injected so a test can hold the confirmation open instead of sleeping through it. */
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /**
     * Where the copy confirmation waits. Android's main dispatcher is the real answer and the
     * fallback is for the host JVM, where `Dispatchers.Main` is a test dispatcher nobody installed;
     * a unit test passes its own scope rather than reaching this path.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + uiDispatcher()),
) {

    var draft: String by mutableStateOf("")

    var snapshot: ComposerSnapshot by mutableStateOf(ComposerSnapshot())
        private set

    var presets: List<ChatModelPreset> by mutableStateOf(emptyList())
        private set

    var sheet: ComposerSheet? by mutableStateOf(null)
        private set

    var palette: PaletteSheet? by mutableStateOf(null)
        private set

    var streaming: Boolean by mutableStateOf(false)
        private set

    /** The sentence a refused send shows, as a resource id: the screen owns the copy. */
    var lastRefusalRes: Int? by mutableStateOf(null)
        private set

    /** The code block whose Copy is confirming, if any. */
    var copiedBlockId: String? by mutableStateOf(null)
        private set

    /** The bar's one overflow, and the action it reported to the shell. */
    var overflowOpen: Boolean by mutableStateOf(false)
        private set

    var chosenAction: SessionAction? by mutableStateOf(null)
        private set

    private var copyJob: Job? = null

    /** The design system's copy confirmation window. */
    companion object {
        const val COPY_CONFIRMATION_MS: Long = 1_500

        private fun uiDispatcher() = runCatching { Dispatchers.Main.immediate }.getOrDefault(Dispatchers.Default)
    }

    /** The screen swaps the driver once a gateway is open and the chat has an id. */
    fun attach(driver: ComposerDriving) {
        this.driver = driver
    }

    val canSend: Boolean get() = draft.trim().isNotEmpty()

    /** The WebUI rule (design README, "Controls worth trying"). */
    val showsStop: Boolean get() = streaming && !canSend

    /**
     * What the one button is. A recording outranks everything, then the stop rule, then text,
     * then the microphone.
     */
    fun primaryState(recording: Boolean): PrimaryState = when {
        recording || showsStop -> PrimaryState.Stop
        canSend -> PrimaryState.Send
        else -> PrimaryState.Voice
    }

    /** The ring, rounded to the whole percent the pill prints; null before there is a window. */
    val usagePercent: Int?
        get() {
            val window = snapshot.contextWindowTokens ?: return null
            val used = snapshot.usedTokens ?: return null
            if (window <= 0) return null
            val percent = (used.toDouble() / window * 100).toInt()
            return percent.coerceIn(0, 100)
        }

    val activePreset: ChatModelPreset? get() = presets.firstOrNull { it.active }

    /** The pill's label: the preset's name when there is one, else the model itself. */
    val modelLabel: String
        get() {
            snapshot.modelPreset?.takeIf { it.isNotEmpty() }?.let { return it }
            snapshot.modelName?.takeIf { it.isNotEmpty() }?.let { return it }
            return "Model"
        }

    /** The bar's session actions, in the design's order, as the overflow lists them. */
    val sessionActions: List<SessionAction> =
        listOf(SessionAction.MessageList, SessionAction.SessionDetails, SessionAction.NewChat)

    // Inputs from the chat and the bridge.

    fun update(streaming: Boolean) {
        this.streaming = streaming
    }

    fun update(snapshot: ComposerSnapshot) {
        this.snapshot = snapshot
    }

    fun update(presets: List<ChatModelPreset>) {
        this.presets = presets
    }

    // The primary action.

    /**
     * What the one button does: stop while a turn runs and the field is empty, queue while a turn
     * runs and something is typed, send otherwise.
     */
    suspend fun primaryAction() {
        if (showsStop) {
            stop()
            return
        }
        send()
    }

    /** The send half on its own: the thread's model asks for it without the stop rule. */
    suspend fun send() {
        if (!canSend) return
        val text = draft.trim()
        if (streaming) {
            driver.enqueue(text)
            // The chip shows it, so the field is empty again — the core keeps the text.
            draft = ""
            snapshot = snapshot.copy(queued = listOfNotNull(snapshot.queued, text).joinToString("\n"))
            return
        }
        draft = ""
        lastRefusalRes = null
        if (!driver.sendPrompt(text)) {
            // A refused send gives the text back rather than losing it (C6.1).
            draft = text
            lastRefusalRes = de.arjmandi.nanobot.companion.R.string.chat_send_refused
        }
    }

    suspend fun stop() {
        driver.stop()
    }

    fun clearRefusal() {
        lastRefusalRes = null
    }

    // The queued prompt chip.

    suspend fun sendQueuedNow() {
        driver.sendQueuedNow()
        snapshot = snapshot.copy(queued = null)
    }

    fun deleteQueued() {
        driver.discardQueued()
        snapshot = snapshot.copy(queued = null)
    }

    // The overflow.

    fun openOverflow() {
        overflowOpen = true
    }

    fun closeOverflow() {
        overflowOpen = false
    }

    /** Picking a row closes the menu and hands the choice to the shell, which performs it. */
    fun choose(action: SessionAction) {
        overflowOpen = false
        chosenAction = action
    }

    fun consumeAction() {
        chosenAction = null
    }

    // Sheets.

    fun openModelSheet() {
        sheet = ComposerSheet.Model
    }

    fun openAccessSheet() {
        sheet = ComposerSheet.Access
    }

    fun openUsageSheet() {
        sheet = ComposerSheet.Usage
    }

    fun openAttachSheet() {
        sheet = ComposerSheet.Attach
    }

    fun openMessageList() {
        sheet = ComposerSheet.MessageList
    }

    fun openSessionDetails() {
        sheet = ComposerSheet.SessionDetails
    }

    fun closeSheet() {
        sheet = null
    }

    /** The sheet's list comes from the settings read the gateway already answers (C6.2). */
    suspend fun loadPresets(load: suspend () -> List<ChatModelPreset>) {
        val loaded = load()
        if (loaded.isNotEmpty()) presets = loaded
    }

    suspend fun pick(preset: ChatModelPreset) {
        sheet = null
        driver.setModelPreset(preset.name)
    }

    suspend fun pick(access: WorkspaceAccess) {
        sheet = null
        // The chip shows the choice at once and goes back if the gateway refuses it (C6.3).
        snapshot = snapshot.copy(access = access)
        driver.setWorkspaceScope(access)
    }

    // Palettes.

    /**
     * Ask for the sheet the current text opens, if any (C6.4). The core decides; this only carries
     * the answer to the screen.
     */
    suspend fun refreshPalette() {
        val loaded = driver.loadPalette(draft, draft.length)
        palette = loaded
        sheet = if (loaded == null) {
            if (sheet == ComposerSheet.Palette) null else sheet
        } else {
            ComposerSheet.Palette
        }
    }

    fun pick(row: PaletteRow) {
        draft = row.insert
        palette = null
        sheet = null
    }

    // Copy.

    fun isCopied(blockId: String): Boolean = copiedBlockId == blockId

    /**
     * A code block's Copy confirms for 1.5 s and then returns to its own label, which is what the
     * design system specifies.
     */
    fun copy(blockId: String) {
        copiedBlockId = blockId
        copyJob?.cancel()
        copyJob = scope.launch {
            sleep(COPY_CONFIRMATION_MS)
            if (copiedBlockId == blockId) copiedBlockId = null
        }
    }
}
