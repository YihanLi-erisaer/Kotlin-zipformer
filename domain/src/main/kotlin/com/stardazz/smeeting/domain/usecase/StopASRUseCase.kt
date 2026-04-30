package com.stardazz.smeeting.domain.usecase

import com.stardazz.smeeting.domain.repository.ASRRepository
import javax.inject.Inject

class StopASRUseCase @Inject constructor(private val repository: ASRRepository) {
    suspend operator fun invoke(): String? = repository.stopListening()
}