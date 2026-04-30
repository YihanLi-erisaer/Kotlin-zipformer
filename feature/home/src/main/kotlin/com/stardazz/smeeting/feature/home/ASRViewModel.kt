package com.stardazz.smeeting.feature.home

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stardazz.smeeting.domain.usecase.AppendTranscriptionHistoryUseCase
import com.stardazz.smeeting.domain.usecase.ObserveAsrAudioLevelUseCase
import com.stardazz.smeeting.domain.usecase.StartASRUseCase
import com.stardazz.smeeting.domain.usecase.StopASRUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ASRViewModel @Inject constructor(
    private val application: Application,
    private val startASRUseCase: StartASRUseCase,
    private val stopASRUseCase: StopASRUseCase,
    private val appendTranscriptionHistoryUseCase: AppendTranscriptionHistoryUseCase,
    observeAsrAudioLevelUseCase: ObserveAsrAudioLevelUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ASRContract.UiState())
    val uiState: StateFlow<ASRContract.UiState> = _uiState.asStateFlow()

    private val _effect = MutableSharedFlow<ASRContract.Effect>()
    val effect: SharedFlow<ASRContract.Effect> = _effect.asSharedFlow()

    private var collectionJob: Job? = null
    private var recordingTimerJob: Job? = null
    private var recordingStartMs: Long = 0L

    init {
        viewModelScope.launch {
            observeAsrAudioLevelUseCase().collect { level ->
                _uiState.update { it.copy(audioLevel = level) }
            }
        }
    }

    fun onIntent(intent: ASRContract.Intent) {
        when (intent) {
            ASRContract.Intent.ToggleListening -> toggleListening()
            ASRContract.Intent.CopyResultClicked -> onCopyResultClicked()
        }
    }

    private fun toggleListening() {
        if (_uiState.value.isListening) {
            stopListening()
        } else {
            startListening()
        }
    }

    private fun onCopyResultClicked() {
        viewModelScope.launch {
            val state = _uiState.value
            if (state.isListening) {
                _effect.emit(
                    ASRContract.Effect.ShowMessage(
                        application.getString(R.string.stop_before_copy)
                    )
                )
                return@launch
            }
            if (state.resultText.isBlank()) {
                _effect.emit(
                    ASRContract.Effect.ShowMessage(
                        application.getString(R.string.no_text_to_copy)
                    )
                )
                return@launch
            }
            _effect.emit(ASRContract.Effect.CopyToClipboard(state.resultText))
            _effect.emit(ASRContract.Effect.ShowMessage(application.getString(R.string.copied)))
        }
    }

    private fun startListening() {
        collectionJob?.cancel()
        recordingTimerJob?.cancel()
        recordingStartMs = System.currentTimeMillis()
        recordingTimerJob = viewModelScope.launch {
            while (true) {
                val elapsed = System.currentTimeMillis() - recordingStartMs
                _uiState.update { it.copy(recordingDurationMs = elapsed) }
                delay(100L)
            }
        }
        _uiState.update { it.copy(isListening = true, recordingDurationMs = 0L) }
        collectionJob = viewModelScope.launch {
            runCatching {
                startASRUseCase().collect { transcription ->
                    _uiState.update { it.copy(transcription = transcription) }
                }
            }.onFailure { throwable ->
                recordingTimerJob?.cancel()
                _uiState.update { it.copy(isListening = false, recordingDurationMs = 0L) }
                _effect.emit(
                    ASRContract.Effect.ShowMessage(
                        throwable.message ?: application.getString(R.string.failed_start_listening)
                    )
                )
            }
        }
    }

    private fun stopListening() {
        viewModelScope.launch {
            var audioFilePath: String? = null
            runCatching {
                audioFilePath = stopASRUseCase()
            }.onFailure { throwable ->
                _effect.emit(
                    ASRContract.Effect.ShowMessage(
                        throwable.message ?: application.getString(R.string.failed_stop_listening)
                    )
                )
            }
            recordingTimerJob?.cancel()
            _uiState.update { it.copy(isListening = false, recordingDurationMs = 0L) }
            val finalText = _uiState.value.resultText.trim()
            if (finalText.isNotEmpty()) {
                appendTranscriptionHistoryUseCase(finalText, audioFilePath)
            } else if (!audioFilePath.isNullOrEmpty()) {
                runCatching { File(audioFilePath!!).delete() }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        collectionJob?.cancel()
        recordingTimerJob?.cancel()
    }
}
