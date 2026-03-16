package com.example.kotlin_asr_with_ncnn.domain.usecase

import com.example.kotlin_asr_with_ncnn.domain.repository.ASRRepository

class StopASRUseCase(private val repository: ASRRepository) {
    suspend operator fun invoke() {
        repository.stopListening()
    }
}