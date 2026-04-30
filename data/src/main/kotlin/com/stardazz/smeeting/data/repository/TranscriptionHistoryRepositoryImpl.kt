package com.stardazz.smeeting.data.repository

import com.stardazz.smeeting.data.db.TranscriptionHistoryDao
import com.stardazz.smeeting.data.db.TranscriptionHistoryEntity
import com.stardazz.smeeting.data.db.toDomain
import com.stardazz.smeeting.domain.model.TranscriptionHistoryEntry
import com.stardazz.smeeting.domain.repository.TranscriptionHistoryRepository
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val MAX_ENTRIES = 500

@Singleton
class TranscriptionHistoryRepositoryImpl @Inject constructor(
    private val dao: TranscriptionHistoryDao,
) : TranscriptionHistoryRepository {

    override val entries: Flow<List<TranscriptionHistoryEntry>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun append(text: String, audioFilePath: String?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        dao.insert(
            TranscriptionHistoryEntity(
                id = UUID.randomUUID().toString(),
                text = trimmed,
                createdAtMillis = System.currentTimeMillis(),
                audioFilePath = audioFilePath,
            )
        )
        val total = dao.count()
        if (total > MAX_ENTRIES) {
            dao.deleteOldest(total - MAX_ENTRIES)
        }
    }

    override suspend fun remove(id: String) {
        dao.getById(id)?.audioFilePath?.let { path ->
            runCatching {
                File(path).takeIf { it.exists() }?.delete()
            }
        }
        dao.deleteById(id)
    }

    override suspend fun updateSummary(id: String, summary: String) {
        dao.updateSummary(id, summary)
    }

    override suspend fun removeAudio(id: String) {
        dao.getById(id)?.audioFilePath?.let { path ->
            runCatching {
                File(path).takeIf { it.exists() }?.delete()
            }
        }
        dao.clearAudioById(id)
    }
}
