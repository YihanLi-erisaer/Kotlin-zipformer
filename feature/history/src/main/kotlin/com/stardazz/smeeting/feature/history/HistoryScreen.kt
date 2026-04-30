package com.stardazz.smeeting.feature.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stardazz.smeeting.core.startup.LlmModelState
import com.stardazz.smeeting.domain.model.TranscriptionHistoryEntry
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onBack: () -> Unit,
) {
    val entries by viewModel.entries.collectAsState()
    val llmState by viewModel.llmState.collectAsState()
    val summarizingEntryId by viewModel.summarizingEntryId.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val view = LocalView.current
    val keepScreenOn =
        summarizingEntryId != null ||
            llmState is LlmModelState.Downloading ||
            llmState is LlmModelState.Loading
    DisposableEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose {
            view.keepScreenOn = false
        }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.history_copied)
    var revealedDeleteId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var deleteFromDetail by remember { mutableStateOf(false) }
    var selectedEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailMenuExpanded by remember { mutableStateOf(false) }
    var showDeleteLlmModelDialog by remember { mutableStateOf(false) }
    var showDeleteAudioDialog by remember { mutableStateOf(false) }
    var expandBias by remember { mutableFloatStateOf(0f) }
    var contentCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val listState = rememberLazyListState()
    val selectedEntry = entries.firstOrNull { it.id == selectedEntryId }
    val isShowingDetail = selectedEntry != null
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingEntryId by remember { mutableStateOf<String?>(null) }
    var playbackPositionMs by remember { mutableStateOf(0L) }
    var playbackDurationMs by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    LaunchedEffect(playingEntryId, mediaPlayer) {
        while (playingEntryId != null && mediaPlayer != null) {
            val mp = mediaPlayer ?: break
            playbackPositionMs = mp.currentPosition.toLong().coerceAtLeast(0L)
            val duration = mp.duration.toLong()
            playbackDurationMs = if (duration > 0L) duration else playbackDurationMs
            delay(120L)
        }
    }

    LaunchedEffect(selectedEntry?.id, selectedEntry?.audioFilePath) {
        val path = selectedEntry?.audioFilePath
        if (path.isNullOrEmpty()) {
            playbackDurationMs = 0L
            playbackPositionMs = 0L
            return@LaunchedEffect
        }
        if (playingEntryId != selectedEntry?.id) {
            playbackPositionMs = 0L
        }
        playbackDurationMs = runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(path)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durationStr?.toLongOrNull() ?: 0L
        }.getOrDefault(0L).coerceAtLeast(0L)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.history_title))
                },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            detailMenuExpanded = false
                            if (isShowingDetail) {
                                viewModel.cancelSummarize()
                                selectedEntryId = null
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Text("← ${stringResource(R.string.back)}")
                    }
                },
                actions = {
                    if (selectedEntry != null) {
                        Box {
                            IconButton(onClick = { detailMenuExpanded = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.history_title),
                                )
                            }
                            DropdownMenu(
                                expanded = detailMenuExpanded,
                                onDismissRequest = { detailMenuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.history_copy)) },
                                    onClick = {
                                        detailMenuExpanded = false
                                        copyToClipboard(context, selectedEntry.text)
                                        scope.launch {
                                            snackbarHostState.showSnackbar(copiedMessage)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.history_delete),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        detailMenuExpanded = false
                                        pendingDeleteId = selectedEntry.id
                                        deleteFromDetail = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(R.string.summary_delete_model),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    onClick = {
                                        detailMenuExpanded = false
                                        showDeleteLlmModelDialog = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        AnimatedContent(
            targetState = selectedEntry,
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { contentCoordinates = it },
            transitionSpec = {
                val anchor = BiasAlignment.Vertical(expandBias)
                if (targetState != null) {
                    (fadeIn(tween(300)) + expandVertically(tween(350), expandFrom = anchor))
                        .togetherWith(fadeOut(tween(250)) + shrinkVertically(tween(350), shrinkTowards = anchor))
                } else {
                    (fadeIn(tween(300)) + expandVertically(tween(350), expandFrom = anchor))
                        .togetherWith(fadeOut(tween(250)) + shrinkVertically(tween(350), shrinkTowards = anchor))
                }.using(SizeTransform(clip = false))
            },
            label = "history_content_transition",
        ) { entry ->
            if (entry != null) {
                HistoryEntryDetail(
                    item = entry,
                    contentPadding = padding,
                    llmState = llmState,
                    isSummarizing = summarizingEntryId == entry.id,
                    streamingText = if (summarizingEntryId == entry.id) streamingText else "",
                    onSummarize = { viewModel.summarize(entry) },
                    onCancelSummarize = { viewModel.cancelSummarize() },
                    onDownloadModel = { viewModel.downloadLlmModel(context) },
                    onCancelDownload = { viewModel.cancelLlmDownload() },
                    onCopySummary = { text ->
                        copyToClipboard(context, text)
                        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                    },
                    isAudioPlaying = playingEntryId == entry.id,
                    playbackPositionMs = playbackPositionMs,
                    playbackDurationMs = playbackDurationMs,
                    onToggleAudio = {
                        val audioPath = entry.audioFilePath
                        if (!audioPath.isNullOrEmpty()) {
                            val audioFile = File(audioPath)
                            if (!audioFile.exists()) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.history_audio_missing))
                                }
                            } else if (playingEntryId == entry.id) {
                                mediaPlayer?.pause()
                                playingEntryId = null
                            } else {
                                mediaPlayer?.release()
                                mediaPlayer = runCatching {
                                    MediaPlayer().apply {
                                        setDataSource(audioPath)
                                        setOnCompletionListener {
                                            playingEntryId = null
                                            playbackPositionMs = 0L
                                        }
                                        prepare()
                                        if (playbackPositionMs > 0L) {
                                            seekTo(playbackPositionMs.toInt().coerceAtMost(duration))
                                        }
                                        start()
                                    }
                                }.onFailure {
                                    scope.launch {
                                        snackbarHostState.showSnackbar(
                                            context.getString(R.string.history_audio_play_failed),
                                        )
                                    }
                                }.getOrNull()
                                if (mediaPlayer != null) {
                                    playingEntryId = entry.id
                                    playbackDurationMs = mediaPlayer?.duration?.toLong()?.coerceAtLeast(0L) ?: playbackDurationMs
                                } else {
                                    playingEntryId = null
                                }
                            }
                        }
                    },
                    onSeekAudio = { progress ->
                        val duration = playbackDurationMs
                        if (duration <= 0L) return@HistoryEntryDetail
                        val target = (duration * progress.coerceIn(0f, 1f)).toInt()
                        playbackPositionMs = target.toLong()
                        mediaPlayer?.seekTo(target)
                    },
                    onDeleteAudio = {
                        showDeleteAudioDialog = true
                    },
                )
            } else if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = entries,
                        key = { it.id },
                    ) { item ->
                        HistoryEntryRow(
                            item = item,
                            showDelete = revealedDeleteId == item.id,
                            onOpenDetail = { rowCenterY ->
                                revealedDeleteId = null
                                val cc = contentCoordinates
                                if (cc != null && cc.isAttached) {
                                    val top = cc.positionInRoot().y
                                    val height = cc.size.height.toFloat()
                                    if (height > 0f) {
                                        val fraction = ((rowCenterY - top) / height).coerceIn(0f, 1f)
                                        expandBias = fraction * 2f - 1f
                                    }
                                }
                                selectedEntryId = item.id
                            },
                            onRevealDelete = { revealedDeleteId = item.id },
                            onCollapseDelete = { revealedDeleteId = null },
                            onCopy = {
                                copyToClipboard(context, item.text)
                                scope.launch {
                                    snackbarHostState.showSnackbar(copiedMessage)
                                }
                            },
                            onDelete = {
                                pendingDeleteId = item.id
                                deleteFromDetail = false
                            },
                        )
                    }
                }
            }
        }
    }

    if (pendingDeleteId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.history_delete_confirm_title)) },
            text = { Text(stringResource(R.string.history_delete_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        val id = pendingDeleteId!!
                        viewModel.deleteEntry(id)
                        revealedDeleteId = null
                        if (deleteFromDetail) {
                            selectedEntryId = null
                        }
                        pendingDeleteId = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(R.string.history_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.history_delete_cancel))
                }
            },
        )
    }

    if (showDeleteLlmModelDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteLlmModelDialog = false },
            title = { Text(stringResource(R.string.summary_delete_model_title)) },
            text = { Text(stringResource(R.string.summary_delete_model_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteLlmModelDialog = false
                        viewModel.deleteLlmModelFiles(context)
                    },
                ) {
                    Text(
                        stringResource(R.string.summary_delete_model_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteLlmModelDialog = false }) {
                    Text(stringResource(R.string.history_delete_cancel))
                }
            },
        )
    }

    if (showDeleteAudioDialog && selectedEntry != null) {
        AlertDialog(
            onDismissRequest = { showDeleteAudioDialog = false },
            title = { Text(stringResource(R.string.history_audio_delete_confirm_title)) },
            text = { Text(stringResource(R.string.history_audio_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (playingEntryId == selectedEntry.id) {
                            mediaPlayer?.stop()
                            mediaPlayer?.release()
                            mediaPlayer = null
                            playingEntryId = null
                            playbackPositionMs = 0L
                            playbackDurationMs = 0L
                        }
                        viewModel.deleteAudio(selectedEntry.id)
                        showDeleteAudioDialog = false
                    },
                ) {
                    Text(
                        stringResource(R.string.history_audio_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAudioDialog = false }) {
                    Text(stringResource(R.string.history_delete_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryEntryRow(
    item: TranscriptionHistoryEntry,
    showDelete: Boolean,
    onOpenDetail: (centerYInRoot: Float) -> Unit,
    onRevealDelete: () -> Unit,
    onCollapseDelete: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    var rowCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowCoordinates = it }
            .combinedClickable(
                onClick = {
                    if (showDelete) {
                        onCollapseDelete()
                    } else {
                        val coords = rowCoordinates
                        if (coords != null && coords.isAttached) {
                            val bounds = coords.boundsInRoot()
                            onOpenDetail((bounds.top + bounds.bottom) / 2f)
                        } else {
                            onOpenDetail(0f)
                        }
                    }
                },
                onLongClick = {
                    if (showDelete) onCollapseDelete() else onRevealDelete()
                },
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatHistoryTime(item.createdAtMillis),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.history_copy))
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = showDelete,
                enter = fadeIn(tween(200)),
                exit = fadeOut(tween(200)),
                modifier = Modifier.matchParentSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                            shape = MaterialTheme.shapes.medium,
                        )
                        .clickable { onCollapseDelete() },
                    contentAlignment = Alignment.Center,
                ) {
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text(stringResource(R.string.history_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEntryDetail(
    item: TranscriptionHistoryEntry,
    contentPadding: PaddingValues,
    llmState: LlmModelState,
    isSummarizing: Boolean,
    streamingText: String,
    onSummarize: () -> Unit,
    onCancelSummarize: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onCopySummary: (String) -> Unit,
    isAudioPlaying: Boolean,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    onToggleAudio: () -> Unit,
    onSeekAudio: (Float) -> Unit,
    onDeleteAudio: () -> Unit,
) {
    val scrollState = rememberScrollState()
    var shouldAutoScroll by remember { mutableStateOf(true) }
    val generatingLabel = stringResource(R.string.summary_generating)
    val rawSummaryText: String? = when {
        isSummarizing && streamingText.isNotEmpty() -> streamingText
        !isSummarizing && !item.summary.isNullOrEmpty() -> item.summary
        else -> null
    }
    val showSummarySection =
        rawSummaryText != null || (isSummarizing && streamingText.isEmpty())

    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value to scrollState.maxValue }
            .collect { (value, max) ->
                shouldAutoScroll = (max - value) <= AUTO_SCROLL_BOTTOM_THRESHOLD_PX
            }
    }

    LaunchedEffect(isSummarizing, streamingText) {
        if (!isSummarizing) return@LaunchedEffect
        if (!shouldAutoScroll) return@LaunchedEffect
        withFrameNanos { }
        scrollState.scrollTo(scrollState.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = formatHistoryTime(item.createdAtMillis),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(16.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                if (showSummarySection) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.summary_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    when {
                        rawSummaryText != null -> {
                            SelectionContainer {
                                Text(
                                    text = rawSummaryText,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                        else -> {
                            Text(
                                text = generatingLabel,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (isSummarizing) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        SummarizeActionBar(
            llmState = llmState,
            isSummarizing = isSummarizing,
            hasSummary = !item.summary.isNullOrEmpty(),
            onSummarize = onSummarize,
            onCancelSummarize = onCancelSummarize,
            onDownloadModel = onDownloadModel,
            onCancelDownload = onCancelDownload,
            onCopySummary = {
                val raw = when {
                    isSummarizing && streamingText.isNotEmpty() -> streamingText
                    !item.summary.isNullOrEmpty() -> item.summary
                    else -> null
                }
                if (!raw.isNullOrEmpty()) {
                    onCopySummary(AiSummaryThinkingParser.copyPlainText(raw))
                }
            },
        )

        if (!item.audioFilePath.isNullOrEmpty()) {
            val duration = playbackDurationMs.coerceAtLeast(0L)
            val position = playbackPositionMs.coerceIn(0L, duration.takeIf { it > 0L } ?: 0L)
            val progress = if (duration > 0L) position.toFloat() / duration.toFloat() else 0f
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Slider(
                    value = progress.coerceIn(0f, 1f),
                    onValueChange = onSeekAudio,
                    valueRange = 0f..1f,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatPlaybackTime(position),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatPlaybackTime(duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onToggleAudio,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (isAudioPlaying) stringResource(R.string.history_audio_pause)
                        else stringResource(R.string.history_audio_play),
                    )
                }
                OutlinedButton(
                    onClick = onDeleteAudio,
                ) {
                    Text(
                        stringResource(R.string.history_audio_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummarizeActionBar(
    llmState: LlmModelState,
    isSummarizing: Boolean,
    hasSummary: Boolean,
    onSummarize: () -> Unit,
    onCancelSummarize: () -> Unit,
    onDownloadModel: () -> Unit,
    onCancelDownload: () -> Unit,
    onCopySummary: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            isSummarizing -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.summary_generating),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onCancelSummarize) {
                    Text(stringResource(R.string.summary_cancel))
                }
            }
            llmState is LlmModelState.NotDownloaded || llmState is LlmModelState.Error -> {
                Button(onClick = onDownloadModel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.summary_download_model))
                }
            }
            llmState is LlmModelState.Downloading -> {
                val progress = llmState.progress
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.summary_downloading, (progress * 100).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedButton(onClick = onCancelDownload) {
                    Text(stringResource(R.string.summary_cancel_download))
                }
            }
            llmState is LlmModelState.Loading -> {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.summary_loading_model),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
            llmState is LlmModelState.Ready -> {
                Button(onClick = onSummarize, modifier = Modifier.weight(1f)) {
                    Text(
                        if (hasSummary) stringResource(R.string.summary_regenerate)
                        else stringResource(R.string.summary_summarize)
                    )
                }
                if (hasSummary) {
                    OutlinedButton(onClick = onCopySummary) {
                        Text(stringResource(R.string.summary_copy))
                    }
                }
            }
            else -> {
                Button(onClick = onDownloadModel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.summary_download_model))
                }
            }
        }
    }
    }
}

private fun formatHistoryTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

private fun formatPlaybackTime(millis: Long): String {
    val sec = (millis / 1000L).coerceAtLeast(0L)
    val m = sec / 60
    val s = sec % 60
    return "%02d:%02d".format(m, s)
}

private const val AUTO_SCROLL_BOTTOM_THRESHOLD_PX = 120

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("transcription", text))
}
