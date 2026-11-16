#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <cstring>
#include <cstdint>
#include <algorithm>
#include <android/log.h>
#include <media/NdkMediaExtractor.h>
#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "DroidMedia", __VA_ARGS__)
static constexpr int32_t COLOR_FMT_YUV420_PLANAR = 19;
static constexpr int32_t COLOR_FMT_YUV420_FLEXIBLE = 0x7F420888;
static constexpr int64_t DEQUEUE_TIMEOUT_US = 2000;

struct TrackDecoder {
    AMediaExtractor* extractor = nullptr;
    AMediaCodec* codec = nullptr;
    int trackIndex = -1;
    bool inputEos = false, outputEos = false;
};

struct DroidMediaSession {
    TrackDecoder video, audio;
    int32_t width = 0, height = 0, stride = 0, sliceHeight = 0, colorFormat = 21;
    int64_t durationMs = 0;
    int32_t sampleRate = 44100, channels = 2;
};

static inline uint8_t clampU8(int v) { return static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v)); }

static inline uint32_t yuvToArgb(int y, int u, int v) {
    int c = y - 16, d = u - 128, e = v - 128;
    if (c < 0) c = 0;
    int r = (298 * c + 409 * e + 128) >> 8;
    int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
    int b = (298 * c + 516 * d + 128) >> 8;
    return 0xFF000000u | (static_cast<uint32_t>(clampU8(r)) << 16) | (static_cast<uint32_t>(clampU8(g)) << 8) | static_cast<uint32_t>(clampU8(b));
}

static void convertYuvToArgb(const uint8_t* src, size_t srcSize, int32_t w, int32_t h, int32_t stride, int32_t sliceH, int32_t colorFmt, uint32_t* dstArgb) {
    if (stride < w) stride = w;
    if (sliceH < h) sliceH = h;
    const size_t yPlaneSize = static_cast<size_t>(stride) * static_cast<size_t>(sliceH);
    if (srcSize < yPlaneSize) return;
    const uint8_t* yPlane = src;
    const uint8_t* uvBase = src + yPlaneSize;

    if (colorFmt == COLOR_FMT_YUV420_PLANAR) {
        const size_t uvStride = static_cast<size_t>(stride / 2);
        const size_t uPlaneSize = uvStride * static_cast<size_t>(sliceH / 2);
        if (srcSize < yPlaneSize + uPlaneSize * 2) return;
        const uint8_t* uPlane = uvBase;
        const uint8_t* vPlane = uvBase + uPlaneSize;
        for (int y = 0; y < h; ++y) {
            const uint8_t* yRow = yPlane + y * stride;
            const uint8_t* uRow = uPlane + (y >> 1) * uvStride;
            const uint8_t* vRow = vPlane + (y >> 1) * uvStride;
            uint32_t* outRow = dstArgb + y * w;
            for (int x = 0; x < w; ++x) outRow[x] = yuvToArgb(yRow[x], uRow[x >> 1], vRow[x >> 1]);
        }
    } else {
        const size_t minNeeded = yPlaneSize + static_cast<size_t>(stride) * static_cast<size_t>((h + 1) / 2);
        if (srcSize < minNeeded) return;
        for (int y = 0; y < h; ++y) {
            const uint8_t* yRow = yPlane + y * stride;
            const uint8_t* uvRow = uvBase + (y >> 1) * stride;
            uint32_t* outRow = dstArgb + y * w;
            for (int x = 0; x < w; ++x) {
                int uvIdx = (x & ~1);
                outRow[x] = yuvToArgb(yRow[x], uvRow[uvIdx], uvRow[uvIdx + 1]);
            }
        }
    }
}
static bool openTrack(const char* path, const char* mimePrefix, TrackDecoder& td, AMediaFormat** outFormat, bool isVideo) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return false;
    struct stat st{};
    if (fstat(fd, &st) != 0) { close(fd); return false; }
    td.extractor = AMediaExtractor_new();
    media_status_t status = AMediaExtractor_setDataSourceFd(td.extractor, fd, 0, st.st_size);
    close(fd);
    if (status != AMEDIA_OK) return false;
    size_t trackCount = AMediaExtractor_getTrackCount(td.extractor);
    for (size_t i = 0; i < trackCount; ++i) {
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(td.extractor, i);
        const char* mime = nullptr;
        if (AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime) && mime != nullptr) {
            if (strncmp(mime, mimePrefix, strlen(mimePrefix)) == 0) {
                td.trackIndex = static_cast<int>(i);
                AMediaExtractor_selectTrack(td.extractor, i);
                // keep default color format
                td.codec = AMediaCodec_createDecoderByType(mime);
                if (!td.codec || AMediaCodec_configure(td.codec, fmt, nullptr, nullptr, 0) != AMEDIA_OK) {
                    AMediaFormat_delete(fmt); return false;
                }
                AMediaCodec_start(td.codec);
                *outFormat = fmt;
                return true;
            }
        }
        AMediaFormat_delete(fmt);
    }
    return false;
}

