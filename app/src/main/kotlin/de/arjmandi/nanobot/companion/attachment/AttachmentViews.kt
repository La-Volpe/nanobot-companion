package de.arjmandi.nanobot.companion.attachment

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.ui.Design

/**
 * The composer's attachment cards (plan.md A6): an image grid that opens a lightbox, a video card
 * and file cards. Audio has no player in chat, by the design's own limit, so it is a file card.
 */
@Composable
fun AttachmentStrip(
    model: AttachmentViewModel,
    thumbnails: Map<String, ImageBitmap> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        model.lastRejection?.let { reason ->
            Text(
                text = rejectionCopy(reason, model.limits),
                fontSize = Design.Interface.microSize,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("attach.rejection"),
            )
        }
        if (model.images.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("attach.images")) {
                model.images.forEachIndexed { index, image ->
                    val bitmap = thumbnails[image.id]
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .clip(RoundedCornerShape(Design.Radius.field))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { model.openLightbox(index) }
                            .testTag("attach.image"),
                    ) {
                        if (bitmap != null) {
                            Image(bitmap = bitmap, contentDescription = image.name, contentScale = ContentScale.Crop)
                        } else {
                            Text(
                                text = image.kind.face,
                                fontSize = Design.Interface.microSize,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                        // The remove affordance is the tile's corner, not the tile: a tap in the
                        // middle opens the lightbox, which is what a thumbnail is for.
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(28.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(Design.Radius.field))
                                .clickable { model.remove(image.id) }
                                .testTag("attach.remove"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "\u00d7", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        model.videos.forEach { video ->
            CardRow(
                face = video.kind.face,
                title = video.name,
                subtitle = stringResource(R.string.attach_video_card, video.sizeLabel),
                onRemove = { model.remove(video.id) },
                tag = "attach.video",
            )
        }
        model.files.forEach { file ->
            CardRow(
                face = file.kind.face,
                title = file.name,
                subtitle = file.sizeLabel,
                onRemove = { model.remove(file.id) },
                tag = "attach.file",
            )
        }
    }
}

@Composable
private fun CardRow(face: String, title: String, subtitle: String, onRemove: () -> Unit, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Design.minimumTarget)
            .clip(RoundedCornerShape(Design.Radius.field))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = face, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = Design.Interface.captionSize,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(text = subtitle, fontSize = Design.Interface.microSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onRemove, modifier = Modifier.testTag("attach.remove")) {
            Text(stringResource(R.string.attach_remove), fontSize = Design.Interface.microSize)
        }
    }
}

/** The attach sheet's rows: the real limits, then the two pickers the platform offers. */
@Composable
fun AttachSheetContent(
    model: AttachmentViewModel,
    onPickPhotos: () -> Unit,
    onPickFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = model.limits.sheetSubtitle,
            fontSize = Design.Interface.microSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp).testTag("attach.limits"),
        )
        TextButton(onClick = onPickPhotos, modifier = Modifier.fillMaxWidth().heightIn(min = Design.minimumTarget).testTag("attach.photos")) {
            Text(stringResource(R.string.attach_photos), fontSize = Design.Interface.bodySize)
        }
        TextButton(onClick = onPickFiles, modifier = Modifier.fillMaxWidth().heightIn(min = Design.minimumTarget).testTag("attach.files")) {
            Text(stringResource(R.string.attach_files), fontSize = Design.Interface.bodySize)
        }
        model.lastRejection?.let { reason ->
            Text(
                text = rejectionCopy(reason, model.limits),
                fontSize = Design.Interface.microSize,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The lightbox: the image, the design's zoom target, and a tap anywhere to close. */
@Composable
fun Lightbox(image: PendingAttachment?, bitmap: ImageBitmap?, onClose: () -> Unit) {
    val current = image ?: return
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp)
                .clip(RoundedCornerShape(Design.Radius.panel))
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClose)
                .padding(12.dp)
                .testTag("attach.lightbox"),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap, contentDescription = current.name, contentScale = ContentScale.Fit)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = current.name, fontSize = Design.Interface.bodySize)
                    Text(
                        text = current.sizeLabel,
                        fontSize = Design.Interface.microSize,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A file card's second line says why it is there, because the app has no audio player in chat. */
@Composable
fun rejectionCopy(reason: AttachmentReject, limits: UploadLimits): String = stringResource(
    when (reason) {
        AttachmentReject.Empty -> R.string.attach_empty
        AttachmentReject.TooMany -> R.string.attach_tooMany
        AttachmentReject.FileTooLarge -> R.string.attach_fileTooLarge
        AttachmentReject.TotalTooLarge -> R.string.attach_totalTooLarge
        AttachmentReject.TooManyVideos -> R.string.attach_tooManyVideos
        AttachmentReject.VideoTooLarge -> R.string.attach_videoTooLarge
        AttachmentReject.Unsupported -> R.string.attach_unsupported
        AttachmentReject.Unreachable -> R.string.attach_unreachable
    },
    limits.maxCount,
    UploadLimits.megabytes(limits.maxFileBytes),
    UploadLimits.megabytes(limits.maxTotalBytes),
    UploadLimits.megabytes(UploadLimits.MAX_VIDEO_BYTES),
)
