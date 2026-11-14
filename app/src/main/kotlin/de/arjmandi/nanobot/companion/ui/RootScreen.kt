package de.arjmandi.nanobot.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.arjmandi.nanobot.companion.MainViewModel
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.automations.AutomationsScreen
import de.arjmandi.nanobot.companion.chat.ChatScreen
import de.arjmandi.nanobot.companion.composer.SessionAction
import de.arjmandi.nanobot.companion.composer.SessionActionsOverflow
import de.arjmandi.nanobot.companion.connect.ConnectScreen
import de.arjmandi.nanobot.companion.connect.ConnectViewModel
import de.arjmandi.nanobot.companion.core.ConnectionStatus
import de.arjmandi.nanobot.companion.core.CoreHolder
import de.arjmandi.nanobot.companion.debug.DebugStatesMenu
import de.arjmandi.nanobot.companion.drawer.DrawerContent
import de.arjmandi.nanobot.companion.settings.SettingsScreen
import de.arjmandi.nanobot.companion.states.ConnectionDot
import de.arjmandi.nanobot.companion.states.ConnectionDotView
import kotlinx.coroutines.launch

/**
 * The app's screen (plan.md A1, A2): onboarding, then the shell — a `ModalNavigationDrawer` whose
 * content is the bar, the connection dot and (from A3) the thread. Android's navigation is the
 * design's own mapping (README: "TopAppBar, ModalNavigationDrawer"), and session actions collapse
 * into one overflow so the title keeps its room on a 412 dp screen.
 */
