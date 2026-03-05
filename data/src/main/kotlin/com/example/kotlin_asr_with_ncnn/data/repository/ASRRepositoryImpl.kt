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

/** Punctuation handling for ASR output. Detects script (CJK vs Latin) and applies appropriate punctuation. */
private object PunctuationHelper {
    private val SENTENCE_ENDING = setOf('。', '.', '?', '!', '？', '！', '…')

    fun endsWithSentencePunctuation(text: String): Boolean =
        text.isNotEmpty() && SENTENCE_ENDING.any { text.trimEnd().endsWith(it) }

    /** Heuristic: text is CJK if a significant share of letters are in CJK ranges */
    fun isPrimarilyCJK(text: String): Boolean {
        if (text.isBlank()) return false
        var cjkCount = 0
        var letterCount = 0
        for (c in text) {
            when {
                c in '\u4e00'..'\u9fff' -> { cjkCount++; letterCount++ } // CJK unified
                c in '\u3000'..'\u303f' -> { cjkCount++; letterCount++ } // CJK punctuation
                c in '\uff00'..'\uffef' -> { cjkCount++; letterCount++ } // Fullwidth
                c.isLetter() -> letterCount++
            }
        }
        return letterCount > 0 && cjkCount.toFloat() / letterCount >= 0.2
    }

    /** Space/glue between sentences. Does not include period—that comes from getEndPunctuation. */
    fun getSentenceSeparator(isCJK: Boolean): String = if (isCJK) "" else " "
    fun getEndPunctuation(isCJK: Boolean): String = if (isCJK) "。" else "."
}

private fun buildDisplayWithPartial(accumulated: String, partial: String): String {
    val combined = accumulated + partial
    val isCJK = PunctuationHelper.isPrimarilyCJK(combined)
    val sep = PunctuationHelper.getSentenceSeparator(isCJK)
    val acc = accumulated.trimEnd()
    return if (acc.isEmpty()) partial else "$acc$sep${partial.trim()}"
}

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
                val sep = PunctuationHelper.getSentenceSeparator(
                    PunctuationHelper.isPrimarilyCJK(accumulatedText.toString() + text)
                )
                if (accumulatedText.isNotEmpty()) {
                    val prev = accumulatedText.toString().trimEnd()
                    if (!PunctuationHelper.endsWithSentencePunctuation(prev)) {
                        accumulatedText.append(PunctuationHelper.getEndPunctuation(
                            PunctuationHelper.isPrimarilyCJK(prev)
                        ))
                    }
                    accumulatedText.append(sep)
                }
                accumulatedText.append(text.trim())
                currentUtterance = ""
                accumulatedText.toString()
            } else {
                // New utterance: new text doesn't extend current (model reset after endpoint)
                val isNewUtterance = currentUtterance.isNotEmpty() &&
                    text != currentUtterance &&
                    !text.startsWith(currentUtterance) &&
                    !currentUtterance.startsWith(text)
                if (isNewUtterance) {
                    val toAppend = currentUtterance.trim()
                    val isCJK = PunctuationHelper.isPrimarilyCJK(accumulatedText.toString() + toAppend)
                    val sep = PunctuationHelper.getSentenceSeparator(isCJK)
                    if (accumulatedText.isNotEmpty()) {
                        val prev = accumulatedText.toString().trimEnd()
                        if (!PunctuationHelper.endsWithSentencePunctuation(prev)) {
                            accumulatedText.append(PunctuationHelper.getEndPunctuation(isCJK))
                        }
                        accumulatedText.append(sep)
                    }
                    accumulatedText.append(toAppend)
                    currentUtterance = ""
                }
                currentUtterance = text
                val full = if (accumulatedText.isEmpty()) text else buildDisplayWithPartial(
                    accumulatedText.toString(), text
                )
                full
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
            // Append end punctuation when user presses Stop, if output exists and doesn't already end with one
            val fullText = (accumulatedText.toString() + if (currentUtterance.isNotEmpty()) {
                val sep = PunctuationHelper.getSentenceSeparator(
                    PunctuationHelper.isPrimarilyCJK(accumulatedText.toString() + currentUtterance)
                )
                sep + currentUtterance.trim()
            } else "").trim()
            if (fullText.isNotEmpty() && !PunctuationHelper.endsWithSentencePunctuation(fullText)) {
                val isCJK = PunctuationHelper.isPrimarilyCJK(fullText)
                val endPunct = PunctuationHelper.getEndPunctuation(isCJK)
                val textWithEnding = "$fullText$endPunct"
                accumulatedText.clear()
                accumulatedText.append(textWithEnding)
                _transcriptionFlow.tryEmit(Transcription("stop", textWithEnding, 0f, System.currentTimeMillis(), true))
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
