package com.stardazz.smeeting.domain.repository

import com.stardazz.smeeting.domain.model.TranscriptionHistoryEntry
import kotlinx.coroutines.flow.Flow

interface TranscriptionHistoryRepository {
    val entries: Flow<List<TranscriptionHistoryEntry>>

    suspend fun append(text: String, audioFilePath: String?)

    suspend fun remove(id: String)

    suspend fun updateSummary(id: String, summary: String)

    suspend fun removeAudio(id: String)
}
