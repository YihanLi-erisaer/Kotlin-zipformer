#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/asset_manager_jni.h>
#include <thread>
#include <atomic>
#include <vector>
#include <queue>
#include <mutex>
#include <condition_variable>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>

#include "sherpa-ncnn/csrc/recognizer.h"

#define TAG "NcnnASR-Native"

static sherpa_ncnn::Recognizer* g_recognizer = nullptr;
static jobject g_bridge_obj = nullptr;
static jmethodID g_callback_mid = nullptr;
static JavaVM* g_jvm = nullptr;
static std::atomic<bool> g_is_running(false);
static std::thread g_inference_thread;
static std::mutex g_state_mutex;
static std::mutex g_thread_mutex;

static std::queue<std::vector<float>> g_audio_queue;
static std::mutex g_audio_mutex;
static std::condition_variable g_audio_cv;
static std::atomic<bool> g_input_finished(false);
static std::atomic<bool> g_flush_done(false);
static std::mutex g_done_mutex;
static std::condition_variable g_done_cv;

enum Status { IDLE = 0, INITIALIZING = 1, LISTENING = 2, ERROR = 3 };
static std::atomic<int> g_status(IDLE);

bool verify_asset(AAssetManager* mgr, const std::string& path) {
    if (path.empty()) return false;
    AAsset* asset = AAssetManager_open(mgr, path.c_str(), AASSET_MODE_UNKNOWN);
    if (asset) {
        AAsset_close(asset);
        return true;
    }
    __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to open asset: %s", path.c_str());
    return false;
}

