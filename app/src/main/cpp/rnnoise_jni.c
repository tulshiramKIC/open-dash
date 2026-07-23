/*
 * JNI shim for RNNoise. The WebRTC external capture post-processor hands Java a
 * direct ByteBuffer wrapping the APM's fullband float channel (values in int16
 * range), processed in place — zero copies.
 *
 * Two rates occur in practice:
 *   48 kHz / 480-sample frames — phone mic path; RNNoise's native frame.
 *   16 kHz / 160-sample frames — Bluetooth SCO (helmet headsets) and some
 *     communication-mode devices. RNNoise is 48 kHz-only, so these frames are
 *     upsampled 3x (zero-stuff + FIR), denoised, and decimated back — the FIR
 *     (47-tap Kaiser sinc, 8 kHz cutoff) adds under 1 ms of group delay.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "rnnoise.h"

#define FRAME48 480
#define FRAME16 160
#define UP 3
#define TAPS 47

/* Kaiser(beta=7)-windowed sinc, fc = 8 kHz @ 48 kHz, unity DC gain. */
static const float kFir[TAPS] = {
    -0.00007109f, -0.00017444f, 0.00000000f, 0.00058165f, 0.00092736f, -0.00000000f,
    -0.00203285f, -0.00285582f, 0.00000000f, 0.00523492f, 0.00688710f, -0.00000000f,
    -0.01143536f, -0.01451349f, 0.00000000f, 0.02301830f, 0.02897794f, -0.00000000f,
    -0.04722539f, -0.06243917f, 0.00000000f, 0.13448707f, 0.27397198f, 0.33332256f,
    0.27397198f, 0.13448707f, 0.00000000f, -0.06243917f, -0.04722539f, -0.00000000f,
    0.02897794f, 0.02301830f, 0.00000000f, -0.01451349f, -0.01143536f, -0.00000000f,
    0.00688710f, 0.00523492f, 0.00000000f, -0.00285582f, -0.00203285f, -0.00000000f,
    0.00092736f, 0.00058165f, 0.00000000f, -0.00017444f, -0.00007109f,
};

typedef struct {
    DenoiseState *st;
    /* 48 kHz-domain FIR histories (previous TAPS-1 samples) for streaming. */
    float hist_up[TAPS - 1];
    float hist_dn[TAPS - 1];
} NsHandle;

/* Streaming FIR: convolve `in` (n samples) with kFir, `hist` carries state. */
static void fir_block(const float *in, float *out, int n, float *hist) {
    for (int i = 0; i < n; i++) {
        float acc = 0.0f;
        for (int k = 0; k < TAPS; k++) {
            int idx = i - k;
            float s = (idx >= 0) ? in[idx] : hist[TAPS - 1 + idx];
            acc += kFir[k] * s;
        }
        out[i] = acc;
    }
    if (n >= TAPS - 1) {
        memcpy(hist, in + n - (TAPS - 1), (TAPS - 1) * sizeof(float));
    } else {
        memmove(hist, hist + n, (TAPS - 1 - n) * sizeof(float));
        memcpy(hist + TAPS - 1 - n, in, n * sizeof(float));
    }
}

JNIEXPORT jlong JNICALL
Java_com_example_opendash_data_RnnoiseProcessor_nativeCreate(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    NsHandle *h = (NsHandle *)calloc(1, sizeof(NsHandle));
    if (!h) return 0;
    h->st = rnnoise_create(NULL);   /* NULL → built-in model */
    if (!h->st) { free(h); return 0; }
    return (jlong)(intptr_t)h;
}

JNIEXPORT void JNICALL
Java_com_example_opendash_data_RnnoiseProcessor_nativeDestroy(JNIEnv *env, jclass clazz, jlong ptr) {
    (void)env; (void)clazz;
    NsHandle *h = (NsHandle *)(intptr_t)ptr;
    if (!h) return;
    if (h->st) rnnoise_destroy(h->st);
    free(h);
}

/* Clear resampler state on a rate change so stale history doesn't click. */
JNIEXPORT void JNICALL
Java_com_example_opendash_data_RnnoiseProcessor_nativeReset(JNIEnv *env, jclass clazz, jlong ptr) {
    (void)env; (void)clazz;
    NsHandle *h = (NsHandle *)(intptr_t)ptr;
    if (!h) return;
    memset(h->hist_up, 0, sizeof(h->hist_up));
    memset(h->hist_dn, 0, sizeof(h->hist_dn));
}

/* Returns the model's speech probability for the frame (0..1); useful for logging. */
JNIEXPORT jfloat JNICALL
Java_com_example_opendash_data_RnnoiseProcessor_nativeProcess(JNIEnv *env, jclass clazz,
                                                              jlong ptr, jobject buffer,
                                                              jint numSamples) {
    (void)clazz;
    NsHandle *h = (NsHandle *)(intptr_t)ptr;
    float *data = (float *)(*env)->GetDirectBufferAddress(env, buffer);
    if (!h || !h->st || !data) return 0.0f;

    if (numSamples == FRAME48) {
        return rnnoise_process_frame(h->st, data, data);
    }

    if (numSamples == FRAME16) {
        float up[FRAME48], f48[FRAME48], dn[FRAME48];
        /* Zero-stuff x3 (gain 3 compensates the stuffed zeros). */
        for (int i = 0; i < FRAME48; i++) {
            up[i] = (i % UP == 0) ? data[i / UP] * (float)UP : 0.0f;
        }
        fir_block(up, f48, FRAME48, h->hist_up);      /* anti-image */
        float vad = rnnoise_process_frame(h->st, f48, f48);
        fir_block(f48, dn, FRAME48, h->hist_dn);      /* anti-alias */
        for (int i = 0; i < FRAME16; i++) data[i] = dn[i * UP];
        return vad;
    }

    return 0.0f;   /* unexpected frame size → untouched */
}
