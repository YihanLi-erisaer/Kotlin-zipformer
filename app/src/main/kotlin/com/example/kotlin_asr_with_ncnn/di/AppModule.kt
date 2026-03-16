package com.example.kotlin_asr_with_ncnn.di

import com.example.kotlin_asr_with_ncnn.core.media.AudioRecorder
import com.example.kotlin_asr_with_ncnn.core.media.NcnnNativeBridge
import com.example.kotlin_asr_with_ncnn.data.repository.ASRRepositoryImpl
import com.example.kotlin_asr_with_ncnn.domain.repository.ASRRepository
import com.example.kotlin_asr_with_ncnn.domain.usecase.StartASRUseCase
import com.example.kotlin_asr_with_ncnn.domain.usecase.StopASRUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideASRRepository(
        nativeBridge: NcnnNativeBridge,
        audioRecorder: AudioRecorder
    ): ASRRepository {
        return ASRRepositoryImpl(nativeBridge, audioRecorder)
    }

    @Provides
    @Singleton
    fun provideStartASRUseCase(repository: ASRRepository): StartASRUseCase {
        return StartASRUseCase(repository)
    }

    @Provides
    @Singleton
    fun provideStopASRUseCase(repository: ASRRepository): StopASRUseCase {
        return StopASRUseCase(repository)
    }
}
