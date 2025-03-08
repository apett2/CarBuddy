#include <oboe/Oboe.h>
#include "kissfft/kiss_fft.h"
#include "kissfft/kiss_fftr.h"
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <cmath>

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

static void DetachCurrentThread() {
    gJavaVM->DetachCurrentThread();
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

public:
    AudioEngine(JNIEnv* env, jobject obj) : env(env), javaObject(obj ? env->NewGlobalRef(obj) : nullptr), dataReady(false) {
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
        for (int i = 0; i < 22; i++) {
            lowFreqMagnitude[i] = 0.0f;
            lowFreqBuffer[i] = 0.0f;
        }
        for (int i = 0; i < 1024; i++) {
            highFreqMagnitude[i] = 0.0f;
            highFreqBuffer[i] = 0.0f;
        }

        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Input)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setSampleRate(44100)
                ->setChannelCount(oboe::ChannelCount::Mono)
                ->setFormat(oboe::AudioFormat::Float)
                ->setCallback(this)
                ->openManagedStream(inputStream);
        inputStream->requestStart();
    }

    ~AudioEngine() {
        if (inputStream) {
            inputStream->requestStop();
            inputStream->close();
        }
        kiss_fftr_free(fftCfg);
        delete[] fftOutput;
        delete[] audioBuffer;
        delete[] lowFreqMagnitude;
        delete[] highFreqMagnitude;
        delete[] lowFreqBuffer;
        delete[] highFreqBuffer;
        if (javaObject) {
            env->DeleteGlobalRef(javaObject);
        }
        JNIEnv* currentEnv = GetJNIEnv();
        if (currentEnv && gJavaVM) {
            int getEnvStat = gJavaVM->GetEnv((void**)&currentEnv, JNI_VERSION_1_6); // Fixed typo: ¤tEnv -> currentEnv
            if (getEnvStat == JNI_EDETACHED) {
                LOGI("Thread already detached, skipping DetachCurrentThread");
            } else if (getEnvStat == JNI_OK) {
                gJavaVM->DetachCurrentThread();
                LOGI("Thread detached successfully");
            } else {
                LOGE("Failed to check thread attachment status: %d", getEnvStat);
            }
        } else {
            LOGE("No JNIEnv or JavaVM available for detachment");
        }
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override {
        float* input = static_cast<float*>(audioData);
        int32_t totalSamples = numFrames * stream->getChannelCount();

        float gain = 20.0f;
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
        const float sampleRate = 44100.0f;
        const float binWidth = sampleRate / sampleSize; // ~21.53 Hz/bin
        const int lowFreqBins = 6; // ~40-129 Hz (bins 2-6)
        const int highFreqStart = 7; // ~150 Hz+
        const float lowSensitivity = 200.0f; // Reduced from 500.0f
        const float highSensitivity = 50.0f; // Reduced from 100.0f
        float highFreqMax = 0.0f;

        for (int i = 1; i < sampleSize / 2; i++) {
            float real = fftOutput[i].r;
            float imag = fftOutput[i].i;
            float magnitude = sqrtf(real * real + imag * imag) / sampleSize;
            if (i >= 2 && i < lowFreqBins) { // Narrow to 40-129 Hz
                magnitude *= lowSensitivity;
            } else if (i >= highFreqStart && (i - highFreqStart) < 1024) {
                magnitude *= highSensitivity;
            } else {
                magnitude = 0.0f; // Ignore outside ranges
            }
            magnitude = std::min(magnitude, 1000.0f);

            if (i >= 2 && i - 2 < 22 && i < lowFreqBins) {
                lowFreqMagnitude[i - 2] = magnitude;
            } else if (i >= highFreqStart && (i - highFreqStart) < 1024) {
                highFreqMagnitude[i - highFreqStart] = magnitude;
                highFreqMax = std::max(highFreqMax, magnitude);
            }
        }
        LOGI("LowFreq[0]: %f, HighFreq[0]: %f, HighFreq[Max]: %f",
             lowFreqMagnitude[0], highFreqMagnitude[0], highFreqMax);
    }

    void processFrequenciesForJNI(JNIEnv* env, jfloatArray lowFreq, jfloatArray highFreq) {
        const int lowFreqBins = 22;
        const int highFreqBins = 1024;

        jfloat* lowFreqData = env->GetFloatArrayElements(lowFreq, nullptr);
        jfloat* highFreqData = env->GetFloatArrayElements(highFreq, nullptr);

        pthread_mutex_lock(&audioMutex);
        while (!dataReady) {
            pthread_cond_wait(&audioCond, &audioMutex); // Wait for fresh data
        }
        if (lowFreqData && highFreqData) {
            for (int i = 0; i < lowFreqBins; i++) {
                lowFreqData[i] = std::max(0.0f, std::min(lowFreqBuffer[i], 1000.0f));
            }
            for (int i = 0; i < highFreqBins; i++) {
                highFreqData[i] = std::max(0.0f, std::min(highFreqBuffer[i], 1000.0f));
            }
            LOGI("JNI Transfer - LowFreq[0]: %f, HighFreq[0]: %f", lowFreqData[0], highFreqData[0]);
            dataReady = false; // Reset after transfer
        } else {
            LOGE("Failed to get float array elements: lowFreqData=%p, highFreqData=%p", lowFreqData, highFreqData);
        }
        pthread_mutex_unlock(&audioMutex);

        if (lowFreqData) env->ReleaseFloatArrayElements(lowFreq, lowFreqData, 0);
        if (highFreqData) env->ReleaseFloatArrayElements(highFreq, highFreqData, 0);
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
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_alexpettit_carbuddy_MainActivity_startAudioEngine(JNIEnv* env, jobject instance, jlong instanceId, jlongArray ptrArray) {
    if (!instance) {
        LOGE("Instance is null in startAudioEngine");
        return 0;
    }
    AudioEngine* engine = new AudioEngine(env, instance);
    if (ptrArray && env->GetArrayLength(ptrArray) > 0) {
        jlong* ptr = env->GetLongArrayElements(ptrArray, nullptr);
        *ptr = reinterpret_cast<jlong>(engine);
        env->ReleaseLongArrayElements(ptrArray, ptr, 0);
    }
    jclass clazz = env->GetObjectClass(instance);
    jfieldID fieldId = env->GetFieldID(clazz, "audioEnginePtr", "J");
    if (fieldId) {
        env->SetLongField(instance, fieldId, reinterpret_cast<jlong>(engine));
    }
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_alexpettit_carbuddy_MainActivity_stopAudioEngine(JNIEnv* env, jobject instance, jlong instanceId, jlong ptr) {
    if (!instance) {
        LOGE("Instance is null in stopAudioEngine");
        return;
    }
    delete reinterpret_cast<AudioEngine*>(ptr);
    jclass clazz = env->GetObjectClass(instance);
    jfieldID fieldId = env->GetFieldID(clazz, "audioEnginePtr", "J");
    if (fieldId) {
        env->SetLongField(instance, fieldId, 0L);
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