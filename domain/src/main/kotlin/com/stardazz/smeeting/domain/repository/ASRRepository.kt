package com.stardazz.smeeting.domain.repository

import com.stardazz.smeeting.domain.model.Transcription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface ASRRepository {
    fun startListening(): Flow<Transcription>
    suspend fun stopListening(): String?
    fun getEngineStatus(): EngineStatus
    val audioLevel: StateFlow<Float>
}

enum class EngineStatus {
    IDLE, INITIALIZING, LISTENING, ERROR
}