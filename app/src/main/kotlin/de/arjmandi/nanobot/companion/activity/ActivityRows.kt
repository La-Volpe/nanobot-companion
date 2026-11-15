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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
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
fun ActivityClusterRow(
    cluster: ActivityCluster,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleDetails: (String) -> Unit = {},
) {
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
                cluster.items.forEach { ActivityItemRow(it, onToggleDetails) }
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

/**
 * One row in the cluster's body: reasoning, a tool run, an edited file, or a search.
 *
 * Each row is one line — glyph, name, one-line summary, state — and everything the gateway sent
 * beyond that (arguments, output, the whole reasoning block) is behind the row's own disclosure,
 * closed until the reader opens it. That is the web's shape (`ActivityStep.tsx:77`), which the
 * owner reported this screen was missing (T9).
 */
@Composable
fun ActivityItemRow(
    item: ActivityItemView,
    onToggleDetails: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (val kind = item.kind) {
        is ActivityKind.Reasoning -> ReasoningLine(item, kind, onToggleDetails, modifier)

        is ActivityKind.Tool -> Column(modifier = modifier.testTag("activity.tool")) {
            ActivityLine(
                glyph = activityGlyph(kind.name),
                label = kind.name,
                detail = kind.summary,
                error = kind.status == ToolRunStatus.Failed,
                onClick = { onToggleDetails(item.id) },
                trailing = { ToolState(kind.status, kind.detail) },
            )
            if (item.detailsOpen) {
                ToolDetails(kind)
            }
        }

        is ActivityKind.FileEdit -> FileEditRow(kind, modifier = modifier)

        is ActivityKind.Search -> ActivityLine(
            glyph = R.drawable.ic_webui_globe,
            label = stringResource(R.string.activity_web_search),
            detail = kind.query,
            onClick = null,
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

/**
 * Reasoning is the model thinking out loud: one line of prose, compacted exactly as the web's
 * `compactReasoningPreview` does, and the block itself only when the reader asks for it.
 */
@Composable
private fun ReasoningLine(
    item: ActivityItemView,
    kind: ActivityKind.Reasoning,
    onToggleDetails: (String) -> Unit,
    modifier: Modifier,
) {
    val line = item.preview.orEmpty().ifEmpty { stringResource(R.string.activity_thinking) }
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Design.Activity.rowHeight)
                .clickable { onToggleDetails(item.id) }
                .testTag("activity.reasoning"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The design's rule: reasoning is quoted by a hairline, not by a bubble.
            Spacer(
                modifier = Modifier
                    .width(2.dp)
                    .heightIn(min = 18.dp)
                    .background(MaterialTheme.colorScheme.outline),
            )
            Text(
                text = line,
                style = scaledReadingStyle().copy(fontSize = Design.Activity.reasoningSize, lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!kind.done) {
                Text(
                    text = stringResource(R.string.activity_thinking),
                    fontSize = Design.Interface.microSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (item.detailsOpen && kind.text.isNotBlank()) {
            Text(
                text = kind.text,
                style = scaledReadingStyle().copy(fontSize = Design.Activity.reasoningSize, lineHeight = 20.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 10.dp, top = 4.dp, bottom = 2.dp)
                    .testTag("activity.reasoning.details"),
            )
        }
    }
}

/** What the row kept back: the arguments it was called with and the output it returned. */
@Composable
private fun ToolDetails(kind: ActivityKind.Tool) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 30.dp, top = 2.dp, bottom = 4.dp)
            .testTag("activity.tool.details"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DetailLine(stringResource(R.string.activity_arguments), kind.summary)
        if (kind.status != ToolRunStatus.Running) {
            DetailLine(stringResource(R.string.activity_output), kind.detail)
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(
            text = label,
            fontSize = Design.Interface.microSize,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontSize = Design.Interface.microSize,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // A tool's own output can be a whole file; the row shows the start of it, not all of it.
            maxLines = 12,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A row is one line: the tool's glyph, its name, the one-line summary the core reduced from the
 * arguments, and the state. Nothing here wraps, because a wrapped row is the verbosity T9 reported.
 */
@Composable
private fun ActivityLine(
    @DrawableRes glyph: Int,
    label: String,
    detail: String?,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Design.Activity.rowHeight)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(glyph),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(4.dp),
        )
        Text(
            text = label,
            fontSize = Design.Interface.captionSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (detail.isNullOrEmpty()) Modifier.weight(1f) else Modifier.weight(1f, fill = false),
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (!detail.isNullOrEmpty()) {
            Text(
                text = detail,
                fontSize = Design.Interface.microSize,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(2f),
            )
        }
        trailing()
    }
}

@Composable
private fun ToolState(status: ToolRunStatus, detail: String?) {
    when (status) {
        // A running tool says so in a word, because the row has no spinner of its own.
        ToolRunStatus.Running -> Text(
            text = stringResource(R.string.activity_status_running),
            fontSize = Design.Interface.microSize,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ToolRunStatus.Done -> Text(
            text = stringResource(R.string.activity_tool_done),
            fontSize = Design.Interface.microSize,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF3F8F4B),
        )

        // The design's own failed row says the gateway's reason (`exit 4`), on one line.
        ToolRunStatus.Failed -> Text(
            text = detail?.lineSequence()?.firstOrNull()?.take(32) ?: stringResource(R.string.activity_tool_failed),
            fontSize = Design.Interface.microSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** The tool's glyph, by name, from the WebUI's own drawings rather than a Unicode stand-in. */
@DrawableRes
private fun activityGlyph(toolName: String): Int = when (toolName) {
    "run_command", "exec", "shell", "run_shell", "execute_command" -> R.drawable.ic_webui_terminal
    "web_search", "search", "x_search" -> R.drawable.ic_webui_globe
    "edit_file", "write_file", "apply_patch" -> R.drawable.ic_webui_compact
    else -> R.drawable.ic_webui_activity
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
