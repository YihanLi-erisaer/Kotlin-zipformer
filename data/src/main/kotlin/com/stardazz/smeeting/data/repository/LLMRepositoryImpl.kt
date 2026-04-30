package com.stardazz.smeeting.data.repository

import android.os.SystemClock
import android.util.Log
import com.stardazz.smeeting.core.common.InferenceCoordinator
import com.stardazz.smeeting.core.llm.NcnnLlmBridge
import com.stardazz.smeeting.domain.repository.LLMRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LLMRepositoryImpl @Inject constructor(
    private val bridge: NcnnLlmBridge,
    private val coordinator: InferenceCoordinator,
) : LLMRepository {

    /**
     * Serializes summarize runs so a second call cannot start [bridge.generate] until the previous
     * run has finished [coordinator.release]. Otherwise [InferenceCoordinator.acquireLlm] allows
     * re-entrant LLM while the slot is still held, and the second [NcnnLlmBridge.generate] hits
     * "Generation already in progress" and returns "" (Re-summarize looks stuck then exits).
     */
    private val summarizeMutex = Mutex()

    override val isModelReady: StateFlow<Boolean> = bridge.isLoaded

    override fun summarize(text: String): Flow<String> = channelFlow {
        summarizeMutex.withLock {
            if (!coordinator.acquireLlm()) {
                Log.w(TAG, "Cannot start LLM: ASR is currently active")
                return@withLock
            }

            try {
                val prompt = buildPrompt(text)
                val sb = StringBuilder()
                var lastEmitMs = 0L

                val collector = launch {
                    bridge.tokenFlow().collect { token ->
                        sb.append(token)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmitMs >= STREAM_EMIT_INTERVAL_MS) {
                            send(sb.toString())
                            lastEmitMs = now
                        }
                    }
                }

                bridge.generate(prompt, maxTokens = MAX_TOKENS, nThreads = N_THREADS)

                collector.cancel()
                if (sb.isNotEmpty()) {
                    send(sb.toString())
                }
            } finally {
                coordinator.release()
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun abortGeneration() {
        bridge.abort()
    }

    override suspend fun awaitGenerationIdle() {
        bridge.isGenerating.first { !it }
    }

    private fun buildPrompt(transcriptionText: String): String {
        val trimmed = if (transcriptionText.length > MAX_INPUT_CHARS) {
            transcriptionText.take(MAX_INPUT_CHARS)
        } else {
            transcriptionText
        }
        val promptLanguage = detectPromptLanguage(trimmed)
        val systemPrompt = buildSystemPrompt(promptLanguage)
        return "<|im_start|>system\n$systemPrompt<|im_end|>\n<|im_start|>user\n$trimmed<|im_end|>\n<|im_start|>assistant\n"
    }

    private fun detectPromptLanguage(text: String): PromptLanguage {
        val hanCharCount = text.count { it.code in HAN_UNICODE_START..HAN_UNICODE_END }
        val engCharCount = text.count { it.code in 0x0020..0x007E }
        return if (hanCharCount >= engCharCount) {
            PromptLanguage.CHINESE
        } else {
            PromptLanguage.ENGLISH
        }
    }

    private fun buildSystemPrompt(language: PromptLanguage): String =
        when (language) {
            PromptLanguage.CHINESE -> CHINESE_SYSTEM_PROMPT
            PromptLanguage.ENGLISH -> ENGLISH_SYSTEM_PROMPT
        }

    companion object {
        private const val TAG = "LLMRepositoryImpl"
        private const val MAX_TOKENS = 512
        private const val N_THREADS = 4
        private const val MAX_INPUT_CHARS = 4096
        private const val STREAM_EMIT_INTERVAL_MS = 100L
        private const val HAN_UNICODE_START = 0x4E00
        private const val HAN_UNICODE_END = 0x9FFF
        // private const val MIN_HAN_CHAR_FOR_CHINESE = 4

        private const val ENGLISH_SYSTEM_PROMPT =
            "You are a meeting transcription assistant. " +
            "Given a transcription, output a concise summary in English. " +
            "Format:\n" +
            "Summary:\n" +
            "- summary in 2-3 SENTENCES ONLY!\n\n" +
            "Key Points:\n" +
            "- bullet points ONLY ONE SENTENCE PER POINT! \n\n" +
            "Action Items:\n" +
            "- if any write 2-3 SENTENCES ONLY, otherwise write \"None\""

        private const val CHINESE_SYSTEM_PROMPT =
            "你是会议转写助手。输入是中文转写文本。必须全程仅使用简体中文输出，不得使用英文。 " +
            "输出格式：\n" +
            "摘要：\n" +
            "- 摘要仅 2-3 句话。\n\n" +
            "关键点：\n" +
            "- 项目符号列表，每点仅 1 句话。\n\n" +
            "行动项：\n" +
            "- 如有行动项，写 2-3 句话；如无则写“无”。"
    }

    private enum class PromptLanguage {
        CHINESE,
        ENGLISH,
    }
}
