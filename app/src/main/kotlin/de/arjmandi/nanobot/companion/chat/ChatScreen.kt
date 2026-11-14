package de.arjmandi.nanobot.companion.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.ui.Design
import de.arjmandi.nanobot.companion.ui.readingStyle
import kotlinx.coroutines.launch

/**
 * The thread (plan.md A3): the history the core replayed, the answer as it streams, and one
 * composer whose button is Send or Stop by the view model's rule. Everything on this screen comes
 * from the core's own thread, so a replayed turn and a live one look the same (D9).
 */
@Composable
fun ChatScreen(model: ChatViewModel, modifier: Modifier = Modifier) {
    val thread by model.thread.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Streaming keeps the newest row in view; a reader who scrolled up is left where they are
    // until the turn settles (the artifact's scroll-to-latest, not a jump per delta).
    LaunchedEffect(thread.turns.size, thread.turns.lastOrNull()?.answer?.length) {
        if (thread.turns.isNotEmpty()) listState.animateScrollToItem(thread.turns.lastIndex)
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding()) {
        if (thread.turns.isEmpty()) {
            EmptyThread(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat.thread"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(thread.turns, key = { it.id }) { turn ->
                    TurnRow(turn)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = Design.composerBottomPadding),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = model.draft,
                onValueChange = { model.draft = it },
                placeholder = { Text(stringResource(R.string.chat_composer_hint), fontSize = Design.Interface.bodySize) },
                maxLines = 4,
                shape = RoundedCornerShape(Design.Radius.bubble),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { scope.launch { model.send() } }),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Design.minimumTarget)
                    .testTag("chat.composer"),
            )
            Button(
                onClick = { scope.launch { model.primaryAction() } },
                shape = RoundedCornerShape(Design.Radius.bubble),
                modifier = Modifier
                    .heightIn(min = Design.minimumTarget)
                    .testTag(if (model.showsStop) "chat.stop" else "chat.send"),
            ) {
                Text(stringResource(if (model.showsStop) R.string.chat_stop else R.string.chat_send))
            }
        }
    }
}

/** One turn: the prompt above, the answer below. Nothing here decides what a turn contains. */
@Composable
private fun TurnRow(turn: ChatTurnView) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        turn.prompt?.let { prompt ->
            Text(
                text = prompt,
                style = readingStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(Design.Radius.bubble),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("chat.prompt"),
            )
        }
        if (turn.answer.isNotEmpty()) {
            Text(
                text = turn.answer,
                style = readingStyle,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chat.answer"),
            )
        }
    }
}

@Composable
private fun EmptyThread(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().testTag("chat.empty"),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.chat_empty_title), fontSize = Design.Interface.bodySize)
        Text(
            text = stringResource(R.string.chat_empty_body),
            fontSize = Design.Interface.captionSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
