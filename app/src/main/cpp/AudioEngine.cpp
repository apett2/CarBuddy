#include <oboe/Oboe.h>
#include "kissfft/kiss_fft.h"
#include "kissfft/kiss_fftr.h"
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <cmath>
#include <thread>
#include <chrono>

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static JavaVM* gJavaVM = nullptr;
static pthread_mutex_t audioMutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t audioCond = PTHREAD_COND_INITIALIZER;

static JNIEnv* GetJNIEnv() {
    JNIEnv* env = nullptr;
    int getEnvStat = gJavaVM->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        LOGI("Thread is not attached, attaching now...");
        if (gJavaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("Failed to attach thread to JVM");
            return nullptr;
        }
    } else if (getEnvStat != JNI_OK) {
        LOGE("Failed to get JNIEnv, status: %d", getEnvStat);
        return nullptr;
    }
    return env;
}

class AudioEngine : public oboe::AudioStreamCallback {
private:
    oboe::ManagedStream inputStream;
    kiss_fftr_cfg fftCfg;
    kiss_fft_cpx* fftOutput;
    float* audioBuffer;
    const int sampleSize = 2048;
    float* lowFreqMagnitude;
    float* highFreqMagnitude;
    float* lowFreqBuffer;
    float* highFreqBuffer;
    JNIEnv* env;
    jobject javaObject;
    bool dataReady;
    bool isStreamRunning;

public:
    AudioEngine(JNIEnv* env, jobject obj) : env(env), javaObject(obj ? env->NewGlobalRef(obj) : nullptr), dataReady(false), isStreamRunning(false) {
        if (!javaObject) {
            LOGE("javaObject is null in AudioEngine constructor");
            return;
        }

        fftCfg = kiss_fftr_alloc(sampleSize, 0, nullptr, nullptr);
        fftOutput = new kiss_fft_cpx[sampleSize / 2 + 1];
        audioBuffer = new float[sampleSize];
        lowFreqMagnitude = new float[22];
        highFreqMagnitude = new float[1024];
        lowFreqBuffer = new float[22];
        highFreqBuffer = new float[1024];
        resetBuffers();
        LOGI("AudioEngine constructed at %p", this);
    }

    ~AudioEngine() {
        LOGI("Destroying AudioEngine at %p", this);
        stopStream();
        kiss_fftr_free(fftCfg);
        delete[] fftOutput;
        delete[] audioBuffer;
        delete[] lowFreqMagnitude;
        delete[] highFreqMagnitude;
        delete[] lowFreqBuffer;
        delete[] highFreqBuffer;
        if (javaObject) {
            JNIEnv* currentEnv = GetJNIEnv();
            if (currentEnv) {
                currentEnv->DeleteGlobalRef(javaObject);
                LOGI("Deleted global ref for javaObject");
            } else {
                LOGE("Failed to get JNIEnv to delete javaObject ref");
            }
        }
    }

    bool startStream() {
        if (isStreamRunning) {
            LOGI("Stream already running, skipping start");
            return true;
        }

        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Input)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSampleRate(48000) // Changed to 48000 Hz, more commonly supported
                ->setChannelCount(oboe::ChannelCount::Mono)
                ->setFormat(oboe::AudioFormat::Float)
                ->setCallback(this);