static void closeTrack(TrackDecoder& td) {
    if (td.codec) { AMediaCodec_stop(td.codec); AMediaCodec_delete(td.codec); td.codec = nullptr; }
    if (td.extractor) { AMediaExtractor_delete(td.extractor); td.extractor = nullptr; }
}

static void feedInput(TrackDecoder& td) {
    if (!td.codec || !td.extractor || td.inputEos) return;
    ssize_t idx = AMediaCodec_dequeueInputBuffer(td.codec, DEQUEUE_TIMEOUT_US);
    if (idx < 0) return;
    size_t bufSize = 0;
    uint8_t* buf = AMediaCodec_getInputBuffer(td.codec, static_cast<size_t>(idx), &bufSize);
    if (!buf) return;
    ssize_t sampleSize = AMediaExtractor_readSampleData(td.extractor, buf, bufSize);
    if (sampleSize < 0) {
        AMediaCodec_queueInputBuffer(td.codec, static_cast<size_t>(idx), 0, 0, 0, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
        td.inputEos = true;
    } else {
        int64_t pts = AMediaExtractor_getSampleTime(td.extractor);
        AMediaCodec_queueInputBuffer(td.codec, static_cast<size_t>(idx), 0, static_cast<size_t>(sampleSize), static_cast<uint64_t>(pts), 0);
        AMediaExtractor_advance(td.extractor);
    }
}

extern "C" {
JNIEXPORT jlong JNICALL Java_com_ttcraft_droidmedia_DroidMediaNative_nativeCreate(JNIEnv* env, jclass, jstring jPath) {
    const char* path = env->GetStringUTFChars(jPath, nullptr);
    auto* session = new DroidMediaSession();
    AMediaFormat* vFmt = nullptr;
    if (!openTrack(path, "video/", session->video, &vFmt, true)) {
        LOGE("Failed to open video track: %s", path);
        env->ReleaseStringUTFChars(jPath, path);
        closeTrack(session->video); delete session; return 0;
    }
    AMediaFormat_getInt32(vFmt, AMEDIAFORMAT_KEY_WIDTH, &session->width);
    AMediaFormat_getInt32(vFmt, AMEDIAFORMAT_KEY_HEIGHT, &session->height);
    session->stride = session->width; session->sliceHeight = session->height;
    int64_t durUs = 0;
    if (AMediaFormat_getInt64(vFmt, AMEDIAFORMAT_KEY_DURATION, &durUs)) session->durationMs = durUs / 1000;
    AMediaFormat_delete(vFmt);
    AMediaFormat* aFmt = nullptr;
    if (openTrack(path, "audio/", session->audio, &aFmt, false)) {
        AMediaFormat_getInt32(aFmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &session->sampleRate);
        AMediaFormat_getInt32(aFmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &session->channels);
        AMediaFormat_delete(aFmt);
    }
    env->ReleaseStringUTFChars(jPath, path);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL Java_com_ttcraft_droidmedia_DroidMediaNative_nativeDestroy(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<DroidMediaSession*>(handle);
    if (!s) return;
    closeTrack(s->video); closeTrack(s->audio); delete s;
}
JNIEXPORT jint JNICALL Java_com_ttcraft_droidmedia_DroidMediaNative_nativeGetWidth(JNIEnv*, jclass, jlong h) { auto* s = reinterpret_cast<DroidMediaSession*>(h); return s ? s->width : 0; }
JNIEXPORT jint JNICALL Java_com_ttcraft_droidmedia_DroidMediaNative_nativeGetHeight(JNIEnv*, jclass, jlong h) { auto* s = reinterpret_cast<DroidMediaSession*>(h); return s ? s->height : 0; }
JNIEXPORT jlong JNICALL Java_com_ttcraft_droidmedia_DroidMediaNative_nativeGetDurationMs(JNIEnv*, jclass, jlong h) { auto* s = reinterpret_cast<DroidMediaSession*>(h); return s ? s->durationMs : 0; }
JNIEXPORT jint JNICALL Java_com_ttcraft_droidmedia_DroidMediaNative_nativeGetAudioSampleRate(JNIEnv*, jclass, jlong h) { auto* s = reinterpret_cast<DroidMediaSession*>(h); return s ? s->sampleRate : 44100; }
JNIEXPORT jint JNICALL Java_com_ttcraft_droidmedia_DroidMediaNative_nativeGetAudioChannels(JNIEnv*, jclass, jlong h) { auto* s = reinterpret_cast<DroidMediaSession*>(h); return s ? s->channels : 2; }

JNIEXPORT jlong JNICALL Java_com_ttcraft_droidmedia_DroidMediaNative_nativeReadVideoFrame(JNIEnv* env, jclass, jlong handle, jobject argbDirectBuf) {
    auto* s = reinterpret_cast<DroidMediaSession*>(handle);
    if (!s || !s->video.codec || s->video.outputEos) return -2;
    feedInput(s->video);
    AMediaCodecBufferInfo info{};
    ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(s->video.codec, &info, DEQUEUE_TIMEOUT_US);
    if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        AMediaFormat* outFmt = AMediaCodec_getOutputFormat(s->video.codec);
        AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_WIDTH, &s->width);
        AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_HEIGHT, &s->height);
        if (!AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_STRIDE, &s->stride)) s->stride = s->width;
        if (!AMediaFormat_getInt32(outFmt, "slice-height", &s->sliceHeight)) s->sliceHeight = s->height;
        AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_COLOR_FORMAT, &s->colorFormat);
        AMediaFormat_delete(outFmt);
        return -1;
    }
    if (outIdx < 0) return -1;
    if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) s->video.outputEos = true;
    jlong ptsMs = -1;
    if (info.size > 0) {
        size_t bufSize = 0;
        uint8_t* outBuf = AMediaCodec_getOutputBuffer(s->video.codec, static_cast<size_t>(outIdx), &bufSize);
        auto* dstArgb = static_cast<uint32_t*>(env->GetDirectBufferAddress(argbDirectBuf));
        if (outBuf && dstArgb) {
            convertYuvToArgb(outBuf + info.offset, static_cast<size_t>(info.size), s->width, s->height, s->stride, s->sliceHeight, s->colorFormat, dstArgb);
            ptsMs = info.presentationTimeUs / 1000;
        }
    }
    AMediaCodec_releaseOutputBuffer(s->video.codec, static_cast<size_t>(outIdx), false);
    return s->video.outputEos && ptsMs < 0 ? -2 : ptsMs;
}

