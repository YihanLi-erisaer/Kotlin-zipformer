package com.example.kotlin_asr_with_ncnn.domain.usecase

import com.example.kotlin_asr_with_ncnn.domain.model.Transcription
import com.example.kotlin_asr_with_ncnn.domain.repository.ASRRepository
import kotlinx.coroutines.flow.Flow

class StartASRUseCase(private val repository: ASRRepository) {
    operator fun invoke(): Flow<Transcription> {
        return repository.startListening()
    }
}