        // Retry logic for stream opening
        const int maxRetries = 3;
        const int retryDelayMs = 500;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            oboe::Result result = builder.openManagedStream(inputStream);
            if (result == oboe::Result::OK) {
                break;
            }
            LOGE("Attempt %d/%d: Failed to open audio stream: %s", attempt, maxRetries, oboe::convertToText(result));
            if (attempt == maxRetries) {
                return false;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(retryDelayMs));
        }

        // Verify stream state before starting
        if (!inputStream) {
            LOGE("Audio stream is null after open attempt");
            return false;
        }

        oboe::Result result = inputStream->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Failed to start audio stream: %s", oboe::convertToText(result));
            inputStream->close();
            return false;
        }

        isStreamRunning = true;
        LOGI("Audio stream started successfully");
        return true;
    }

    void stopStream() {
        if (isStreamRunning && inputStream) {
            oboe::Result result = inputStream->requestStop();
            if (result != oboe::Result::OK) {
                LOGE("Failed to stop audio stream: %s", oboe::convertToText(result));
            }
            result = inputStream->close();
            if (result != oboe::Result::OK) {
                LOGE("Failed to close audio stream: %s", oboe::convertToText(result));
            }
            isStreamRunning = false;
            LOGI("Audio stream stopped and closed");
        } else {
            LOGI("No audio stream to stop or already stopped");
        }
        // Reset buffers to ensure no stale data
        resetBuffers();
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override {
        float* input = static_cast<float*>(audioData);
        int32_t totalSamples = numFrames * stream->getChannelCount();

        float gain = 5.0f; // Reduced gain from 20.0f to 5.0f to prevent saturation
        for (int i = 0; i < totalSamples && i < sampleSize; i++) {
            audioBuffer[i] = input[i] * gain;
        }

        pthread_mutex_lock(&audioMutex);
        kiss_fftr(fftCfg, audioBuffer, fftOutput);
        processFrequencies();
        for (int i = 0; i < 22; i++) lowFreqBuffer[i] = lowFreqMagnitude[i];
        for (int i = 0; i < 1024; i++) highFreqBuffer[i] = highFreqMagnitude[i];
        dataReady = true;
        pthread_cond_signal(&audioCond); // Signal data is ready
        pthread_mutex_unlock(&audioMutex);

        return oboe::DataCallbackResult::Continue;
    }

    void processFrequencies() {
        const float sampleRate = 48000.0f; // Updated to match new sample rate
        const float binWidth = sampleRate / sampleSize; // ~23.44 Hz/bin
        const int lowFreqBins = 6; // ~47-140 Hz (bins 2-6)
        const int highFreqStart = 7; // ~164 Hz+
        const float lowSensitivity = 200.0f;
        const float highSensitivity = 50.0f;
        float highFreqMax = 0.0f;

        for (int i = 1; i < sampleSize / 2; i++) {
            float real = fftOutput[i].r;
            float imag = fftOutput[i].i;
            float magnitude = sqrtf(real * real + imag * imag) / sampleSize;
            if (i >= 2 && i < lowFreqBins) { // 47-140 Hz
                magnitude *= lowSensitivity;
            } else if (i >= highFreqStart && (i - highFreqStart) < 1024) {
                magnitude *= highSensitivity;
            } else {
                magnitude = 0.0f;
            }
            // Lower cap to prevent saturation
            magnitude = std::min(magnitude, 50.0f);

            if (i >= 2 && i - 2 < 22 && i < lowFreqBins) {
                lowFreqMagnitude[i - 2] = magnitude;
            } else if (i >= highFreqStart && (i - highFreqStart) < 1024) {
                highFreqMagnitude[i - highFreqStart] = magnitude;
                highFreqMax = std::max(highFreqMax, magnitude);
            }
        }
        LOGI("LowFreq[0]: %f, HighFreq[0]: %f, HighFreq[Max]: %f", lowFreqMagnitude[0], highFreqMagnitude[0], highFreqMax);
    }

    void processFrequenciesForJNI(JNIEnv* env, jfloatArray lowFreq, jfloatArray highFreq) {
        const int lowFreqBins = 22;
        const int highFreqBins = 1024;

        jfloat* lowFreqData = env->GetFloatArrayElements(lowFreq, nullptr);
        jfloat* highFreqData = env->GetFloatArrayElements(highFreq, nullptr);

        if (!lowFreqData || !highFreqData) {
            LOGE("Failed to get float array elements: lowFreqData=%p, highFreqData=%p", lowFreqData, highFreqData);
            if (lowFreqData) env->ReleaseFloatArrayElements(lowFreq, lowFreqData, JNI_ABORT);
            if (highFreqData) env->ReleaseFloatArrayElements(highFreq, highFreqData, JNI_ABORT);
            return;
        }

        pthread_mutex_lock(&audioMutex);
        const int maxWaitMs = 200; // Increased timeout to 200ms
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_sec += maxWaitMs / 1000;
        ts.tv_nsec += (maxWaitMs % 1000) * 1000000;
        if (ts.tv_nsec >= 1000000000) {
            ts.tv_sec += 1;
            ts.tv_nsec -= 1000000000;
        }

        int waitResult = pthread_cond_timedwait(&audioCond, &audioMutex, &ts);
        if (waitResult == ETIMEDOUT) {
            LOGW("Timed out waiting for dataReady, using stale data");
        } else if (waitResult != 0) {
            LOGE("pthread_cond_timedwait failed with error: %d", waitResult);
        }

        if (dataReady) {
            for (int i = 0; i < lowFreqBins; i++) {
                lowFreqData[i] = std::max(0.0f, std::min(lowFreqBuffer[i], 1000.0f));
            }
            for (int i = 0; i < highFreqBins; i++) {
                highFreqData[i] = std::max(0.0f, std::min(highFreqBuffer[i], 1000.0f));
            }
            LOGI("JNI Transfer - LowFreq[0]: %f, HighFreq[0]: %f", lowFreqData[0], highFreqData[0]);
            dataReady = false; // Reset after transfer
        } else {
            LOGW("No fresh data available, buffers remain unchanged");
        }
        pthread_mutex_unlock(&audioMutex);

        env->ReleaseFloatArrayElements(lowFreq, lowFreqData, 0);
        env->ReleaseFloatArrayElements(highFreq, highFreqData, 0);
    }

    void resetBuffers() {
        pthread_mutex_lock(&audioMutex);
        for (int i = 0; i < 22; i++) {
            lowFreqMagnitude[i] = 0.0f;
            lowFreqBuffer[i] = 0.0f;
        }
        for (int i = 0; i < 1024; i++) {
            highFreqMagnitude[i] = 0.0f;
            highFreqBuffer[i] = 0.0f;
        }
        dataReady = false;
        pthread_mutex_unlock(&audioMutex);
        LOGI("Buffers reset");
    }
};

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    gJavaVM = vm;
    JNIEnv* env;
    if (vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        LOGE("Failed to get JNIEnv in JNI_OnLoad");
        return -1;
    }
    LOGI("JNI_OnLoad completed, JavaVM stored");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_alexpettit_carbuddy_MainActivity_startAudioEngine(JNIEnv* env, jobject instance, jlong instanceId, jlongArray ptrArray) {
    if (!instance) {
        LOGE("Instance is null in startAudioEngine");
        return 0;
    }
    AudioEngine* engine = new AudioEngine(env, instance);
    if (engine && engine->startStream()) {
        if (ptrArray && env->GetArrayLength(ptrArray) > 0) {
            jlong* ptr = env->GetLongArrayElements(ptrArray, nullptr);
            *ptr = reinterpret_cast<jlong>(engine);
            env->ReleaseLongArrayElements(ptrArray, ptr, 0);
        }
        jclass clazz = env->GetObjectClass(instance);
        jfieldID fieldId = env->GetFieldID(clazz, "audioEnginePtr", "J");
        if (fieldId) {
            env->SetLongField(instance, fieldId, reinterpret_cast<jlong>(engine));
        } else {
            LOGE("Failed to set audioEnginePtr field");
        }
        LOGI("AudioEngine started, ptr=%p", engine);
        return reinterpret_cast<jlong>(engine);
    } else {
        LOGE("Failed to initialize AudioEngine");
        delete engine;
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_alexpettit_carbuddy_MainActivity_stopAudioEngine(JNIEnv* env, jobject instance, jlong instanceId, jlong ptr) {
    if (!instance) {
        LOGE("Instance is null in stopAudioEngine");
        return;
    }
    AudioEngine* engine = reinterpret_cast<AudioEngine*>(ptr);
    if (engine) {
        LOGI("Stopping AudioEngine at %p", engine);
        engine->stopStream();
        delete engine;
        jclass clazz = env->GetObjectClass(instance);
        jfieldID fieldId = env->GetFieldID(clazz, "audioEnginePtr", "J");
        if (fieldId) {
            env->SetLongField(instance, fieldId, 0L);
            LOGI("audioEnginePtr reset to 0");
        } else {
            LOGE("Failed to reset audioEnginePtr field");
        }
    } else {
        LOGE("AudioEngine pointer is null in stopAudioEngine");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_alexpettit_carbuddy_MainActivity_updateFrequencies(JNIEnv* env, jobject instance, jlong instanceId, jlong ptr, jfloatArray lowFreq, jfloatArray highFreq) {
    if (!instance) {
        LOGE("Instance is null in updateFrequencies");
        return;
    }
    AudioEngine* engine = reinterpret_cast<AudioEngine*>(ptr);
    if (engine) {
        engine->processFrequenciesForJNI(env, lowFreq, highFreq);
    } else {
        LOGE("AudioEngine instance not found for updateFrequencies");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_alexpettit_carbuddy_MainActivity_resetBuffers(JNIEnv* env, jobject instance, jlong ptr) {
    if (!instance) {
        LOGE("Instance is null in resetBuffers");
        return;
    }
    AudioEngine* engine = reinterpret_cast<AudioEngine*>(ptr);
    if (engine) {
        engine->resetBuffers();
    } else {
        LOGE("AudioEngine instance not found for resetBuffers");
    }
}