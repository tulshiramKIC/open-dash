cmake_minimum_required(VERSION 3.22)
project(opendash_audio C)

# RNNoise (xiph, BSD) + a minimal JNI shim. Processes the intercom's 10 ms /
# 480-sample @48 kHz capture frames in place — see RnnoiseProcessor.kt.
add_library(
    rnnoise_jni SHARED
    rnnoise_jni.c
    rnnoise/denoise.c
    rnnoise/rnn.c
    rnnoise/rnn_data.c
    rnnoise/rnn_reader.c
    rnnoise/pitch.c
    rnnoise/kiss_fft.c
    rnnoise/celt_lpc.c
)

target_include_directories(rnnoise_jni PRIVATE rnnoise)
# No -ffast-math: opus-derived arch.h forbids it (NaN handling matters on a
# live mic). -O2 alone keeps the per-frame cost at ~1% of a core.
target_compile_options(rnnoise_jni PRIVATE -O2 -fvisibility=hidden)
target_link_libraries(rnnoise_jni log)
