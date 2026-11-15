package de.arjmandi.nanobot.companion.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.ui.Design
import de.arjmandi.nanobot.companion.ui.scaledReadingStyle

/**
 * The markdown an answer draws (plan.md A5, D6/D7): prose, bullets, tables that scroll inside
 * their own box, and code with a language chip, line numbers on a multiline block and Copy with
 * the design's 1.5 s confirmation. v1's subset: no math, no diagrams, which is the design's own
 * line.
 */
@Composable
fun MarkdownBlocks(
    blocks: List<MdBlock>,
    idPrefix: String,
    isCopied: (String) -> Boolean,
    onCopy: (blockId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            BlockView(block = block, blockId = "$idPrefix-$index", isCopied = isCopied, onCopy = onCopy)
        }
    }
}

@Composable
private fun BlockView(block: MdBlock, blockId: String, isCopied: (String) -> Boolean, onCopy: (String) -> Unit) {
    when (block) {
        is MdBlock.Heading -> Text(
            text = inlineText(block.inlines),
            style = scaledReadingStyle().copy(
                fontSize = when (block.level) {
                    1 -> 22.sp
                    2 -> 19.sp
                    else -> 17.sp
                },
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        is MdBlock.Paragraph -> Text(
            text = inlineText(block.inlines),
            style = scaledReadingStyle(),
            modifier = Modifier.fillMaxWidth().testTag("markdown.paragraph"),
        )

        is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            block.items.forEachIndexed { index, item ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(modifier = Modifier.width((item.depth * 16).dp))
                    val box = item.checked
                    if (box != null) {
                        TaskBox(checked = box)
                    } else {
                        Text(
                            text = if (block.ordered) "${block.start + index}." else "\u2022",
                            style = scaledReadingStyle(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = inlineText(item.inlines),
                        style = scaledReadingStyle(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        is MdBlock.Code -> CodeBlock(block = block, blockId = blockId, copied = isCopied(blockId), onCopy = { onCopy(blockId) })

        is MdBlock.Table -> TableBlock(block)

        is MdBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(
                modifier = Modifier
                    .width(2.dp)
                    .heightIn(min = 18.dp)
                    .background(MaterialTheme.colorScheme.outline),
            )
            Column(
                modifier = Modifier.padding(start = 10.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                block.blocks.forEachIndexed { index, inner ->
                    BlockView(inner, "$blockId-quote-$index", isCopied, onCopy)
                }
            }
        }

        MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * The WebUI's `emerald-500`: the ring and fill of a task box (`border-emerald-500 bg-emerald-500`,
 * with a white tick, in both themes). iOS draws the same value from the same place.
 */
private val TaskBoxFill = Color(0xFF10B981)

/**
 * The gutter of a task item, drawn as the WebUI draws it: a dashed ring that fills solid, with a
 * tick, once the box is checked (`MarkdownTextRenderer`'s checkbox span).
 */
@Composable
private fun TaskBox(checked: Boolean) {
    val ring = if (checked) TaskBoxFill else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .padding(top = 3.dp)
            .size(14.dp)
            .clip(CircleShape)
            .background(if (checked) ring else Color.Transparent)
            // The WebUI's unchecked box is a dashed ring; a checked one is solid.
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawCircle(
                    color = ring,
                    radius = size.minDimension / 2 - stroke / 2,
                    style = Stroke(
                        width = stroke,
                        pathEffect = if (checked) null else PathEffect.dashPathEffect(
                            floatArrayOf(with(density) { 2.dp.toPx() }, with(density) { 1.5.dp.toPx() })
                        )
                    ),
                )
            }
            .testTag(if (checked) "markdown.task.done" else "markdown.task.open"),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                painter = painterResource(R.drawable.ic_webui_check),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(9.dp),
            )
        }
    }
}

/**
 * A fence: the language chip and Copy sit above the code, and a block with more than one line is
 * numbered. Copy is there while the fence is still arriving too, because that is the state a
 * diagram's source is in for the whole turn: the source is readable and worth taking before the
 * turn ends.
 */
@Composable
private fun CodeBlock(block: MdBlock.Code, blockId: String, copied: Boolean, onCopy: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Design.Radius.field))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .testTag("markdown.code"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = block.language ?: stringResource(R.string.markdown_code),
                fontSize = Design.Interface.microSize,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp).testTag("markdown.code.language"),
            )
            TextButton(onClick = onCopy, modifier = Modifier.heightIn(min = Design.minimumTarget)) {
                Text(
                    text = stringResource(if (copied) R.string.markdown_copied else R.string.markdown_copy),
                    fontSize = Design.Interface.microSize,
                )
            }
        }
        val numbered = block.lines.size > 1
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp)
                .padding(bottom = 10.dp),
        ) {
            block.lines.forEachIndexed { index, line ->
                Row {
                    if (numbered) {
                        Text(
                            text = "${index + 1}",
                            fontSize = Design.Activity.diffSize,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(24.dp).testTag("markdown.code.lineNumber"),
                        )
                    }
                    Text(text = line.ifEmpty { " " }, fontSize = Design.Activity.diffSize, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

/** A limit table is wider than a phone, so it scrolls inside its own box and the page does not. */
@Composable
private fun TableBlock(block: MdBlock.Table) {
    val rows = listOf(block.header) + block.rows
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Design.Radius.field))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .horizontalScroll(rememberScrollState())
            .testTag("markdown.table"),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEachIndexed { index, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { cell ->
                        Text(
                            text = inlineText(cell),
                            fontSize = Design.Interface.captionSize,
                            fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.width(140.dp),
                        )
                    }
                }
                if (index == 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/** Inlines keep their emphasis, their code face and their link colour, but not a tap target. */
@Composable
fun inlineText(inlines: List<MdInline>): AnnotatedString {
    val codeColour: Color = MaterialTheme.colorScheme.onSurfaceVariant
    val linkColour: Color = MaterialTheme.colorScheme.primary
    return buildAnnotatedString { appendInlines(inlines, codeColour, linkColour) }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendInlines(
    inlines: List<MdInline>,
    codeColour: Color,
    linkColour: Color,
) {
    inlines.forEach { inline ->
        when (inline) {
            is MdInline.Text -> append(inline.text)
            is MdInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColour)) { append(inline.text) }
            is MdInline.Emphasis -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                appendInlines(inline.inlines, codeColour, linkColour)
            }

            is MdInline.Strong -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                appendInlines(inline.inlines, codeColour, linkColour)
            }

            is MdInline.Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                appendInlines(inline.inlines, codeColour, linkColour)
            }

            is MdInline.Link -> withStyle(SpanStyle(color = linkColour)) {
                appendInlines(inline.inlines, codeColour, linkColour)
            }
        }
    }
}
