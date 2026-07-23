package com.example.opendash.data

import com.example.opendash.util.DebugLog
import org.webrtc.ExternalAudioProcessingFactory
import java.nio.ByteBuffer

/**
 * RNNoise (xiph, BSD) noise suppression for the intercom mic, run as WebRTC's capture
 * post-processor. It sits after AEC/AGC in the APM pipeline and replaces WebRTC's own
 * NS (disabled in [IntercomEngine] — stacking two suppressors causes musical-noise
 * artifacts). RNN-based suppression handles the non-stationary noise a bike actually
 * makes — wind gusts, tyre roar, passing traffic — far better than spectral NS.
 *
 * The native hook delivers a direct ByteBuffer over the APM's fullband float channel,
 * one 10 ms frame per tick, int16-range values, processed in place with zero copies.
 * At 48 kHz (phone mic) that's exactly one RNNoise frame; at 16 kHz (Bluetooth SCO —
 * the helmet-headset norm) the shim resamples 3x around the model. ~1% of one core.
 */
class RnnoiseProcessor : ExternalAudioProcessingFactory.AudioProcessing {

    @Volatile private var state = 0L
    @Volatile private var engaged = false

    override fun initialize(sampleRateHz: Int, numChannels: Int) {
        // 48 kHz is RNNoise's native rate; 16 kHz (Bluetooth SCO — the helmet-headset
        // case) goes through the shim's 3x resampler. Anything else passes through.
        engaged = (sampleRateHz == 48_000 || sampleRateHz == 16_000) && numChannels == 1
        if (engaged && state == 0L) state = nativeCreate()
        if (state != 0L) nativeReset(state)
        DebugLog.i(TAG) { "initialize rate=$sampleRateHz ch=$numChannels engaged=$engaged" }
    }

    override fun reset(newRate: Int) {
        engaged = newRate == 48_000 || newRate == 16_000
        if (state != 0L) nativeReset(state)
        DebugLog.i(TAG) { "reset rate=$newRate engaged=$engaged" }
    }

    override fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        if (!engaged || state == 0L) return
        // 480 floats = 48 kHz fullband frame, 160 = 16 kHz frame; the shim handles
        // both and leaves any other size untouched.
        nativeProcess(state, buffer, buffer.capacity() / BYTES_PER_FLOAT)
    }

    fun release() {
        val s = state
        state = 0L
        engaged = false
        if (s != 0L) nativeDestroy(s)
    }

    companion object {
        private const val TAG = "Rnnoise"
        private const val BYTES_PER_FLOAT = 4

        init {
            System.loadLibrary("rnnoise_jni")
        }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeDestroy(ptr: Long)
        @JvmStatic private external fun nativeReset(ptr: Long)
        @JvmStatic private external fun nativeProcess(ptr: Long, buffer: ByteBuffer, numSamples: Int): Float
    }
}
