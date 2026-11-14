package de.arjmandi.nanobot.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.arjmandi.nanobot.companion.chat.ChatDriver
import de.arjmandi.nanobot.companion.chat.ChatViewModel
import de.arjmandi.nanobot.companion.core.CoreHolder
import de.arjmandi.nanobot.companion.core.SessionSummary
import de.arjmandi.nanobot.companion.drawer.DrawerViewModel

/**
 * The shell's own state (plan.md A1–A3): whether onboarding or the shell is on screen, which chat
 * the thread is showing, and whether the drawer is open. The connection, the drawer's rows and the
 * thread itself are flows from the holder, so there is exactly one owner per piece of state and
 * this class never copies one.
 */
class MainViewModel(
    val holder: CoreHolder,
    /** The driver for one chat: the real one in the app, a scripted one in a test. */
    private val driver: (chatId: String?) -> ChatDriver,
) {

    /** Onboarding until a gateway opened, then the shell. */
    var connected by mutableStateOf(false)
        private set

    /** The drawer, opened from the bar and closed by a choice or the scrim. */
    var drawerOpen by mutableStateOf(false)
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
        val model = chats.getOrPut(chatId) { ChatViewModel(driver(chatId)) }
        chat = model
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
    fun disconnect() {
        connected = false
        selected = null
        chat = null
        chats.clear()
        holder.disconnect()
    }
}
