package de.arjmandi.nanobot.companion.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.arjmandi.nanobot.companion.R
import de.arjmandi.nanobot.companion.activity.ActivityClusterRow
import de.arjmandi.nanobot.companion.attachment.AttachSheetContent
import de.arjmandi.nanobot.companion.attachment.AttachmentStrip
import de.arjmandi.nanobot.companion.attachment.Lightbox
import de.arjmandi.nanobot.companion.attachment.PickedAttachment
import de.arjmandi.nanobot.companion.composer.ChatModelPreset
import de.arjmandi.nanobot.companion.composer.ComposerMetaRow
import de.arjmandi.nanobot.companion.composer.ComposerPrimaryButton
import de.arjmandi.nanobot.companion.composer.ComposerSheetHost
import de.arjmandi.nanobot.companion.composer.QueueChip
import de.arjmandi.nanobot.companion.markdown.MarkdownBlocks
import de.arjmandi.nanobot.companion.states.ConnectionBannerRow
import de.arjmandi.nanobot.companion.states.GoalStripRow
import de.arjmandi.nanobot.companion.states.StateRowView
import de.arjmandi.nanobot.companion.states.SubagentDockRow
import de.arjmandi.nanobot.companion.states.snackbarActionLabel
import de.arjmandi.nanobot.companion.states.snackbarText
import de.arjmandi.nanobot.companion.ui.Design
import de.arjmandi.nanobot.companion.ui.SkeletonLine
import de.arjmandi.nanobot.companion.ui.scaledReadingStyle
import de.arjmandi.nanobot.companion.voice.VoiceMeter
import de.arjmandi.nanobot.companion.voice.VoiceNoticeRow
import de.arjmandi.nanobot.companion.voice.VoiceViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The thread (plan.md A3, A4, A5): the history the core replayed, the activity cluster per turn,
 * the answer as the core parsed it and as it streams, and the composer row whose one button the
 * model decides. Everything on this screen comes from the core's own thread, so a replayed turn
 * and a live one look the same (D9).
 */
