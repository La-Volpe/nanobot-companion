package de.arjmandi.nanobot.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.arjmandi.nanobot.companion.chat.ChatDriver
import de.arjmandi.nanobot.companion.chat.ChatViewModel
import de.arjmandi.nanobot.companion.composer.ComposerDriving
import de.arjmandi.nanobot.companion.composer.ComposerSnapshot
import de.arjmandi.nanobot.companion.composer.EmptyComposerDriver
import de.arjmandi.nanobot.companion.core.ChatSession
import de.arjmandi.nanobot.companion.core.CoreHolder
import de.arjmandi.nanobot.companion.core.SessionSummary
import de.arjmandi.nanobot.companion.drawer.DrawerViewModel
import de.arjmandi.nanobot.companion.settings.SettingsViewModel
import de.arjmandi.nanobot.companion.states.ConnectionStatus
import de.arjmandi.nanobot.companion.states.ConnectionView
import kotlinx.coroutines.flow.StateFlow

/**
 * The shell's own state (plan.md A1–A3): whether onboarding or the shell is on screen, which chat
 * the thread is showing, and whether the drawer is open. The connection, the drawer's rows and the
 * thread itself are flows from the holder, so there is exactly one owner per piece of state and
 * this class never copies one.
 */
class MainViewModel(
    val holder: CoreHolder,
    /** Both bridges for one chat: the real pair in the app, scripted ones in a test. */
    private val session: (chatId: String?) -> ChatSession,
) {

    /** Onboarding until a gateway opened, then the shell. */
    var connected by mutableStateOf(false)
        private set

    /** The drawer, opened from the bar and closed by a choice or the scrim. */
    var drawerOpen by mutableStateOf(false)
        private set

    /**
     * Settings (A7), attached by the composition root once the shell exists: its disconnect calls
     * back into [disconnect], so the two are built in that order rather than in one constructor.
     */
    var settings: SettingsViewModel? = null
        private set

    var settingsOpen by mutableStateOf(false)
        private set

    /** The DEBUG menu (A8): reachable in a debug build only, and it injects the state objects. */
    var debugOpen by mutableStateOf(false)
        private set

    /**
     * The connection as the states surfaces draw it (A8). One value, pushed to the chat's own states
     * whenever the holder emits, so the bar's dot and the chat's banner cannot disagree.
     */
    var connectionView by mutableStateOf(ConnectionView(ConnectionStatus.Idle))
        private set

    val drawer: DrawerViewModel = DrawerViewModel()

    /** The row whose chat the thread is showing; null for the chat the gateway minted at connect. */
    var selected by mutableStateOf<SessionSummary?>(null)
        private set

    /** The thread the shell draws: one model per chat, kept so switching back keeps what it saw. */
    var chat by mutableStateOf<ChatViewModel?>(null)
        private set

    private val chats = mutableMapOf<String?, ChatViewModel>()

    /** The bar's title: the gateway's name for the row, or the app's own before anything is picked. */
    val title: String get() = selected?.displayTitle ?: "nanobot"

    fun enterShell() {
        connected = true
    }

    fun attachSettings(settings: SettingsViewModel) {
        this.settings = settings
    }

    fun openSettings() {
        drawerOpen = false
        settingsOpen = true
    }

    fun closeSettings() {
        settingsOpen = false
    }

    fun updateConnection(view: ConnectionView) {
        connectionView = view
        chat?.states?.update(view)
    }

    fun openDebug() {
        debugOpen = true
    }

    fun closeDebug() {
        debugOpen = false
    }

    /** The drawer's own gesture reports back, so the model and the drawer never disagree. */
    fun reportDrawerOpen(open: Boolean) {
        drawerOpen = open
    }

    fun openDrawer() {
        drawerOpen = true
    }

    fun closeDrawer() {
        drawerOpen = false
    }

    /**
     * The rows arrive from the holder's flow; the drawer decides what they look like. A row whose
     * chat was deleted on the gateway is dropped from the title rather than kept as a ghost.
     */
    fun updateSessions(rows: List<SessionSummary>, loaded: Boolean) {
        drawer.update(rows, loaded)
        val current = selected
        if (current != null && loaded && rows.none { it.key == current.key }) selected = null
    }

    /**
     * Opening a chat: one command, which the shell runs when it appears and when a row is picked.
     * The core attaches and replays the stored history, which is what makes a resumed session show
     * its turns (D46, P9).
     */
    suspend fun openChat(chatId: String?) {
        val model = chats.getOrPut(chatId) {
            val opened = session(chatId)
            ChatViewModel(
                chat = opened.chat,
                composerDriver = opened.composer,
                snapshots = opened.snapshots,
                attachmentDriver = opened.attachments,
                voiceDriver = opened.voice,
            )
        }
        chat = model
        // The chat's states start from the connection the shell already knows (P9's lesson).
        model.states.update(connectionView)
        model.open(chatId)
    }

    /** Picking a row: the drawer closes and the thread moves to that chat. */
    fun select(row: SessionSummary) {
        selected = row
        drawerOpen = false
    }

    fun newChat() {
        selected = null
        drawerOpen = false
    }

    /**
     * Settings → Gateway or the debug menu's way out: one command, which forgets the address and
     * the key and returns to onboarding.
     */
    /** The bar's overflow chose a row, so the shell performs it and the choice is consumed once. */
    fun consumeComposerAction() {
        chat?.composer?.consumeAction()
    }

    /** The model sheet's list: the gateway's presets, read when the sheet opens. */
    suspend fun presets(): List<de.arjmandi.nanobot.companion.composer.ChatModelPreset> = holder.presets()

    fun disconnect() {
        connected = false
        debugOpen = false
        settingsOpen = false
        selected = null
        chat = null
        chats.clear()
        holder.disconnect()
    }
}
