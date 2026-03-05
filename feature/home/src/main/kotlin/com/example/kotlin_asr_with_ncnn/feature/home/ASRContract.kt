package com.example.kotlin_asr_with_ncnn.feature.home

import com.example.kotlin_asr_with_ncnn.domain.model.Transcription

object ASRContract {
    data class UiState(
        val isListening: Boolean = false,
        val transcription: Transcription? = null
    ) {
        val resultText: String get() = transcription?.text.orEmpty()
        val canCopy: Boolean get() = !isListening && resultText.isNotBlank()
    }

    sealed interface Intent {
        data object ToggleListening : Intent
        data object CopyResultClicked : Intent
    }

    sealed interface Effect {
        data class CopyToClipboard(val text: String) : Effect
        data class ShowMessage(val message: String) : Effect
    }
}
