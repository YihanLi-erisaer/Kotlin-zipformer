package com.stardazz.smeeting.domain.repository

import com.stardazz.smeeting.domain.model.Transcription
import kotlinx.coroutines.flow.Flow

interface ASRRepository {
    fun startListening(): Flow<Transcription>
    suspend fun stopListening(): String?
    fun getEngineStatus(): EngineStatus
}

enum class EngineStatus {
    IDLE, INITIALIZING, LISTENING, ERROR
}