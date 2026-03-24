package com.example.kotlin_asr_with_ncnn.core.media

import android.content.res.AssetManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NcnnNativeBridge @Inject constructor() {
    private val isModelInitialized = AtomicBoolean(false)

    companion object {
        init {
            try {
                // Try to load OpenMP first as it is a dependency of ncnn_asr
                try {
                    System.loadLibrary("omp")
                    Log.i("NcnnNativeBridge", "Native library libomp.so loaded successfully")
                } catch (e: UnsatisfiedLinkError) {
                    Log.w("NcnnNativeBridge", "libomp.so not found, it might be statically linked or missing: ${e.message}")
                }

                System.loadLibrary("ncnn_asr")
                Log.i("NcnnNativeBridge", "Native library ncnn_asr loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("NcnnNativeBridge", "Failed to load native library: ${e.message}")
                // Rethrow to make the failure obvious during development
                throw e
            }
        }
    }


    private var callback: ((String, Float, Boolean) -> Unit)? = null

    fun setCallback(cb: (String, Float, Boolean) -> Unit) {
        this.callback = cb
    }

    // Called from C++ via JNI
    fun onNativeResult(text: String, confidence: Float, isFinal: Boolean) {
        callback?.invoke(text, confidence, isFinal)
    }

    fun initModel(assetManager: AssetManager, config: ModelConfig): Boolean {
        if (isModelInitialized.get()) {
            Log.d("NcnnNativeBridge", "Model already initialized, skipping duplicate init")
            return true
        }

        synchronized(this) {
            if (isModelInitialized.get()) {
                Log.d("NcnnNativeBridge", "Model already initialized, skipping duplicate init")
                return true
            }

            val success = initModelNative(
                assetManager,
                config.encoderParam,
                config.encoderBin,
                config.decoderParam,
                config.decoderBin,
                config.joinerParam,
                config.joinerBin,
                config.tokens,
                config.numThreads,
                config.useVulkanCompute,
                config.useBeamSearch
            )
            if (success) {
                isModelInitialized.set(true)
            }
            return success
        }
    }

    fun releaseModel() {
        stopInference()
        synchronized(this) {
            releaseModelNative()
            isModelInitialized.set(false)
        }
    }

    private external fun initModelNative(
        assetManager: AssetManager,
        encoderParam: String,
        encoderBin: String,
        decoderParam: String,
        decoderBin: String,
        joinerParam: String,
        joinerBin: String,
        tokens: String,
        numThreads: Int,
        useVulkan: Boolean,
        useBeamSearch: Boolean
    ): Boolean

    private external fun releaseModelNative()

    external fun startInference()
    external fun stopInference()
    external fun signalInputFinished()
    external fun feedAudioData(data: ShortArray)
    external fun getStatus(): Int
}
