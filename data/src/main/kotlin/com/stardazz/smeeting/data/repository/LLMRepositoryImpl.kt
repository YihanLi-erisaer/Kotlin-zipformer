package com.stardazz.smeeting.data.repository

import android.os.SystemClock
import android.util.Log
import com.stardazz.smeeting.core.common.InferenceCoordinator
import com.stardazz.smeeting.core.llm.NcnnLlmBridge
import com.stardazz.smeeting.domain.repository.LLMRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
    private val llmThreads: Int by lazy { computeAdaptiveThreads() }

    override val isModelReady: StateFlow<Boolean> = bridge.isLoaded

    override fun summarize(text: String): Flow<String> = channelFlow {
        summarizeMutex.withLock {
            if (!coordinator.acquireLlm()) {
                Log.w(TAG, "Cannot start LLM: ASR is currently active")
                return@withLock
            }

            try {
                val normalized = text.trim()
                if (normalized.isEmpty()) return@withLock
                val language = detectPromptLanguage(normalized)
                val chunks = splitByUtf8Bytes(normalized, CHUNK_INPUT_UTF8_BYTES)
                    .take(MAX_CHUNKS_PER_REQUEST)
                val chunkSummaries = mutableListOf<String>()

                chunks.forEachIndexed { index, chunk ->
                    val chunkPrompt =
                        if (chunks.size == 1) {
                            buildPrompt(chunk, language)
                        } else {
                            buildChunkPrompt(
                                chunkText = chunk,
                                index = index + 1,
                                total = chunks.size,
                                language = language,
                            )
                        }
                    val chunkResult = runGeneration(
                        prompt = chunkPrompt,
                        onPartial = { partial ->
                            val output =
                                if (chunks.size == 1) partial
                                else "${buildProgressPrefix(index + 1, chunks.size, language)}\n\n$partial"
                            trySend(output)
                        },
                        maxTokens = CHUNK_MAX_TOKENS,
                    ).trim()
                    if (chunkResult.isNotEmpty()) {
                        chunkSummaries += chunkResult
                    }
                }

                if (chunkSummaries.isEmpty()) return@withLock
                if (chunkSummaries.size == 1) {
                    trySend(chunkSummaries.first())
                    return@withLock
                }

                val mergePrompt = buildMergePrompt(chunkSummaries, language)
                val merged = runGeneration(
                    prompt = mergePrompt,
                    onPartial = { partial -> trySend(partial) },
                    maxTokens = MERGE_MAX_TOKENS,
                ).trim()
                if (merged.isNotEmpty()) {
                    trySend(merged)
                } else {
                    trySend(chunkSummaries.joinToString("\n\n"))
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

    private suspend fun runGeneration(
        prompt: String,
        onPartial: (String) -> Unit,
        maxTokens: Int,
    ): String {
        return coroutineScope {
            val sb = StringBuilder()
            var lastEmitMs = 0L
            var lastEmitLength = 0
            val collector = launch {
                bridge.tokenFlow().collect { token ->
                    sb.append(token)
                    val now = SystemClock.elapsedRealtime()
                    val appendedChars = sb.length - lastEmitLength
                    if (
                        now - lastEmitMs >= STREAM_EMIT_INTERVAL_MS &&
                        appendedChars >= MIN_STREAM_EMIT_CHARS
                    ) {
                        onPartial(sb.toString())
                        lastEmitMs = now
                        lastEmitLength = sb.length
                    }
                }
            }
            var nativeResult = ""
            runCatching {
                nativeResult = bridge.generate(prompt, maxTokens = maxTokens, nThreads = llmThreads)
            }.onFailure { t ->
                Log.e(TAG, "LLM generate failed", t)
            }
            collector.cancelAndJoin()
            val streamed = sb.toString()
            if (streamed.length > lastEmitLength) {
                onPartial(streamed)
            }
            if (streamed.isNotBlank()) streamed else nativeResult
        }
    }

    private fun splitByUtf8Bytes(text: String, maxBytesPerChunk: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val remaining = text.substring(start)
            val chunk = trimToUtf8Bytes(remaining, maxBytesPerChunk)
            if (chunk.isEmpty()) break
            chunks += chunk.trim()
            start += chunk.length
        }
        return chunks.filter { it.isNotEmpty() }
    }

    private fun buildPrompt(transcriptionText: String, language: PromptLanguage): String {
        val normalized = transcriptionText.trim()
        val trimmed = trimToUtf8Bytes(normalized, MAX_INPUT_UTF8_BYTES)
        val systemPrompt = buildSystemPrompt(language)
        return "<|im_start|>system\n$systemPrompt<|im_end|>\n<|im_start|>user\n$trimmed<|im_end|>\n<|im_start|>assistant\n"
    }

    private fun buildChunkPrompt(
        chunkText: String,
        index: Int,
        total: Int,
        language: PromptLanguage,
    ): String {
        val instruction =
            when (language) {
                PromptLanguage.CHINESE ->
                    "这是长文本的第 $index/$total 段，请先只总结本段，保留关键信息和行动项。"
                PromptLanguage.ENGLISH ->
                    "This is segment $index/$total of a long transcription. Summarize this segment only with key points and action items."
            }
        val chunkBody = trimToUtf8Bytes(chunkText.trim(), CHUNK_INPUT_UTF8_BYTES)
        return "<|im_start|>system\n${buildChunkSystemPrompt(language)}<|im_end|>\n" +
            "<|im_start|>user\n$instruction\n\n$chunkBody<|im_end|>\n<|im_start|>assistant\n"
    }

    private fun buildMergePrompt(chunkSummaries: List<String>, language: PromptLanguage): String {
        val mergedInput =
            chunkSummaries.mapIndexed { idx, summary ->
                "[$idx]\n${summary.trim()}"
            }.joinToString("\n\n")
        val body = trimToUtf8Bytes(mergedInput, MERGE_INPUT_UTF8_BYTES)
        return "<|im_start|>system\n${buildMergeSystemPrompt(language)}<|im_end|>\n" +
            "<|im_start|>user\n$body<|im_end|>\n<|im_start|>assistant\n"
    }

    private fun buildProgressPrefix(index: Int, total: Int, language: PromptLanguage): String =
        when (language) {
            PromptLanguage.CHINESE -> "正在总结第 $index/$total 段"
            PromptLanguage.ENGLISH -> "Summarizing segment $index/$total"
        }

    private fun trimToUtf8Bytes(text: String, maxBytes: Int): String {
        if (maxBytes <= 0 || text.isEmpty()) return ""
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        var low = 0
        var high = text.length
        while (low < high) {
            val mid = (low + high + 1) / 2
            val bytes = text.substring(0, mid).toByteArray(Charsets.UTF_8).size
            if (bytes <= maxBytes) {
                low = mid
            } else {
                high = mid - 1
            }
        }
        return text.substring(0, low)
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

    private fun computeAdaptiveThreads(): Int {
        val cpuCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val threads = (cpuCount / 2).coerceIn(MIN_THREADS, MAX_THREADS)
        // val threads = 6
        Log.i(TAG, "Adaptive LLM threads: cpu=$cpuCount, threads=$threads")
        return threads
    }

    companion object {
        private const val TAG = "LLMRepositoryImpl"
        private const val CHUNK_MAX_TOKENS = 224
        private const val MERGE_MAX_TOKENS = 320
        private const val MIN_THREADS = 2
        private const val MAX_THREADS = 6
        private const val MAX_CHUNKS_PER_REQUEST = 6
        private const val MAX_INPUT_UTF8_BYTES = 3000
        private const val CHUNK_INPUT_UTF8_BYTES = 2200
        private const val MERGE_INPUT_UTF8_BYTES = 3200
        private const val STREAM_EMIT_INTERVAL_MS = 250L
        private const val MIN_STREAM_EMIT_CHARS = 48
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

        private const val ENGLISH_CHUNK_SYSTEM_PROMPT =
            "You summarize one segment of a long meeting transcription. " +
            "Keep factual details, decisions, and action items from this segment only."

        private const val CHINESE_CHUNK_SYSTEM_PROMPT =
            "你负责总结长会议转写中的单个片段。只总结当前片段，保留事实、决策和行动项。"

        private const val ENGLISH_MERGE_SYSTEM_PROMPT =
            "You are given summaries from multiple transcription segments. " +
            "Merge them into one coherent final summary in the format: Summary, Key Points, Action Items."

        private const val CHINESE_MERGE_SYSTEM_PROMPT =
            "你将收到多个片段总结，请合并为一份完整总结，格式为：摘要、关键点、行动项。"
    }

    private fun buildChunkSystemPrompt(language: PromptLanguage): String =
        when (language) {
            PromptLanguage.CHINESE -> CHINESE_CHUNK_SYSTEM_PROMPT
            PromptLanguage.ENGLISH -> ENGLISH_CHUNK_SYSTEM_PROMPT
        }

    private fun buildMergeSystemPrompt(language: PromptLanguage): String =
        when (language) {
            PromptLanguage.CHINESE -> CHINESE_MERGE_SYSTEM_PROMPT
            PromptLanguage.ENGLISH -> ENGLISH_MERGE_SYSTEM_PROMPT
        }

    private enum class PromptLanguage {
        CHINESE,
        ENGLISH,
    }
}
