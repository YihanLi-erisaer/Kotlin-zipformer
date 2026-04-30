package com.stardazz.smeeting.domain.usecase

import com.stardazz.smeeting.domain.repository.ASRRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class ObserveAsrAudioLevelUseCase @Inject constructor(
    private val repository: ASRRepository,
) {
    operator fun invoke(): StateFlow<Float> = repository.audioLevel
}
