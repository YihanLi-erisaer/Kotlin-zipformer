package com.stardazz.smeeting.feature.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ASRScreen(
    viewModel: ASRViewModel,
    isModelLoading: Boolean = false,
    modelErrorMessage: String? = null,
    onSettingsClick: () -> Unit = {},
    onHistoryClick: () -> Unit = {},
    onStartRequested: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val outputScrollState = rememberScrollState()
    val view = LocalView.current
    val resultText = when {
        isModelLoading -> stringResource(R.string.loading_model_please_wait)
        modelErrorMessage != null -> modelErrorMessage
        uiState.isListening && uiState.resultText.isBlank() -> stringResource(R.string.recording)
        else -> uiState.resultText
    }

    LaunchedEffect(viewModel) {
        viewModel.effect.collectLatest { effect ->
            when (effect) {
                is ASRContract.Effect.CopyToClipboard -> {
                    clipboardManager.setText(AnnotatedString(effect.text))
                }
                is ASRContract.Effect.ShowMessage -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
            }
        }
    }

    LaunchedEffect(resultText) {
        // Wait for the new text to be laid out before following the latest streamed output.
        withFrameNanos { }
        outputScrollState.scrollTo(outputScrollState.maxValue)
    }

    DisposableEffect(view, uiState.isListening) {
        val previousKeepScreenOn = view.keepScreenOn
        if (uiState.isListening) {
            view.keepScreenOn = true
        }

        onDispose {
            view.keepScreenOn = previousKeepScreenOn
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = when {
                    isModelLoading -> stringResource(R.string.loading_model)
                    modelErrorMessage != null -> stringResource(R.string.model_error_title)
                    uiState.isListening -> stringResource(R.string.result_title)
                    else -> stringResource(R.string.press_start_hint)
                },
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(
                    R.string.recording_duration,
                    formatDuration(if (uiState.isListening) uiState.recordingDurationMs else 0L),
                ),
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(modifier = Modifier.height(8.dp))
            RecordingWaveform(
                modifier = Modifier
                    .fillMaxWidth(0.78f)
                    .height(34.dp),
                level = uiState.audioLevel,
                isListening = uiState.isListening,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(3f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = resultText,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(outputScrollState),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (uiState.isListening) {
                            viewModel.onIntent(ASRContract.Intent.ToggleListening)
                        } else {
                            onStartRequested()
                        }
                    },
                    enabled = !isModelLoading && modelErrorMessage == null
                ) {
                    Text(
                        text = if (uiState.isListening) {
                            stringResource(R.string.stop)
                        } else {
                            stringResource(R.string.start)
                        }
                    )
                }

                Button(
                    onClick = { viewModel.onIntent(ASRContract.Intent.CopyResultClicked) },
                    enabled = uiState.canCopy
                ) {
                    Text(text = stringResource(R.string.copy))
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            Text(text = stringResource(R.string.ai_mistakes_warning), style = MaterialTheme.typography.labelSmall)

            Spacer(modifier = Modifier.weight(1f))
        }

        IconButton(
            onClick = onSettingsClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(48.dp)
        ) {
            Text(text = "⚙", style = MaterialTheme.typography.titleLarge)
        }

        IconButton(
            onClick = onHistoryClick,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Menu,
                contentDescription = stringResource(R.string.history_open),
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }
}

@Composable
private fun RecordingWaveform(
    modifier: Modifier,
    level: Float,
    isListening: Boolean,
    color: Color,
) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val animatedPhase = transition.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    ).value
    val phase = if (isListening) animatedPhase else 0f

    Canvas(modifier = modifier) {
        if (!isListening) {
            val lineHeight = size.height * 0.08f
            val top = (size.height - lineHeight) / 2f
            drawRoundRect(
                color = color.copy(alpha = 0.65f),
                topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                size = androidx.compose.ui.geometry.Size(size.width, lineHeight),
                cornerRadius = CornerRadius(lineHeight / 2f, lineHeight / 2f),
            )
            return@Canvas
        }
        val bars = 24
        val gap = 6f
        val barWidth = (size.width - gap * (bars - 1)) / bars
        val baseHeight = size.height * 0.10f
        val effectiveLevel = level.coerceIn(0f, 1f).coerceAtLeast(0.07f)
        val maxExtra = size.height * 0.66f * effectiveLevel
        for (i in 0 until bars) {
            val centerFactor = 1f - (kotlin.math.abs(i - (bars / 2f)) / (bars / 2f)).coerceIn(0f, 1f)
            val wave = (sin(phase + i * 0.6f) + 1f) / 2f
            val h = baseHeight + maxExtra * wave * (0.5f + centerFactor * 0.5f)
            val left = i * (barWidth + gap)
            val top = (size.height - h) / 2f
            drawRoundRect(
                color = color.copy(alpha = 0.85f),
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%02d:%02d".format(min, sec)
}
