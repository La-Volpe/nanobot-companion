package de.arjmandi.nanobot.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The placeholder shapes a screen draws while a read is in flight, so "not loaded yet" never reads
 * as "nothing here" (the WebUI's `StartupSkeleton`/`ThreadLoadingSkeleton` draw the same idea).
 *
 * Static on purpose: a shimmer is an infinite animation, and an infinite animation makes every
 * Compose test that waits for the frame clock to go idle wait forever. The shape says "coming";
 * the animation is not worth a flaky suite.
 */
@Composable
fun SkeletonLine(
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f,
    height: Dp = 12.dp,
    shape: RoundedCornerShape = RoundedCornerShape(4.dp),
) {
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(shape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
    )
}

/**
 * `count` rows at the size of the real ones: a surface with two lines in it, which is what a
 * drawer row and a settings row both are. [label] is what a screen reader announces instead of
 * the shapes, so the state is audible as well as visible.
 */
@Composable
fun SkeletonRows(
    count: Int,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 44.dp,
    spacing: Dp = 6.dp,
    label: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .testTag("skeleton"),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        repeat(count) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .clip(RoundedCornerShape(Design.Radius.field))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SkeletonLine(widthFraction = 0.7f, height = 10.dp)
                SkeletonLine(widthFraction = 0.45f, height = 8.dp)
            }
        }
    }
}
