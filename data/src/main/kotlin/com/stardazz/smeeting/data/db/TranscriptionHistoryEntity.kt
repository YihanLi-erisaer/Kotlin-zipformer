package com.stardazz.smeeting.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.stardazz.smeeting.domain.model.TranscriptionHistoryEntry

@Entity(tableName = "transcription_history")
data class TranscriptionHistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "created_at_millis") val createdAtMillis: Long,
    @ColumnInfo(name = "summary", defaultValue = "NULL") val summary: String? = null,
    @ColumnInfo(name = "audio_file_path", defaultValue = "NULL") val audioFilePath: String? = null,
)

fun TranscriptionHistoryEntity.toDomain() = TranscriptionHistoryEntry(
    id = id,
    text = text,
    createdAtMillis = createdAtMillis,
    summary = summary,
    audioFilePath = audioFilePath,
)
