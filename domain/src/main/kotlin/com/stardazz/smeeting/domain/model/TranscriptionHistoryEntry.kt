package com.stardazz.smeeting.domain.model

data class TranscriptionHistoryEntry(
    val id: String,
    val text: String,
    val createdAtMillis: Long,
    val summary: String? = null,
    val audioFilePath: String? = null,
)
