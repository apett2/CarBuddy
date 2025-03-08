#include <jni.h>
#include <oboe/Oboe.h>
#include <android/log.h>
#include <cmath> // for fmax, sqrt

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "CarBuddyNative", __VA_ARGS__)

// Simple Oboe audio callback that measures amplitude and divides audio into rough “bass/mid/treble.”
class AudioCallback : public oboe::AudioStreamCallback {
public:
    AudioCallback() : amplitude(0.0f), bass(0.0f), mid(0.0f), treble(0.0f) {}

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        float* floatData = static_cast<float*>(audioData);
        int32_t totalSamples = numFrames * oboeStream->getChannelCount();

        // 1) Apply a gain to amplify the input signal
        float gain = 5.0f;
        for (int i = 0; i < totalSamples; i++) {
            floatData[i] *= gain;
        }

        // 2) Calculate a basic RMS amplitude
        float sumSquares = 0.0f;
        for (int i = 0; i < totalSamples; i++) {
            sumSquares += floatData[i] * floatData[i];
        }
        amplitude = std::sqrt(sumSquares / totalSamples) * 50.0f; // Scaled up

        // 3) Very naive frequency band splitting: bass / mid / treble
        int third = totalSamples / 3;
        float bassSum = 0.0f, midSum = 0.0f, trebleSum = 0.0f;
        for (int i = 0; i < third; i++) {
            bassSum += std::fabs(floatData[i]);
        }
        for (int i = third; i < 2 * third; i++) {
            midSum += std::fabs(floatData[i]);
        }
        for (int i = 2 * third; i < totalSamples; i++) {
            trebleSum += std::fabs(floatData[i]);
        }
        bass   = (bassSum / third)               * 20.0f;
        mid    = (midSum / third)                * 20.0f;
        treble = (trebleSum / (totalSamples - 2*third)) * 20.0f;

        // 4) Enforce a minimum threshold so we never get pure zero
        const float minVal = 0.1f;
        amplitude = fmax(amplitude, minVal);
        bass      = fmax(bass,      minVal);
        mid       = fmax(mid,       minVal);
        treble    = fmax(treble,    minVal);

        return oboe::DataCallbackResult::Continue;
    }

    float getAmplitude() const { return amplitude; }
    float getBass()       const { return bass; }
    float getMid()        const { return mid; }
    float getTreble()     const { return treble; }

private:
    float amplitude;
    float bass;
    float mid;
    float treble;
};

// Global singletons for simplicity
static AudioCallback*      audioCallback = nullptr;
static oboe::AudioStream*  audioStream   = nullptr;

/**
 * startAudioEngine(...):
 * Matches Kotlin signature:
 *   private external fun startAudioEngine(instance: Long, ptr: LongArray): Long
 */
extern "C"
JNIEXPORT jlong JNICALL
Java_com_alexpettit_carbuddy_MainActivity_startAudioEngine(
        JNIEnv *env,
        jobject thiz,    // "this" object (MainActivity)
        jlong instance,  // from Kotlin, e.g. hashCode().toLong()
        jlongArray ptr   // 1-element array to store a native pointer
) {
    if (audioStream) {
        // Already running, just return the existing pointer
        jlong existingPtr = reinterpret_cast<jlong>(audioStream);
        env->SetLongArrayRegion(ptr, 0, 1, &existingPtr);
        return existingPtr;
    }

    // Build an Oboe input stream
    oboe::AudioStreamBuilder builder;
    audioCallback = new AudioCallback();
    builder.setDirection(oboe::Direction::Input)
            ->setSampleRate(44100)
            ->setChannelCount(oboe::ChannelCount::Mono)
            ->setFormat(oboe::AudioFormat::Float)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setCallback(audioCallback);

    oboe::Result result = builder.openStream(&audioStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open Oboe stream: %s", oboe::convertToText(result));
        delete audioCallback;
        audioCallback = nullptr;
        jclass exClass = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exClass, oboe::convertToText(result));
        return 0;
    }

    // Start the stream
    result = audioStream->start();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start Oboe stream: %s", oboe::convertToText(result));
        audioStream->close();
        delete audioCallback;
        audioCallback = nullptr;
        audioStream   = nullptr;
        jclass exClass = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exClass, oboe::convertToText(result));
        return 0;
    }

    // Convert the C++ pointer to jlong, store in ptr[0], and return
    jlong cPointer = reinterpret_cast<jlong>(audioStream);
    env->SetLongArrayRegion(ptr, 0, 1, &cPointer);
    return cPointer;
}

/**
 * stopAudioEngine(...):
 * Matches Kotlin signature:
 *   private external fun stopAudioEngine(instance: Long, ptr: Long)
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_alexpettit_carbuddy_MainActivity_stopAudioEngine(
        JNIEnv *env,
        jobject thiz,
        jlong instance,
        jlong ptr
) {
    if (audioStream) {
        audioStream->stop();
        audioStream->close();
        delete audioCallback;
        audioCallback = nullptr;
        audioStream   = nullptr;
    }
}

/**
 * updateFrequencies(...):
 * Matches Kotlin signature:
 *   private external fun updateFrequencies(
 *       instance: Long, ptr: Long,
 *       lowFreq: FloatArray, highFreq: FloatArray
 *   )
 *
 * For demonstration, we fill lowFreq[] with “bass” data
 * and highFreq[] with “treble” data. Real FFT-based code
 * would do more complex analysis.
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_alexpettit_carbuddy_MainActivity_updateFrequencies(
        JNIEnv *env,
        jobject thiz,
        jlong instance,
        jlong ptr,
        jfloatArray lowFreq,
        jfloatArray highFreq
) {
    if (!audioCallback) {
        return;
    }

    jsize lowSize  = env->GetArrayLength(lowFreq);
    jsize highSize = env->GetArrayLength(highFreq);

    // ✅ Instead of stack allocation, use heap allocation
    float* lowBuffer = new float[lowSize];
    float* highBuffer = new float[highSize];

    float bass   = audioCallback->getBass();
    float mid    = audioCallback->getMid();
    float treble = audioCallback->getTreble();

    for (int i = 0; i < lowSize; i++) {
        lowBuffer[i] = bass;
    }
    for (int i = 0; i < highSize; i++) {
        highBuffer[i] = treble;
    }

    env->SetFloatArrayRegion(lowFreq, 0, lowSize, lowBuffer);
    env->SetFloatArrayRegion(highFreq, 0, highSize, highBuffer);

    // ✅ Free heap memory to avoid leaks
    delete[] lowBuffer;
    delete[] highBuffer;
}
