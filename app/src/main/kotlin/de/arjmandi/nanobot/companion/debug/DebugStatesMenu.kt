package de.arjmandi.nanobot.companion.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.composer.ComposerViewModel
import de.arjmandi.nanobot.companion.states.DebugState
import de.arjmandi.nanobot.companion.states.StatesViewModel
import de.arjmandi.nanobot.companion.ui.Design

/**
 * A8's debug menu, the Android twin of iOS's `DebugMenu` (G2 asked for it, and G3 keeps it): the
 * eight taxonomy failures with their sentences, then the chat states the artifact's chips inject,
 * then one way back. DEBUG builds only; the release build never compiles the entry point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugStatesMenu(
    states: StatesViewModel,
    composer: ComposerViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.states_debug_title)) },
                navigationIcon = {
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = Design.minimumTarget).testTag("debug.close")) {
                        Text(stringResource(R.string.settings_back))
                    }
                },
            )
        },
        modifier = modifier.testTag("debug.menu"),
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Heading(stringResource(R.string.states_debug_failures))
            DebugState.failureScenarios.forEach { scenario ->
                TextButton(
                    onClick = { states.debugFail(scenario.kind, scenario.detail) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Design.minimumTarget).testTag("debug.fail.${scenario.kind.name}"),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(scenario.title, fontSize = Design.Interface.bodySize, color = MaterialTheme.colorScheme.onSurface)
                        scenario.detail?.let {
                            Text(it, fontSize = Design.Interface.microSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Heading(stringResource(R.string.states_debug_chats))
            DebugState.ChatState.entries.forEach { state ->
                TextButton(
                    onClick = {
                        states.debugInject(state)
                        if (state == DebugState.ChatState.Queued) {
                            composer.update(
                                composer.snapshot.copy(queued = "Then check whether the 6 MiB cap is enforced client-side too"),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = Design.minimumTarget).testTag("debug.chat.${state.name}"),
                ) {
                    // A sectioned list's rows read from the left; a centred label looks like a button
                    // and breaks the column the design draws.
                    androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
                        Text(state.title, fontSize = Design.Interface.bodySize, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            TextButton(
                onClick = {
                    states.debugClear()
                    onClose()
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = Design.minimumTarget).testTag("debug.clear"),
            ) {
                androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.states_debug_clear), fontSize = Design.Interface.bodySize)
                }
            }
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = Design.Interface.microSize,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}