@Composable
fun ChatScreen(
    model: ChatViewModel,
    sessionTitle: String = "nanobot",
    sessionChatId: String? = null,
    loadPresets: suspend () -> List<ChatModelPreset>? = { emptyList() },
    modifier: Modifier = Modifier,
) {
    val thread by model.thread.collectAsStateWithLifecycle()
    val composer = model.composer
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Copy is the platform's clipboard, not the core's: the block's text is what lands there.
    val clipboard = LocalContext.current.getSystemService(android.content.ClipboardManager::class.java)
    var jumpToTurn by remember { mutableStateOf<String?>(null) }

    // The WebUI's rule (ThreadViewport.tsx:1002, thread-motion.ts:353), which the owner reported
    // the phone did not follow: the tail stays in view only while the reader is at it. A reader who
    // scrolled up is left alone, and coming back to the end resumes following.
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(listState) {
        // A finger on the list is the reader's: where the finger leaves it decides whether the
        // stream follows. This is the web's scroll listener, not a layout observer.
        snapshotFlow { dragging to pixelsFromEnd(listState) }
            .collect { (isDragging, distance) -> if (isDragging) model.scrolled(distance) }
    }
    LaunchedEffect(listState) {
        // A fling or a jump settles too, and where it settled is what decides.
        snapshotFlow { listState.isScrollInProgress }.distinctUntilChanged().collect { scrolling ->
            if (!scrolling) model.scrolled(pixelsFromEnd(listState))
        }
    }
    LaunchedEffect(listState) {
        // New content leaves the end further down: catch up by exactly that gap, and only for a
        // reader who is following. Measured after layout — measuring before it reads the previous
        // frame's zero and never scrolls at all, which is the bug this replaced.
        snapshotFlow {
            Triple(pixelsFromEnd(listState), dragging, listState.isScrollInProgress)
        }.collect { (distance, isDragging, scrolling) ->
            if (!isDragging && !scrolling && model.viewport.followsNewContent() && distance in 1 until Int.MAX_VALUE) {
                listState.scrollBy(distance.toFloat())
            }
        }
    }
    // A chat opens at its own end, which is where following starts (the web's pending-conversation
    // scroll); keyed by the first turn, so it happens once per conversation, not per delta.
    LaunchedEffect(thread.turns.firstOrNull()?.id) {
        if (thread.turns.isNotEmpty()) {
            model.jumpToLatest()
            listState.scrollToItem(thread.turns.lastIndex)
        }
    }
    // The clusters are a function of the thread, and the fold rules are the model's (A4): this
    // only hands it the value the screen already collects.
    LaunchedEffect(thread) { model.onThread(thread) }
    // The message-list sheet's pick is a scroll, done here because the list is the screen's.
    LaunchedEffect(jumpToTurn) {
        val target = jumpToTurn ?: return@LaunchedEffect
        val index = thread.turns.indexOfFirst { it.id == target }
        if (index >= 0) listState.animateScrollToItem(index)
        jumpToTurn = null
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).imePadding()) {
        model.states.banner?.let { banner ->
            ConnectionBannerRow(banner = banner, onRetry = { model.states.retryConnection() })
        }
        when (model.placeholder) {
            // The page read is in flight: the shape of a conversation, not "nothing here yet"
            // (the WebUI's `ThreadLoadingSkeleton` draws the same).
            ThreadPlaceholder.Skeleton -> ThreadSkeleton(modifier = Modifier.weight(1f))
            ThreadPlaceholder.Failed -> ThreadReadFailed(modifier = Modifier.weight(1f))
            ThreadPlaceholder.Empty -> EmptyThread(modifier = Modifier.weight(1f))
            ThreadPlaceholder.None -> Unit
        }
        if (thread.turns.isNotEmpty()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat.thread"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            ) {
                items(thread.turns, key = { it.id }) { turn ->
                    TurnRow(
                        turn = turn,
                        cluster = model.activity.cluster(turn.id),
                        onToggleActivity = { model.activity.toggle(turn.id) },
                        onToggleDetails = { model.activity.toggleDetails(it) },
                        isCopied = composer::isCopied,
                        onCopy = { blockId, plain ->
                            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("answer", plain))
                            composer.copy(blockId)
                        },
                    )
                }
            }
        }
        // A notice is a row in the transcript, and never a banner (A8's one-owner rule). It sits
        // above the composer rather than inside the list, so a notice still shows on an empty thread.
        model.states.rows.forEach { row ->
            StateRowView(row = row, onAction = { model.states.perform(it, row) })
        }

        ComposerZone(
            model = model,
            turns = thread.turns,
            sessionTitle = sessionTitle,
            sessionChatId = sessionChatId,
            loadPresets = loadPresets,
            onJumpToTurn = { jumpToTurn = it },
        )
    }
}

