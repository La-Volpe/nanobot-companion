package de.arjmandi.nanobot.companion.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.ui.Design
import de.arjmandi.nanobot.companion.ui.scaledReadingStyle

/**
 * The activity cluster (A4): one collapsible row per turn holding what nanobot thought, ran and
 * changed. The layout follows the artifact's `.activity` block — a head with a chevron, the label
 * and a status word, and a body of reasoning, tool rows, diffs and source chips. The composable
 * draws; every fold rule is [ActivityViewModel]'s.
 */
@Composable
fun ActivityClusterRow(cluster: ActivityCluster, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Design.Radius.bubble))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("activity.cluster"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Design.minimumTarget)
                .clickable(onClick = onToggle)
                .padding(horizontal = 11.dp, vertical = 9.dp)
                .testTag("activity.head"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (cluster.isOpen) "\u2304" else "\u203A",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = headlineText(cluster.headline),
                fontSize = Design.Interface.captionSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = statusText(cluster.status),
                fontSize = Design.Interface.microSize,
                color = if (cluster.hasFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("activity.status"),
            )
        }
        if (cluster.isOpen) {
            Column(
                modifier = Modifier.padding(horizontal = 11.dp).padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                cluster.items.forEach { ActivityItemRow(it) }
                if (cluster.sourceChips.isNotEmpty()) ActivitySourcesRow(cluster.sourceChips)
            }
        }
    }
}

/** `Thought for 8s · 3 steps · 1 file changed`, and no clause for a count of zero. */
@Composable
private fun headlineText(headline: ActivityHeadline): String = when (headline) {
    ActivityHeadline.Thinking -> stringResource(R.string.activity_thinking)
    is ActivityHeadline.Finished -> buildList {
        add(stringResource(R.string.activity_thought_for, headline.seconds))
        add(pluralStringResource(R.plurals.activity_steps, headline.steps, headline.steps))
        if (headline.files > 0) {
            add(pluralStringResource(R.plurals.activity_files_changed, headline.files, headline.files))
        }
    }.joinToString(" \u00b7 ")
}

@Composable
private fun statusText(status: ActivityStatus): String = stringResource(
    when (status) {
        ActivityStatus.Running -> R.string.activity_status_running
        ActivityStatus.Done -> R.string.activity_status_done
        ActivityStatus.Failed -> R.string.activity_status_failed
    },
)

/** One row in the cluster's body: reasoning, a tool run, an edited file, or a search. */
@Composable
fun ActivityItemRow(item: ActivityItemView, modifier: Modifier = Modifier) {
    when (val kind = item.kind) {
        is ActivityKind.Reasoning -> Row(modifier = modifier.fillMaxWidth()) {
            // The design's rule: reasoning is quoted by a hairline, not by a bubble.
            Spacer(
                modifier = Modifier
                    .width(2.dp)
                    .heightIn(min = 18.dp)
                    .background(MaterialTheme.colorScheme.outline),
            )
            Text(
                text = kind.text,
                style = scaledReadingStyle().copy(fontSize = Design.Activity.reasoningSize, lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .testTag("activity.reasoning"),
            )
        }

        is ActivityKind.Tool -> GlyphRow(
            glyph = Design.Activity.glyph(kind.name),
            label = kind.name,
            detail = kind.summary,
            modifier = modifier.testTag("activity.tool"),
            trailing = { ToolState(kind.status, kind.detail) },
        )

        is ActivityKind.FileEdit -> FileEditRow(kind, modifier = modifier)

        is ActivityKind.Search -> GlyphRow(
            glyph = "\u2315",
            label = stringResource(R.string.activity_web_search),
            detail = kind.query,
            modifier = modifier.testTag("activity.search"),
            trailing = {
                Text(
                    text = stringResource(R.string.activity_sources, kind.sources.size),
                    fontSize = Design.Interface.microSize,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
    }
}

@Composable
private fun GlyphRow(
    glyph: String,
    label: String,
    detail: String?,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = Design.Activity.rowHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = glyph,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(4.dp),
        )
        Text(text = label, fontSize = Design.Interface.captionSize, fontWeight = FontWeight.SemiBold)
        if (!detail.isNullOrEmpty()) {
            Text(
                text = detail,
                fontSize = Design.Interface.microSize,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
private fun ToolState(status: ToolRunStatus, detail: String?) {
    when (status) {
        // A running tool is a dot that says it is working, because the row has no spinner of its own.
        ToolRunStatus.Running -> Text(
            text = stringResource(R.string.activity_status_running),
            fontSize = Design.Interface.microSize,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ToolRunStatus.Done -> Text(
            text = detail ?: stringResource(R.string.activity_tool_done),
            fontSize = Design.Interface.microSize,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF3F8F4B),
        )

        ToolRunStatus.Failed -> Text(
            text = detail ?: stringResource(R.string.activity_tool_failed),
            fontSize = Design.Interface.microSize,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** One edited file: path, `+n −n`, and the unified diff as rows. */
@Composable
fun FileEditRow(state: ActivityKind.FileEdit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Design.Radius.field))
            .background(MaterialTheme.colorScheme.surface)
            .testTag("activity.diff"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = state.path,
                fontSize = Design.Interface.microSize,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(text = "+${state.added}", fontSize = Design.Interface.microSize, fontWeight = FontWeight.SemiBold, color = Color(0xFF3F8F4B))
            Text(text = "\u2212${state.deleted}", fontSize = Design.Interface.microSize, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
        }
        state.lines.forEach { line ->
            Text(
                text = line.text,
                fontSize = Design.Activity.diffSize,
                fontFamily = FontFamily.Monospace,
                color = when (line.kind) {
                    DiffKind.Added -> Color(0xFF3F8F4B)
                    DiffKind.Removed -> MaterialTheme.colorScheme.error
                    DiffKind.Context, DiffKind.Hunk -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        when (line.kind) {
                            DiffKind.Added -> Color(0x1F3F8F4B)
                            DiffKind.Removed -> Color(0x1FB3261E)
                            DiffKind.Context, DiffKind.Hunk -> Color.Transparent
                        },
                    )
                    .padding(horizontal = 9.dp, vertical = 1.dp),
            )
        }
    }
}

/** The design draws a source as a capsule with the host and the path tail in it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivitySourcesRow(chips: List<SourceChip>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        chips.forEach { chip ->
            Text(
                text = chip.label,
                fontSize = Design.Interface.microSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .testTag("activity.source"),
            )
        }
    }
}