@Composable
fun RootScreen(
    connect: ConnectViewModel,
    main: MainViewModel,
    holder: CoreHolder,
    onSessionActions: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val connection by holder.connection.collectAsStateWithLifecycle()
    val sessions by holder.sessions.collectAsStateWithLifecycle()

    LaunchedEffect(sessions) {
        main.updateSessions(sessions.rows, sessions.loaded)
    }
    // The greeting minted a chat before this screen existed, so the shell opens one on its first
    // appearance rather than waiting for a row to be picked; picking a row opens that chat instead,
    // which is the same command on a different id.
    LaunchedEffect(main.connected, main.selected?.chatId) {
        if (main.connected) main.openChat(main.selected?.chatId)
    }

    if (main.debugOpen) {
        val chat = main.chat
        if (chat != null) {
            DebugStatesMenu(states = chat.states, composer = chat.composer, onClose = { main.closeDebug() }, modifier = modifier)
            return
        }
    }

    if (main.automationsOpen) {
        AutomationsScreen(
            model = main.automations,
            onClose = { main.closeAutomations() },
            onAskNanobot = { main.askNanobotForAutomation() },
            modifier = modifier,
        )
        return
    }

    if (main.settingsOpen) {
        val settings = main.settings
        if (settings != null) {
            SettingsScreen(
                model = settings,
                loadSnapshot = { holder.settingsSnapshot() },
                onClose = { main.closeSettings() },
                modifier = modifier,
            )
            return
        }
    }

    if (main.connected) {
        Shell(
            main = main,
            status = connection.status,
            onSessionActions = onSessionActions,
            modifier = modifier,
        )
    } else {
        ConnectScreen(
            model = connect,
            onConnected = { main.enterShell() },
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Shell(
    main: MainViewModel,
    status: ConnectionStatus,
    onSessionActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Back closes the nearest thing first: an open sheet, then the drawer, and nothing else is
    // taken from the system. A shell that swallowed Back everywhere would trap the user.
    val chat = main.chat
    androidx.activity.compose.BackHandler(enabled = chat?.composer?.sheet != null || main.drawerOpen) {
        if (chat?.composer?.sheet != null) chat.composer.closeSheet() else main.closeDrawer()
    }

    // One owner for "is the drawer open": the model asks, the drawer state answers — and the
    // swipe reports back, so dragging it open also tells the model (Back then knows to close it).
    LaunchedEffect(main.drawerOpen) {
        if (main.drawerOpen && drawerState.isClosed) drawerState.open()
        if (!main.drawerOpen && drawerState.isOpen) drawerState.close()
    }
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.isOpen }.collect { open -> main.reportDrawerOpen(open) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = main.connected,
        drawerContent = {
            ModalDrawerSheet {
                DrawerContent(
                    model = main.drawer,
                    selectedKey = main.selected?.key,
                    onSelect = { main.select(it) },
                    onNewChat = { main.newChat() },
                    onAutomations = { main.openAutomations() },
                    onSettings = { main.openSettings() },
                )
            }
        },
        modifier = modifier,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = main.title, maxLines = 1) },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                scope.launch { main.openDrawer() }
                            },
                            modifier = Modifier.testTag("chat.openDrawer"),
                        ) {
                            // The WebUI's own menu glyph (lucide `Menu`), not a Unicode trigram.
                            Icon(
                                painter = painterResource(R.drawable.ic_webui_menu),
                                contentDescription = stringResource(R.string.drawer_open),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    actions = {
                        val dot = main.chat?.states?.dot ?: status.toDot(main.connectionView.everConnected)
                        ConnectionDotView(dot)
                        if (de.arjmandi.nanobot.companion.BuildConfig.DEBUG_MENU) {
                            IconButton(onClick = { main.openDebug() }, modifier = Modifier.testTag("chat.debug")) {
                                Text(text = "\uD83D\uDC1E", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        // The design's Android column: one overflow holds the message list, the
                        // session details and a new chat, so the title keeps its room.
                        val composer = main.chat?.composer
                        IconButton(
                            onClick = { composer?.openOverflow() },
                            modifier = Modifier.testTag("chat.sessionActions"),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_webui_more_horizontal),
                                contentDescription = stringResource(R.string.drawer_session_actions),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        if (composer != null) {
                            SessionActionsOverflow(
                                composer = composer,
                                expanded = composer.overflowOpen,
                                onDismiss = { composer.closeOverflow() },
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
        ) { insets ->
            Thread(
                main = main,
                status = status,
                onSessionActions = onSessionActions,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
            )
        }
    }
}

/** The holder's connection as the dot's six states, for a shell that has no chat yet. */
private fun de.arjmandi.nanobot.companion.core.ConnectionStatus.toDot(everConnected: Boolean): ConnectionDot = when (this) {
    de.arjmandi.nanobot.companion.core.ConnectionStatus.Idle -> ConnectionDot.Idle
    de.arjmandi.nanobot.companion.core.ConnectionStatus.Connecting -> ConnectionDot.Connecting
    de.arjmandi.nanobot.companion.core.ConnectionStatus.Open -> ConnectionDot.Open
    de.arjmandi.nanobot.companion.core.ConnectionStatus.Offline ->
        if (everConnected) ConnectionDot.Reconnecting else ConnectionDot.Offline
    de.arjmandi.nanobot.companion.core.ConnectionStatus.Failed -> ConnectionDot.Failed
}

/**
 * The thread, and the way out until Settings lands (A7 draws that screen). The chat is built on
 * the first appearance, which is also the chat the gateway's greeting minted; opening a row from
 * the drawer does not build a second screen, it opens a second chat through the same core.
 */
@Composable
private fun Thread(
    main: MainViewModel,
    status: ConnectionStatus,
    onSessionActions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chat = main.chat

    // The overflow reports its choice once; the shell performs it and consumes it.
    val chosen = chat?.composer?.chosenAction
    LaunchedEffect(chosen) {
        val composer = chat?.composer ?: return@LaunchedEffect
        when (chosen) {
            SessionAction.MessageList -> composer.openMessageList()
            SessionAction.SessionDetails -> composer.openSessionDetails()
            SessionAction.NewChat -> main.newChat()
            null -> Unit
        }
        if (chosen != null) composer.consumeAction()
    }

    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f)) {
            if (chat != null) {
                ChatScreen(
                    model = chat,
                    sessionTitle = main.title,
                    sessionChatId = main.selected?.chatId,
                    loadPresets = { main.presets() },
                )
            } else {
                Text(
                    text = stringResource(connectionLabel(status)),
                    fontSize = Design.Interface.captionSize,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        // The way out is Settings → Gateway (A7), which forgets the address and the key; the
        // thread no longer carries a placeholder button for it (D48's note, now closed).
    }
}

/** The dot's five sentences, as the bar reads them. */
@Composable
internal fun connectionLabel(status: ConnectionStatus): Int = when (status) {
    ConnectionStatus.Idle -> R.string.chat_connection_idle
    ConnectionStatus.Connecting -> R.string.chat_connection_connecting
    ConnectionStatus.Open -> R.string.chat_connection_connected
    ConnectionStatus.Offline -> R.string.chat_connection_disconnected
    ConnectionStatus.Failed -> R.string.chat_connection_error
}