/** The composer zone: the queued chip, the meta row, the field and the one button (A5). */
@Composable
private fun ComposerZone(
    model: ChatViewModel,
    turns: List<ChatTurnView>,
    sessionTitle: String,
    sessionChatId: String?,
    loadPresets: suspend () -> List<ChatModelPreset>?,
    onJumpToTurn: (String) -> Unit,
) {
    val composer = model.composer
    val attachments = model.attachments
    val voice = model.voice
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // The pickers are the platform's: the photo picker for images, the document one for files,
    // and both hand their bytes to the core, which is the only thing that decides what is too big.
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        uris.forEach { uri -> scope.launch { model.attachments.add(context.picked(uri)) } }
        composer.closeSheet()
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch { model.attachments.add(context.picked(it)) } }
        composer.closeSheet()
    }
    // A recording is a permission first and a gesture second, so the ask happens before the hold.
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The transient line sits above the composer, which is where the design's Android column
        // puts feedback; the dock and the goal strip sit there too, because they are composer-zone
        // states rather than transcript rows.
        model.states.snackbar?.let { snackbar ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Design.minimumTarget)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Design.Radius.field))
                    .padding(horizontal = 10.dp)
                    .testTag("states.snackbar"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = snackbarText(snackbar),
                    fontSize = Design.Interface.microSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                snackbarActionLabel(snackbar)?.let { label ->
                    TextButton(
                        onClick = { model.states.performSnackbarAction() },
                        modifier = Modifier.heightIn(min = Design.minimumTarget).testTag("states.snackbar.action"),
                    ) {
                        Text(label, fontSize = Design.Interface.microSize)
                    }
                }
            }
        }
        SubagentDockRow(rows = model.states.dock, onStop = { id -> scope.launch { model.cancelSubagent(id) } })
        model.states.goalStrip?.let { goal -> GoalStripRow(text = goal) }
        if (attachments.attachments.isNotEmpty()) {
            AttachmentStrip(model = attachments)
        }
        VoiceMeter(model = voice)
        VoiceNoticeRow(notice = voice.notice)
        composer.snapshot.queued?.takeIf { it.isNotEmpty() }?.let { queued ->
            QueueChip(
                text = queued,
                onSendNow = { scope.launch { composer.sendQueuedNow() } },
                onDelete = { composer.deleteQueued() },
            )
        }
        ComposerMetaRow(
            composer = composer,
            onOpenAccess = { composer.openAccessSheet() },
            onOpenUsage = { composer.openUsageSheet() },
            onOpenModel = { composer.openModelSheet() },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = { composer.openAttachSheet() },
                modifier = Modifier.size(Design.minimumTarget).testTag("composer.attach"),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_webui_attach),
                    contentDescription = stringResource(R.string.composer_attach_title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            OutlinedTextField(
                value = composer.draft,
                onValueChange = { text ->
                    composer.draft = text
                    scope.launch { composer.refreshPalette() }
                },
                placeholder = { Text(stringResource(R.string.chat_composer_hint), fontSize = Design.Interface.bodySize) },
                maxLines = 5,
                shape = RoundedCornerShape(Design.Radius.bubble),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { scope.launch { model.primaryAction() } }),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Design.minimumTarget)
                    .testTag("chat.composer"),
            )
            val primary = composer.primaryState(recording = voice.phase == VoiceViewModel.Phase.Recording)
            val now = { System.currentTimeMillis() }
            val beginRecording = {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    voice.beginHold(now())
                } else {
                    microphone.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.pointerInput(primary) {
                    if (primary != de.arjmandi.nanobot.companion.composer.PrimaryState.Voice) return@pointerInput
                    // A hold records; a press shorter than the design's 300 ms arms tap-to-dictate
                    // and keeps recording, which is the artifact's own rule.
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val started = now()
                        beginRecording()
                        waitForUpOrCancellation()
                        val released = now()
                        if (released - started < Design.tapThresholdMs) {
                            voice.armTapMode()
                        } else {
                            scope.launch {
                                voice.endHold(released)
                                model.appendTranscript()
                            }
                        }
                    }
                },
            ) {
                ComposerPrimaryButton(
                    state = primary,
                    onClick = {
                        scope.launch {
                            if (primary == de.arjmandi.nanobot.companion.composer.PrimaryState.Stop) {
                                if (voice.phase == VoiceViewModel.Phase.Recording) {
                                    voice.tap(now())
                                    model.appendTranscript()
                                } else {
                                    model.primaryAction()
                                }
                            } else {
                                model.primaryAction()
                            }
                        }
                    },
                )
            }
        }
    }

    ComposerSheetHost(
        composer = composer,
        turns = turns,
        sessionTitle = sessionTitle,
        sessionChatId = sessionChatId,
        loadPresets = loadPresets,
        onJumpToTurn = onJumpToTurn,
        onAttachContent = {
            AttachSheetContent(
                model = attachments,
                onPickPhotos = {
                    photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onPickFiles = { files.launch(arrayOf("*/*")) },
            )
        },
    )

    // The lightbox is a dialog over the thread, closed by a tap (design: the zooming gallery).
    Lightbox(
        image = attachments.lightboxImage,
        bitmap = null,
        onClose = { attachments.closeLightbox() },
    )
}

