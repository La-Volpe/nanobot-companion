package de.arjmandi.nanobot.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.arjmandi.nanobot.companion.MainViewModel
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.connect.ConnectScreen
import de.arjmandi.nanobot.companion.connect.ConnectViewModel
import de.arjmandi.nanobot.companion.core.ConnectionStatus
import de.arjmandi.nanobot.companion.core.CoreHolder
import de.arjmandi.nanobot.companion.chat.ChatScreen
import de.arjmandi.nanobot.companion.drawer.DrawerContent
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

    // One owner for "is the drawer open": the model asks, the drawer state answers.
    LaunchedEffect(main.drawerOpen) {
        if (main.drawerOpen && drawerState.isClosed) drawerState.open()
        if (!main.drawerOpen && drawerState.isOpen) drawerState.close()
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
                    onAutomations = onSessionActions,
                    onSettings = onSessionActions,
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
                            // The bar's drawer glyph, as the design's Android mapping draws it.
                            Text(text = "\u2630", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    actions = {
                        ConnectionDot(status)
                        TextButton(onClick = onSessionActions, modifier = Modifier.testTag("chat.sessionActions")) {
                            Text(stringResource(R.string.drawer_session_actions))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
        ) { insets ->
            Thread(
                main = main,
                status = status,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
            )
        }
    }
}

/** The connection dot: one colour per state, and the sentence a screen reader says (A8 draws the banner). */
@Composable
private fun ConnectionDot(status: ConnectionStatus) {
    val colour = when (status) {
        ConnectionStatus.Open -> Color(0xFF4CAF50)
        ConnectionStatus.Connecting -> Color(0xFFFFA726)
        ConnectionStatus.Offline -> Color(0xFF9E9E9E)
        ConnectionStatus.Failed -> Color(0xFFE5675C)
        ConnectionStatus.Idle -> Color(0xFF9E9E9E)
    }
    val label = stringResource(connectionLabel(status))
    Box(
        modifier = Modifier
            .padding(end = 8.dp)
            .size(8.dp)
            .background(colour, CircleShape)
            .semantics { contentDescription = label }
            .testTag("states.dot"),
    )
}

/**
 * The thread, and the way out until Settings lands (A7 draws that screen). The chat is built on
 * the first appearance, which is also the chat the gateway's greeting minted; opening a row from
 * the drawer does not build a second screen, it opens a second chat through the same core.
 */
@Composable
private fun Thread(main: MainViewModel, status: ConnectionStatus, modifier: Modifier = Modifier) {
    val chat = main.chat

    Column(modifier = modifier) {
        Box(modifier = Modifier.weight(1f)) {
            if (chat != null) {
                ChatScreen(model = chat)
            } else {
                Text(
                    text = stringResource(connectionLabel(status)),
                    fontSize = Design.Interface.captionSize,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        TextButton(
            onClick = { main.disconnect() },
            modifier = Modifier.padding(horizontal = 12.dp).testTag("chat.disconnect"),
        ) {
            Text(stringResource(R.string.chat_disconnect), fontSize = Design.Interface.captionSize)
        }
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
