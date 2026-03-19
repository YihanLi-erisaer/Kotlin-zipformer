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

/** Punctuation handling for ASR output. Detects script (CJK vs Latin) and infers appropriate punctuation from text. */
private object PunctuationHelper {
    private val SENTENCE_ENDING = setOf('。', '.', '?', '!', '？', '！', '…', '，', ',')

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

    /** Space/glue between sentences. Does not include punctuation—that comes from getEndPunctuation. */
    fun getSentenceSeparator(isCJK: Boolean): String = if (isCJK) "" else " "

    /** Extracts the last segment (content after the last sentence-ending punctuation) for punctuation decisions. */
    fun getLastSegment(accumulated: String): String {
        val trimmed = accumulated.trimEnd()
        if (trimmed.isEmpty()) return ""
        val lastPunctIdx = trimmed.indexOfLast { SENTENCE_ENDING.contains(it) }
        return if (lastPunctIdx >= 0) trimmed.substring(lastPunctIdx + 1).trim() else trimmed
    }

    /** Infers sentence-ending punctuation from text content (question, exclamation, comma, or default period). */
    fun getEndPunctuation(text: String, isCJK: Boolean): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return if (isCJK) "。" else "."

        // Exclamation first (e.g. "how amazing" vs "how are you")
        val looksLikeExclamation = when {
            isCJK -> {
                trimmed.startsWith("太") || trimmed.startsWith("真是") ||
                trimmed.contains("好棒") || trimmed.contains("好啊") || trimmed.contains("好呀") ||
                trimmed.contains("哇") || trimmed.contains("天呐") || trimmed.contains("天哪") ||
                trimmed.contains("太棒") || trimmed.contains("太好了") || trimmed.contains("真棒")
            }
            else -> {
                val lower = trimmed.lowercase()
                lower.startsWith("wow") || lower.startsWith("oh ") || lower.startsWith("so ") ||
                lower.startsWith("such ") || lower.startsWith("what a ") ||
                lower.contains("how amazing") || lower.contains("how great") || lower.contains("how wonderful") ||
                lower.endsWith("!") || lower.contains(" amazing") || lower.contains(" great")
            }
        }
        if (looksLikeExclamation) return if (isCJK) "！" else "!"

        // Question patterns — only when clear interrogative markers exist (avoid over-prediction)
        val looksLikeQuestion = when {
            isCJK -> {
                // Chinese: strong interrogative particles at end (吗/呢 are reliable; 啊 is removed—too many false positives)
                trimmed.endsWith('吗') || trimmed.endsWith('呢') ||
                // Interrogative pronouns (avoid single 几: 几乎/几年 are not questions)
                trimmed.contains("什么") || trimmed.contains("怎么") || trimmed.contains("怎样") ||
                trimmed.contains("为什么") || trimmed.contains("为何") || trimmed.contains("哪里") ||
                trimmed.contains("哪儿") || trimmed.contains("谁") ||
                (trimmed.contains("几") && !trimmed.contains("几乎")) ||  // 几乎 = almost, not question
                trimmed.contains("多少") || trimmed.contains("是否") || trimmed.contains("能否") ||
                trimmed.contains("能不能") || trimmed.contains("会不会") || trimmed.contains("是不是") ||
                trimmed.contains("为何") || trimmed.contains("如何")
            }
            else -> {
                // English: question words at start
                val lower = trimmed.lowercase()
                lower.startsWith("what ") || lower.startsWith("why ") || lower.startsWith("how ") ||
                lower.startsWith("when ") || lower.startsWith("where ") || lower.startsWith("who ") ||
                lower.startsWith("which ") || lower.startsWith("whose ") ||
                lower.startsWith("is ") || lower.startsWith("are ") || lower.startsWith("can ") ||
                lower.startsWith("could ") || lower.startsWith("would ") || lower.startsWith("do ") ||
                lower.startsWith("does ") || lower.startsWith("did ") || lower.endsWith(" right") ||
                lower.endsWith(" or what")
            }
        }
        if (looksLikeQuestion) return if (isCJK) "？" else "?"

        // Short fragment: use comma when text is very short (possible mid-sentence pause)
        val wordCount = trimmed.split(Regex("\\s+")).count { it.isNotEmpty() }
        val charCount = trimmed.length
        val isShortFragment = if (isCJK) charCount <= 8 else wordCount <= 5
        if (isShortFragment) return if (isCJK) "，" else ", "

        // Default: period
        return if (isCJK) "。" else "."
    }
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
                        val lastSegment = PunctuationHelper.getLastSegment(prev)
                        accumulatedText.append(PunctuationHelper.getEndPunctuation(
                            lastSegment, PunctuationHelper.isPrimarilyCJK(prev)
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
                            val lastSegment = PunctuationHelper.getLastSegment(prev)
                            accumulatedText.append(PunctuationHelper.getEndPunctuation(lastSegment, isCJK))
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
                val lastSegment = PunctuationHelper.getLastSegment(fullText)
                val endPunct = PunctuationHelper.getEndPunctuation(lastSegment, isCJK)
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