/** One turn: the prompt above, what nanobot did about it, then the answer. */
@Composable
private fun TurnRow(
    turn: ChatTurnView,
    cluster: de.arjmandi.nanobot.companion.activity.ActivityCluster?,
    onToggleActivity: () -> Unit,
    onToggleDetails: (String) -> Unit,
    isCopied: (String) -> Boolean,
    onCopy: (blockId: String, plain: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        turn.prompt?.let { prompt ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = prompt,
                        style = scaledReadingStyle(),
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(Design.Radius.bubble),
                            )
                            .padding(horizontal = 13.dp, vertical = 9.dp)
                            .testTag("chat.prompt"),
                    )
                    if (turn.promptPending) {
                        Text(
                            text = stringResource(R.string.chat_sending),
                            fontSize = Design.Interface.microSize,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("chat.pending"),
                        )
                    }
                }
            }
        }
        if (cluster != null) {
            ActivityClusterRow(cluster = cluster, onToggle = onToggleActivity, onToggleDetails = onToggleDetails)
        }
        if (turn.answer.isNotEmpty()) {
            MarkdownBlocks(
                blocks = turn.readingBlocks.blocks,
                idPrefix = turn.id,
                isCopied = isCopied,
                onCopy = { blockId -> onCopy(blockId, turn.answer) },
                modifier = Modifier.testTag("chat.answer"),
            )
            if (turn.answerStreaming) {
                Box(
                    modifier = Modifier
                        .size(width = 8.dp, height = 2.dp)
                        .background(MaterialTheme.colorScheme.onSurface)
                        .testTag("chat.streaming"),
                )
            }
        }
    }
}

/** The conversation that has not arrived: a bubble and two answers' worth of lines, at their sizes. */
@Composable
private fun ThreadSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("chat.skeleton"),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SkeletonLine(widthFraction = 0.4f, height = 40.dp, shape = RoundedCornerShape(Design.Radius.bubble))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SkeletonLine(widthFraction = 0.8f)
            SkeletonLine(widthFraction = 1f)
            SkeletonLine(widthFraction = 0.6f)
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SkeletonLine(widthFraction = 0.75f)
            SkeletonLine(widthFraction = 0.5f)
        }
    }
}

/** A page read that did not answer: the app's problem, said in the app's voice. */
@Composable
private fun ThreadReadFailed(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().testTag("chat.readFailed"),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.chat_history_failed), fontSize = Design.Interface.bodySize)
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

/**
 * A picked `content://` URI as the core wants it: its name, its type and its bytes. The gateway's
 * size check runs on the bytes, so this reads them once and no second copy is made.
 */
private fun Context.picked(uri: Uri): PickedAttachment {
    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: uri.lastPathSegment ?: "attachment"
    val mime = contentResolver.getType(uri) ?: "application/octet-stream"
    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
    return PickedAttachment(name = name, mimeType = mime, bytes = bytes)
}

/**
 * How far the end of the list is below the viewport, in pixels. A list scrolled away from its tail
 * reports [Int.MAX_VALUE] rather than a guess: the number is only ever compared with the web's
 * threshold (48 px), so a coarse answer is the honest one once a whole row is out of view.
 */
private fun pixelsFromEnd(state: LazyListState): Int {
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0
    if (last.index < info.totalItemsCount - 1) return Int.MAX_VALUE
    val viewportEnd = info.viewportEndOffset - info.afterContentPadding
    return (last.offset + last.size - viewportEnd).coerceAtLeast(0)
}