void run_inference_loop() {
    JNIEnv* env = nullptr;
    if (!g_jvm || g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;

    if (!g_recognizer) {
        g_jvm->DetachCurrentThread();
        return;
    }

    std::unique_ptr<sherpa_ncnn::Stream> stream = g_recognizer->CreateStream();
    std::string last_text = "";

    while (g_is_running) {
        std::vector<float> audio_data;
        bool do_flush = false;
        {
            std::unique_lock<std::mutex> lock(g_audio_mutex);
            g_audio_cv.wait(lock, []{ return !g_audio_queue.empty() || g_input_finished || !g_is_running; });
            if (!g_is_running) break;
            if (!g_audio_queue.empty()) {
                audio_data = std::move(g_audio_queue.front());
                g_audio_queue.pop();
            } else if (g_input_finished) {
                g_input_finished = false;
                do_flush = true;
            } else {
                continue;
            }
        }

        if (do_flush) {
            std::vector<float> tail_padding(16000 * 32 / 100, 0.0f);
            stream->AcceptWaveform(16000, tail_padding.data(), tail_padding.size());
            stream->InputFinished();
            while (g_recognizer->IsReady(stream.get())) {
                g_recognizer->DecodeStream(stream.get());
            }
            std::string text = g_recognizer->GetResult(stream.get()).text;
            __android_log_print(ANDROID_LOG_INFO, TAG, "InputFinished: result=\"%s\"", text.c_str());
            if (!text.empty()) {
                jstring j_text = env->NewStringUTF(text.c_str());
                env->CallVoidMethod(g_bridge_obj, g_callback_mid, j_text, 0.95f, true);
                env->DeleteLocalRef(j_text);
            } else {
                jstring j_empty = env->NewStringUTF("");
                env->CallVoidMethod(g_bridge_obj, g_callback_mid, j_empty, 0.95f, true);
                env->DeleteLocalRef(j_empty);
            }
            g_flush_done = true;
            g_done_cv.notify_one();
            continue;
        }

        if (audio_data.empty()) continue;

        stream->AcceptWaveform(16000, audio_data.data(), audio_data.size());

        while (g_recognizer->IsReady(stream.get())) {
            g_recognizer->DecodeStream(stream.get());
        }

        std::string text = g_recognizer->GetResult(stream.get()).text;
        bool is_endpoint = g_recognizer->IsEndpoint(stream.get());

        if (!text.empty() && text != last_text) {
            last_text = text;
            jstring j_text = env->NewStringUTF(text.c_str());
            env->CallVoidMethod(g_bridge_obj, g_callback_mid, j_text, 0.95f, is_endpoint);
            env->DeleteLocalRef(j_text);
        }

        /* Do NOT reset on endpoint when g_input_finished is set (batch mode).
         * Resetting would clear the stream before our flush phase, producing empty transcription. */
        if (is_endpoint && !g_input_finished) {
            g_recognizer->Reset(stream.get());
            last_text = "";
        }
    }

    g_jvm->DetachCurrentThread();
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_example_kotlin_1asr_1with_1ncnn_core_media_NcnnNativeBridge_initModelNative(
        JNIEnv* env, jobject thiz, jobject asset_manager,
        jstring encoder_param, jstring encoder_bin,
        jstring decoder_param, jstring decoder_bin,
        jstring joiner_param, jstring joiner_bin,
        jstring tokens, jint num_threads, jboolean use_vulkan) {

    std::lock_guard<std::mutex> lock(g_state_mutex);
    __android_log_print(ANDROID_LOG_INFO, TAG, "Initializing Model...");

    if (g_bridge_obj) {
        env->DeleteGlobalRef(g_bridge_obj);
    }
    g_bridge_obj = env->NewGlobalRef(thiz);

    jclass clazz = env->GetObjectClass(g_bridge_obj);
    g_callback_mid = env->GetMethodID(clazz, "onNativeResult", "(Ljava/lang/String;FZ)V");

    if (!g_callback_mid) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to find onNativeResult method callback");
        return JNI_FALSE;
    }

    g_status = INITIALIZING;

    auto get_utf_str = [&](jstring js) {
        if (!js) return std::string("");
        const char* s = env->GetStringUTFChars(js, nullptr);
        std::string res(s);
        env->ReleaseStringUTFChars(js, s);
        return res;
    };

    AAssetManager* mgr = AAssetManager_fromJava(env, asset_manager);
    if (!mgr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to get AAssetManager");
        return JNI_FALSE;
    }

    sherpa_ncnn::RecognizerConfig config;
    config.feat_config.sampling_rate = 16000;
    config.feat_config.feature_dim = 80;

    config.model_config.encoder_param = get_utf_str(encoder_param);
    config.model_config.encoder_bin = get_utf_str(encoder_bin);
    config.model_config.decoder_param = get_utf_str(decoder_param);
    config.model_config.decoder_bin = get_utf_str(decoder_bin);
    config.model_config.joiner_param = get_utf_str(joiner_param);
    config.model_config.joiner_bin = get_utf_str(joiner_bin);
    config.model_config.tokens = get_utf_str(tokens);
    config.model_config.use_vulkan_compute = use_vulkan;

    config.model_config.encoder_opt.num_threads = num_threads;
    config.model_config.decoder_opt.num_threads = num_threads;
    config.model_config.joiner_opt.num_threads = num_threads;

    // Verify all assets before passing them to the recognizer
    bool all_exists = true;
    all_exists &= verify_asset(mgr, config.model_config.encoder_param);
    all_exists &= verify_asset(mgr, config.model_config.encoder_bin);
    all_exists &= verify_asset(mgr, config.model_config.decoder_param);
    all_exists &= verify_asset(mgr, config.model_config.decoder_bin);
    all_exists &= verify_asset(mgr, config.model_config.joiner_param);
    all_exists &= verify_asset(mgr, config.model_config.joiner_bin);
    all_exists &= verify_asset(mgr, config.model_config.tokens);

    if (!all_exists) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "One or more model assets are missing or inaccessible");
        g_status = ERROR;
        return JNI_FALSE;
    }

    config.decoder_config.method = "greedy_search";
    config.enable_endpoint = true;
    config.endpoint_config.rule1.min_trailing_silence = 1;
    config.endpoint_config.rule2.min_trailing_silence = 0.5;
    config.endpoint_config.rule3.min_utterance_length = 5;

    if (g_recognizer) {
        delete g_recognizer;
        g_recognizer = nullptr;
    }

    try {
        __android_log_print(ANDROID_LOG_INFO, TAG, "Creating Recognizer instance...");
        g_recognizer = new sherpa_ncnn::Recognizer(mgr, config);

        if (!g_recognizer || g_recognizer->GetModel() == nullptr) {
             __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to create Recognizer: Model is null. Check for SherpaMetaData in .param files.");
             if (g_recognizer) delete g_recognizer;
             g_recognizer = nullptr;
             g_status = ERROR;
             return JNI_FALSE;
        }

        __android_log_print(ANDROID_LOG_INFO, TAG, "Sherpa-NCNN Recognizer loaded successfully");
        g_status = IDLE;
        return JNI_TRUE;
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Recognizer creation failed: %s", e.what());
        g_status = ERROR;
        return JNI_FALSE;
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Recognizer creation failed with unknown error");
        g_status = ERROR;
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_example_kotlin_1asr_1with_1ncnn_core_media_NcnnNativeBridge_startInference(JNIEnv* env, jobject thiz) {
    std::lock_guard<std::mutex> t_lock(g_thread_mutex);
    {
        std::lock_guard<std::mutex> s_lock(g_state_mutex);
        if (g_is_running) return;
        if (!g_recognizer) {
             __android_log_print(ANDROID_LOG_ERROR, TAG, "Cannot start inference: Recognizer not initialized");
             return;
        }
        g_is_running = true;
    }
    if (g_inference_thread.joinable()) g_inference_thread.join();
    g_inference_thread = std::thread(run_inference_loop);
}

JNIEXPORT void JNICALL
Java_com_example_kotlin_1asr_1with_1ncnn_core_media_NcnnNativeBridge_stopInference(JNIEnv* env, jobject thiz) {
    {
        std::lock_guard<std::mutex> lock(g_state_mutex);
        if (!g_is_running) return;
        g_is_running = false;
    }
    g_audio_cv.notify_all();
    std::lock_guard<std::mutex> t_lock(g_thread_mutex);
    if (g_inference_thread.joinable()) g_inference_thread.join();
}

JNIEXPORT void JNICALL
Java_com_example_kotlin_1asr_1with_1ncnn_core_media_NcnnNativeBridge_signalInputFinished(JNIEnv* env, jobject thiz) {
    g_flush_done = false;
    {
        std::lock_guard<std::mutex> lock(g_audio_mutex);
        g_input_finished = true;
    }
    g_audio_cv.notify_one();
    std::unique_lock<std::mutex> lock(g_done_mutex);
    g_done_cv.wait_for(lock, std::chrono::seconds(10), []{ return g_flush_done || !g_is_running; });
}

JNIEXPORT void JNICALL
Java_com_example_kotlin_1asr_1with_1ncnn_core_media_NcnnNativeBridge_feedAudioData(JNIEnv* env, jobject thiz, jshortArray data) {
    jsize len = env->GetArrayLength(data);
    jshort* body = env->GetShortArrayElements(data, nullptr);
    if (!body) return;

    std::vector<float> audio_vec(len);
    for (int i = 0; i < len; i++) {
        audio_vec[i] = body[i] / 32768.0f;
    }

    env->ReleaseShortArrayElements(data, body, JNI_ABORT);
    {
        std::lock_guard<std::mutex> lock(g_audio_mutex);
        g_audio_queue.push(std::move(audio_vec));
    }
    g_audio_cv.notify_one();
}

JNIEXPORT jint JNICALL
Java_com_example_kotlin_1asr_1with_1ncnn_core_media_NcnnNativeBridge_getStatus(JNIEnv* env, jobject thiz) {
    return (jint)g_status.load();
}

} // extern "C"
