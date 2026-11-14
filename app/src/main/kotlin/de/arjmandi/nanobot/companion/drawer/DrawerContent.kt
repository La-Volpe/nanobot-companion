package de.arjmandi.nanobot.companion.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.core.SessionSummary
import de.arjmandi.nanobot.companion.ui.Design

/**
 * The session drawer (plan.md A2): brand, search, the session list, and the archived row and
 * Settings pinned at the bottom in every state — they are not sessions, so a search never hides
 * them. It reads [DrawerViewModel] and calls commands; nothing here branches on data.
 */
@Composable
fun DrawerContent(
    model: DrawerViewModel,
    selectedKey: String?,
    onSelect: (SessionSummary) -> Unit,
    onNewChat: () -> Unit,
    onAutomations: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp),
    ) {
        Brand(onNewChat = onNewChat, onSettings = onSettings)
        Search(model = model)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        Column(modifier = Modifier.weight(1f)) {
            when (val state = model.state) {
                DrawerViewModel.ListState.Loading -> LoadingState()
                DrawerViewModel.ListState.Empty, is DrawerViewModel.ListState.NoMatches ->
                    MessageState(model.emptyMessage.orEmpty())
                is DrawerViewModel.ListState.Rows -> Rows(state.rows, selectedKey, onSelect)
            }
        }

        // Pinned: the automation list and Settings are entries, not sessions, so they survive a
        // search that matches nothing.
        PinnedRow(stringResource(R.string.drawer_archived), "drawer.archived", onClick = model::toggleArchived)
        PinnedRow(stringResource(R.string.drawer_automations), "drawer.automations", onClick = onAutomations)
        TextButton(
            onClick = onSettings,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Design.minimumTarget)
                .testTag("drawer.settings"),
        ) {
            Text(stringResource(R.string.drawer_settings), fontSize = 14.sp)
        }
    }
}

@Composable
private fun Brand(onNewChat: () -> Unit, onSettings: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Design.minimumTarget)
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "n",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .size(26.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                    .padding(6.dp),
            )
            Text(
                text = stringResource(R.string.drawer_brand),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Row {
            TextButton(onClick = onNewChat, modifier = Modifier.heightIn(min = Design.minimumTarget).testTag("drawer.newChat")) {
                Text(stringResource(R.string.drawer_new_chat), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun Search(model: DrawerViewModel) {
    OutlinedTextField(
        value = model.searchText,
        onValueChange = { model.searchText = it },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.drawer_search_hint), fontSize = Design.Interface.captionSize) },
        shape = RoundedCornerShape(Design.Radius.field),
        trailingIcon = {
            if (model.isSearching) {
                TextButton(onClick = model::clearSearch, modifier = Modifier.testTag("drawer.clearSearch")) {
                    Text(stringResource(R.string.drawer_search_clear), fontSize = Design.Interface.microSize)
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .heightIn(min = Design.minimumTarget)
            .testTag("drawer.search"),
    )
}

@Composable
private fun LoadingState() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp).testTag("drawer.loading"))
    }
}

@Composable
private fun MessageState(message: String) {
    Text(
        text = message,
        fontSize = Design.Interface.captionSize,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("drawer.message"),
    )
}

@Composable
private fun Rows(rows: List<SessionSummary>, selectedKey: String?, onSelect: (SessionSummary) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag("drawer.rows")) {
        items(rows, key = { it.key }) { row ->
            val selected = row.key == selectedKey
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Design.minimumTarget)
                    .background(
                        color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(Design.Radius.field),
                    )
                    .clickable { onSelect(row) }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("drawer.row.${row.key}"),
            ) {
                Text(
                    text = row.displayTitle,
                    fontSize = Design.Interface.bodySize,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.preview.isNotEmpty()) {
                    Text(
                        text = row.preview,
                        fontSize = Design.Interface.captionSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun PinnedRow(label: String, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Design.minimumTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, fontSize = Design.Interface.captionSize)
    }
}
