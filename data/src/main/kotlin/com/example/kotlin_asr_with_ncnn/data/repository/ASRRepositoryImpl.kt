package com.example.kotlin_asr_with_ncnn.data.repository

import android.util.Log
import com.example.kotlin_asr_with_ncnn.core.media.AudioRecorder
import com.example.kotlin_asr_with_ncnn.core.media.NcnnNativeBridge
import com.example.kotlin_asr_with_ncnn.domain.model.Transcription
import com.example.kotlin_asr_with_ncnn.domain.repository.ASRRepository
import com.example.kotlin_asr_with_ncnn.domain.repository.EngineStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ASRRepositoryImpl @Inject constructor(
    private val nativeBridge: NcnnNativeBridge,
    private val audioRecorder: AudioRecorder
) : ASRRepository {

    private val TAG = "ASRRepositoryImpl"

    private val _transcriptionFlow = MutableSharedFlow<Transcription>(
        replay = 1,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Completed sentences; new sentences append here */
    private var accumulatedText = StringBuilder()
    /** In-progress partial for the current sentence */
    private var currentUtterance = ""

    init {
        nativeBridge.setCallback { text, confidence, isFinal ->
            Log.d(TAG, "Native Callback: '$text' (isFinal: $isFinal)")
            
            val isStatusMessage = text.contains("ASR Session Finished") || text.contains("Transcribe Complete")
            if (isStatusMessage) {
                Log.d(TAG, "Skipping status message to preserve transcription text")
                return@setCallback
            }
            if (text.isEmpty()) return@setCallback

            val displayText = if (isFinal) {
                if (accumulatedText.isNotEmpty()) accumulatedText.append(", ")
                accumulatedText.append(text)
                currentUtterance = ""
                accumulatedText.toString()
            } else {
                // New utterance: new text doesn't extend current (model reset after endpoint)
                val isNewUtterance = currentUtterance.isNotEmpty() &&
                    text != currentUtterance &&
                    !text.startsWith(currentUtterance) &&
                    !currentUtterance.startsWith(text)
                if (isNewUtterance) {
                    if (accumulatedText.isNotEmpty()) accumulatedText.append(", ")
                    accumulatedText.append(currentUtterance)
                    currentUtterance = ""
                }
                currentUtterance = text
                if (accumulatedText.isEmpty()) text else "$accumulatedText, $text"
            }

            val newTranscription = Transcription(
                id = System.currentTimeMillis().toString(),
                text = displayText,
                confidence = confidence,
                timestamp = System.currentTimeMillis(),
                isFinal = isFinal
            )
            _transcriptionFlow.tryEmit(newTranscription)
        }
    }

    override fun startListening(): Flow<Transcription> {
        Log.d(TAG, "startListening: Streaming audio to ASR")
        accumulatedText = StringBuilder()
        currentUtterance = ""

        _transcriptionFlow.tryEmit(Transcription("0", "", 0f, 0L, false))

        // Start inference loop first, then stream audio chunks as they arrive
        nativeBridge.startInference()

        audioRecorder.start(object : AudioRecorder.AudioDataListener {
            override fun onAudioData(data: ShortArray) {
                nativeBridge.feedAudioData(data)
            }
        })
        return _transcriptionFlow.asSharedFlow()
    }

    override suspend fun stopListening() {
        Log.d(TAG, "stopListening: Stopping recorder and flushing stream")
        audioRecorder.stop()

        withContext(Dispatchers.Default) {
            nativeBridge.signalInputFinished()
            nativeBridge.stopInference()
            // Append full stop when user presses Stop, if there is output and it doesn't already end with .
            val fullText = (accumulatedText.toString() + if (currentUtterance.isNotEmpty()) " $currentUtterance" else "").trim()
            if (fullText.isNotEmpty() && !fullText.endsWith('.')) {
                val textWithPeriod = "$fullText。"
                accumulatedText.clear()
                accumulatedText.append(textWithPeriod)
                _transcriptionFlow.tryEmit(Transcription("stop", textWithPeriod, 0f, System.currentTimeMillis(), true))
            }
            Log.d(TAG, "Streaming inference completed")
        }
    }

    override fun getEngineStatus(): EngineStatus {
        return when(nativeBridge.getStatus()) {
            0 -> EngineStatus.IDLE
            1 -> EngineStatus.INITIALIZING
            2 -> EngineStatus.LISTENING
            else -> EngineStatus.ERROR
        }
    }
}
