package com.example.kotlin_asr_with_ncnn.core.media

data class ModelConfig(
    val encoderParam: String,
    val encoderBin: String,
    val decoderParam: String,
    val decoderBin: String,
    val joinerParam: String,
    val joinerBin: String,
    val tokens: String,
    val numThreads: Int = 4,
    val useVulkanCompute: Boolean = true,
    /** When false (default), uses greedy search; when true, modified beam search. */
    val useBeamSearch: Boolean = false
)