JNIEXPORT jint JNICALL Java_com_ttcraft_droidmedia_DroidMediaNative_nativeReadAudioPcm(JNIEnv* env, jclass, jlong handle, jobject pcmDirectBuf) {
    auto* s = reinterpret_cast<DroidMediaSession*>(handle);
    if (!s || !s->audio.codec || s->audio.outputEos) return 0;
    feedInput(s->audio);
    AMediaCodecBufferInfo info{};
    ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(s->audio.codec, &info, DEQUEUE_TIMEOUT_US);
    if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
        AMediaFormat* outFmt = AMediaCodec_getOutputFormat(s->audio.codec);
        AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_SAMPLE_RATE, &s->sampleRate);
        AMediaFormat_getInt32(outFmt, AMEDIAFORMAT_KEY_CHANNEL_COUNT, &s->channels);
        AMediaFormat_delete(outFmt);
        return 0;
    }
    if (outIdx < 0) return 0;
    if (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) s->audio.outputEos = true;
    jint copied = 0;
    if (info.size > 0) {
        size_t bufSize = 0;
        uint8_t* outBuf = AMediaCodec_getOutputBuffer(s->audio.codec, static_cast<size_t>(outIdx), &bufSize);
        auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(pcmDirectBuf));
        jlong cap = env->GetDirectBufferCapacity(pcmDirectBuf);
        if (outBuf && dst && cap > 0) {
            copied = static_cast<jint>(std::min<int64_t>(info.size, cap));
            std::memcpy(dst, outBuf + info.offset, static_cast<size_t>(copied));
        }
    }
    AMediaCodec_releaseOutputBuffer(s->audio.codec, static_cast<size_t>(outIdx), false);
    return copied;
}

JNIEXPORT void JNICALL Java_com_ttcraft_droidmedia_DroidMediaNative_nativeSeekToStart(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<DroidMediaSession*>(handle);
    if (!s) return;
    if (s->video.extractor && s->video.codec) {
        AMediaExtractor_seekTo(s->video.extractor, 0, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
        AMediaCodec_flush(s->video.codec); s->video.inputEos = false; s->video.outputEos = false;
    }
    if (s->audio.extractor && s->audio.codec) {
        AMediaExtractor_seekTo(s->audio.extractor, 0, AMEDIAEXTRACTOR_SEEK_CLOSEST_SYNC);
        AMediaCodec_flush(s->audio.codec); s->audio.inputEos = false; s->audio.outputEos = false;
    }
}
}
