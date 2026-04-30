package com.stardazz.smeeting.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stardazz.smeeting.core.startup.LlmModelManager
import com.stardazz.smeeting.core.startup.LlmModelState
import com.stardazz.smeeting.domain.model.TranscriptionHistoryEntry
import com.stardazz.smeeting.domain.usecase.DeleteTranscriptionHistoryEntryUseCase
import com.stardazz.smeeting.domain.usecase.DeleteTranscriptionAudioUseCase
import com.stardazz.smeeting.domain.usecase.ObserveTranscriptionHistoryUseCase
import com.stardazz.smeeting.domain.usecase.SummarizeTranscriptionUseCase
import com.stardazz.smeeting.domain.repository.LLMRepository
import com.stardazz.smeeting.domain.usecase.UpdateHistorySummaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HistoryViewModel @Inject constructor(
    observeTranscriptionHistoryUseCase: ObserveTranscriptionHistoryUseCase,
    private val deleteTranscriptionHistoryEntryUseCase: DeleteTranscriptionHistoryEntryUseCase,
    private val deleteTranscriptionAudioUseCase: DeleteTranscriptionAudioUseCase,
    private val summarizeTranscriptionUseCase: SummarizeTranscriptionUseCase,
    private val updateHistorySummaryUseCase: UpdateHistorySummaryUseCase,
    private val llmRepository: LLMRepository,
    private val llmModelManager: LlmModelManager,
) : ViewModel() {

    val entries: StateFlow<List<TranscriptionHistoryEntry>> =
        observeTranscriptionHistoryUseCase().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyList(),
        )

    val llmState: StateFlow<LlmModelState> = llmModelManager.state

    private val _summarizingEntryId = MutableStateFlow<String?>(null)
    val summarizingEntryId: StateFlow<String?> = _summarizingEntryId.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private var summarizeJob: Job? = null

    fun deleteEntry(id: String) {
        viewModelScope.launch {
            deleteTranscriptionHistoryEntryUseCase(id)
        }
    }

    fun deleteAudio(id: String) {
        viewModelScope.launch {
            deleteTranscriptionAudioUseCase(id)
        }
    }

    fun summarize(entry: TranscriptionHistoryEntry) {
        // Replace any in-flight run: cancel alone leaves Job.isCompleted false while JNI still runs,
        // which blocked new attempts; abort + awaitGenerationIdle() waits for native to release.
        summarizeJob?.cancel()
        llmRepository.abortGeneration()

        summarizeJob = viewModelScope.launch {
            val myJob = coroutineContext[Job]!!
            try {
                // Show generating UI before await: after Cancel, native may still be finishing; await
                // can take noticeable time and looked like "no response" when this ran after await.
                _summarizingEntryId.value = entry.id
                _streamingText.value = ""

                llmRepository.awaitGenerationIdle()
                if (!isActive) return@launch

                summarizeTranscriptionUseCase(entry.text).collect { accumulated ->
                    _streamingText.value = accumulated
                }
                val finalText = _streamingText.value
                if (finalText.isNotEmpty()) {
                    updateHistorySummaryUseCase(entry.id, finalText)
                }
            } finally {
                _summarizingEntryId.value = null
                // Only clear if this job still owns summarizeJob. Otherwise a finished job's finally
                // can run after a newer summarize() replaced the reference and would wipe the new Job.
                if (summarizeJob === myJob) {
                    summarizeJob = null
                }
            }
        }
    }

    fun cancelSummarize() {
        llmRepository.abortGeneration()
        summarizeJob?.cancel()
        _summarizingEntryId.value = null
        _streamingText.value = ""
    }

    fun downloadLlmModel(context: android.content.Context) {
        viewModelScope.launch {
            llmModelManager.downloadModel(context)
            if (llmModelManager.state.value is LlmModelState.Downloaded) {
                llmModelManager.loadModel(context, nThreads = LlmModelManager.DEFAULT_LOAD_THREADS)
            }
        }
    }

    fun cancelLlmDownload() {
        llmModelManager.cancelDownload()
    }

    fun deleteLlmModelFiles(context: android.content.Context) {
        viewModelScope.launch {
            cancelSummarize()
            llmModelManager.deleteDownloadedModel(context)
        }
    }
}
