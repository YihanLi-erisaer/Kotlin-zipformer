package com.example.kotlin_asr_with_ncnn.domain.repository

import com.example.kotlin_asr_with_ncnn.domain.model.Transcription
import kotlinx.coroutines.flow.Flow

interface ASRRepository {
    fun startListening(): Flow<Transcription>
    suspend fun stopListening()
    fun getEngineStatus(): EngineStatus
}

enum class EngineStatus {
    IDLE, INITIALIZING, LISTENING, ERROR
}