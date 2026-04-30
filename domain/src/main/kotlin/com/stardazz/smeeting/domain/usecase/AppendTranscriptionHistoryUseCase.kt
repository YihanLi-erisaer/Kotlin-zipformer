package com.stardazz.smeeting.domain.usecase

import com.stardazz.smeeting.domain.repository.TranscriptionHistoryRepository
import javax.inject.Inject

class AppendTranscriptionHistoryUseCase @Inject constructor(
    private val repository: TranscriptionHistoryRepository,
) {
    suspend operator fun invoke(text: String, audioFilePath: String?) {
        val t = text.trim()
        if (t.isNotEmpty()) repository.append(t, audioFilePath)
    }
}
