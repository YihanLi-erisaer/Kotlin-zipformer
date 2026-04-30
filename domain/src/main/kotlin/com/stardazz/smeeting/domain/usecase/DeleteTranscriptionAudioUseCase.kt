package com.stardazz.smeeting.domain.usecase

import com.stardazz.smeeting.domain.repository.TranscriptionHistoryRepository
import javax.inject.Inject

class DeleteTranscriptionAudioUseCase @Inject constructor(
    private val repository: TranscriptionHistoryRepository,
) {
    suspend operator fun invoke(id: String) = repository.removeAudio(id)
}
