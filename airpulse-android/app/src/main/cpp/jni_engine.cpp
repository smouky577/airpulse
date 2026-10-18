#include "airpulse/bos_flow.hpp"
#include "airpulse/evm_pulse.hpp"

#include <android/log.h>
#include <jni.h>

#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <vector>

#define LOG_TAG "AirPulse"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct Engine {
    std::mutex mu;
    airpulse::PulseStream pulse;
    airpulse::BosStream bos;
    int width = 0;
    int height = 0;
    double fps = 30.0;
    bool capture_ref = false;
    std::vector<uint8_t> bgr;
    std::vector<uint8_t> gray;
    std::vector<uint8_t> prev_gray;
    bool have_prev = false;
    airpulse::PulseParams pulse_params;
    airpulse::BosParams bos_params;
};

Engine* engine_of(jlong handle) {
    return reinterpret_cast<Engine*>(handle);
}

int clamp_int(int v, int lo, int hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

uint8_t plane_at(const uint8_t* base, int capacity, int row_stride, int pix_stride, int x, int y) {
    const int index = y * row_stride + x * pix_stride;
    if (index < 0 || index >= capacity) return 0;
    return base[index];
}

void yuv_to_working(const uint8_t* y_plane, int y_cap, int y_row, int y_pix,
                    const uint8_t* u_plane, int u_cap, int u_row, int u_pix,
                    const uint8_t* v_plane, int v_cap, int v_row, int v_pix,
                    int src_w, int src_h, std::vector<uint8_t>& bgr, std::vector<uint8_t>& gray,
                    int dst_w, int dst_h) {
    bgr.resize(static_cast<size_t>(dst_w) * dst_h * 3);
    gray.resize(static_cast<size_t>(dst_w) * dst_h);
    const float scale_x = static_cast<float>(src_w) / dst_w;
    const float scale_y = static_cast<float>(src_h) / dst_h;
    for (int dy = 0; dy < dst_h; ++dy) {
        const int sy = clamp_int(static_cast<int>((dy + 0.5f) * scale_y), 0, src_h - 1);
        const int cy = sy / 2;
        for (int dx = 0; dx < dst_w; ++dx) {
            const int sx = clamp_int(static_cast<int>((dx + 0.5f) * scale_x), 0, src_w - 1);
            const int cx = sx / 2;
            const int yv = plane_at(y_plane, y_cap, y_row, y_pix, sx, sy);
            const int u = static_cast<int>(plane_at(u_plane, u_cap, u_row, u_pix, cx, cy)) - 128;
            const int v = static_cast<int>(plane_at(v_plane, v_cap, v_row, v_pix, cx, cy)) - 128;
            int r = static_cast<int>(yv + 1.402f * v);
            int g = static_cast<int>(yv - 0.344136f * u - 0.714136f * v);
            int b = static_cast<int>(yv + 1.772f * u);
            r = clamp_int(r, 0, 255);
            g = clamp_int(g, 0, 255);
            b = clamp_int(b, 0, 255);
            const size_t i = static_cast<size_t>(dy) * dst_w + dx;
            bgr[i * 3 + 0] = static_cast<uint8_t>(b);
            bgr[i * 3 + 1] = static_cast<uint8_t>(g);
            bgr[i * 3 + 2] = static_cast<uint8_t>(r);
            gray[i] = static_cast<uint8_t>(yv);
        }
    }
}

struct BorrowedImage {
    airpulse::ImageU8 image;
    std::vector<uint8_t>& slot;
    BorrowedImage(int w, int h, int c, std::vector<uint8_t>& data) : slot(data) {
        image.width = w;
        image.height = h;
        image.channels = c;
        image.data.swap(slot);
    }
    ~BorrowedImage() { slot.swap(image.data); }
};

void write_argb(const airpulse::ImageU8& bgr, jint* dst) {
    const int n = bgr.width * bgr.height;
    for (int i = 0; i < n; ++i) {
        const int b = bgr.data[i * 3 + 0];
        const int g = bgr.data[i * 3 + 1];
        const int r = bgr.data[i * 3 + 2];
        dst[i] = (255 << 24) | (r << 16) | (g << 8) | b;
    }
}

float contrast_of(const std::vector<uint8_t>& gray, int w, int h) {
    double sum = 0.0;
    int count = 0;
    for (int y = 0; y < h; y += 4) {
        const uint8_t* row = gray.data() + static_cast<size_t>(y) * w;
        for (int x = 0; x < w - 1; x += 4) {
            sum += std::abs(static_cast<int>(row[x]) - static_cast<int>(row[x + 1]));
            ++count;
        }
    }
    return count ? static_cast<float>(sum / count) : 0.f;
}

float motion_of(const std::vector<uint8_t>& gray, std::vector<uint8_t>& prev, bool& have_prev) {
    float motion = 0.f;
    if (have_prev && prev.size() == gray.size()) {
        double sum = 0.0;
        int count = 0;
        for (size_t i = 0; i < gray.size(); i += 4) {
            sum += std::abs(static_cast<int>(gray[i]) - static_cast<int>(prev[i]));
            ++count;
        }
        motion = count ? static_cast<float>(sum / count) : 0.f;
    }
    prev = gray;
    have_prev = true;
    return motion;
}

// Mean green over skin-coloured pixels (YCrCb box) on a 2x subsampled grid.
// Returns false when skin covers under 2% of the samples.
bool skin_green_mean(const airpulse::ImageU8& bgr, double& mean_out) {
    double sum = 0.0;
    int skin = 0;
    int total = 0;
    for (int y = 0; y < bgr.height; y += 2) {
        const uint8_t* row = bgr.data.data() + static_cast<size_t>(y) * bgr.width * 3;
        for (int x = 0; x < bgr.width; x += 2) {
            const float b = row[x * 3 + 0];
            const float g = row[x * 3 + 1];
            const float r = row[x * 3 + 2];
            const float luma = 0.299f * r + 0.587f * g + 0.114f * b;
            const float cr = (r - luma) * 0.713f + 128.f;
            const float cb = (b - luma) * 0.564f + 128.f;
            ++total;
            if (cr >= 133.f && cr <= 173.f && cb >= 77.f && cb <= 127.f && luma > 40.f && luma < 245.f) {
                sum += g;
                ++skin;
            }
        }
    }
    if (total == 0 || skin * 50 < total) return false;
    mean_out = sum / skin;
    return true;
}

airpulse::Rect clamp_roi(int x, int y, int rw, int rh, int w, int h) {
    airpulse::Rect roi;
    roi.width = clamp_int(rw, 8, w);
    roi.height = clamp_int(rh, 8, h);
    roi.x = clamp_int(x, 0, w - roi.width);
    roi.y = clamp_int(y, 0, h - roi.height);
    return roi;
}

struct FrameStats {
    float bpm = -1.f;
    float p995 = 0.f;
    float contrast = 0.f;
    float motion = 0.f;
    float ref_ready = 0.f;
    float ref_count = 0.f;
    float process_ms = 0.f;
    float mean_mag = 0.f;
    float snr = 0.f;
};

void store_stats(JNIEnv* env, jfloatArray stats, const FrameStats& s) {
    if (stats == nullptr) return;
    float values[9] = {s.bpm, s.p995, s.contrast, s.motion, s.ref_ready, s.ref_count, s.process_ms, s.mean_mag, s.snr};
    const jsize n = std::min(env->GetArrayLength(stats), static_cast<jsize>(9));
    env->SetFloatArrayRegion(stats, 0, n, values);
}

bool run_frame(Engine& engine, airpulse::ImageU8& bgr, airpulse::ImageU8& gray, int roi_x, int roi_y, int roi_w,
               int roi_h, jint* pixels, FrameStats& stats, std::vector<uint8_t>* record_bgr) {
    const auto t0 = std::chrono::steady_clock::now();
    stats.contrast = contrast_of(gray.data, engine.width, engine.height);
    stats.motion = motion_of(gray.data, engine.prev_gray, engine.have_prev);
    if (record_bgr != nullptr) *record_bgr = bgr.data;

    const airpulse::Rect roi = clamp_roi(roi_x, roi_y, roi_w, roi_h, engine.width, engine.height);
    if (engine.capture_ref && !engine.bos.reference_ready()) {
        engine.bos.add_reference(gray);
        write_argb(bgr, pixels);
    } else if (engine.capture_ref && engine.bos.reference_ready()) {
        airpulse::ImageU8 heat;
        airpulse::ImageF mag;
        engine.bos.process(gray, heat, mag);
        double sum = 0.0;
        for (float v : mag.data) sum += v;
        stats.mean_mag = mag.data.empty() ? 0.f : static_cast<float>(sum / mag.data.size());
        stats.p995 = engine.bos.last_percentile995();
        const int n = engine.width * engine.height;
        if (heat.width == engine.width && heat.height == engine.height &&
            static_cast<int>(heat.data.size()) >= n * 3 && static_cast<int>(bgr.data.size()) >= n * 3 &&
            static_cast<int>(mag.data.size()) >= n) {
            for (int i = 0; i < n; ++i) {
                // Keep colour on sub-pixel air. Only a hand/body (several px) becomes camera.
                float object = (mag.data[i] - 3.f) / 5.f;
                if (object < 0.f) object = 0.f;
                if (object > 1.f) object = 1.f;
                const float heat_w = 1.f - object;
                const float cam_w = object;
                heat.data[i * 3 + 0] = static_cast<uint8_t>(heat.data[i * 3 + 0] * heat_w + bgr.data[i * 3 + 0] * cam_w);
                heat.data[i * 3 + 1] = static_cast<uint8_t>(heat.data[i * 3 + 1] * heat_w + bgr.data[i * 3 + 1] * cam_w);
                heat.data[i * 3 + 2] = static_cast<uint8_t>(heat.data[i * 3 + 2] * heat_w + bgr.data[i * 3 + 2] * cam_w);
            }
        }
        write_argb(heat, pixels);
    } else {
        airpulse::ImageU8 shown = engine.pulse.magnify(bgr);
        double skin_green = 0.0;
        if (skin_green_mean(bgr, skin_green)) {
            engine.pulse.observe_value(skin_green);
        } else {
            engine.pulse.observe_roi(bgr, roi);
        }
        double bpm = 0.0;
        if (engine.pulse.displayed_bpm(bpm)) stats.bpm = static_cast<float>(bpm);
        stats.snr = static_cast<float>(engine.pulse.displayed_snr());
        write_argb(shown, pixels);
    }
    stats.ref_ready = engine.bos.reference_ready() ? 1.f : 0.f;
    stats.ref_count = static_cast<float>(engine.bos.reference_count());
    const auto t1 = std::chrono::steady_clock::now();
    stats.process_ms = std::chrono::duration<float, std::milli>(t1 - t0).count();
    return true;
}

const uint8_t* direct_bytes(JNIEnv* env, jobject buffer, int position, int& capacity) {
    if (buffer == nullptr) return nullptr;
    void* address = env->GetDirectBufferAddress(buffer);
    if (address == nullptr) return nullptr;
    const int full = static_cast<int>(env->GetDirectBufferCapacity(buffer));
    if (position < 0 || position >= full) return nullptr;
    capacity = full - position;
    return static_cast<const uint8_t*>(address) + position;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_airpulse_app_NativeEngine_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new Engine());
}

JNIEXPORT void JNICALL Java_com_airpulse_app_NativeEngine_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete engine_of(handle);
}

JNIEXPORT void JNICALL Java_com_airpulse_app_NativeEngine_nativeConfigure(
    JNIEnv*, jobject, jlong handle, jint width, jint height, jdouble fps, jboolean air_mode, jdouble alpha,
    jdouble low_hz, jdouble high_hz, jint pyramid, jdouble gain, jdouble blur, jint winsize, jdouble highpass) {
    Engine* engine = engine_of(handle);
    if (engine == nullptr || width < 16 || height < 16 || fps <= 0.0) return;
    std::lock_guard<std::mutex> lock(engine->mu);
    engine->width = width;
    engine->height = height;
    engine->fps = fps;
    engine->capture_ref = air_mode == JNI_TRUE;
    engine->pulse_params.alpha = alpha;
    engine->pulse_params.low_hz = low_hz;
    engine->pulse_params.high_hz = high_hz;
    engine->pulse_params.pyramid_level = pyramid;
    engine->bos_params.gain = gain;
    engine->bos_params.blur_sigma = blur;
    engine->bos_params.winsize = winsize < 5 ? 5 : winsize;
    engine->bos_params.highpass_hz = highpass;
    engine->have_prev = false;
    try {
        engine->pulse.reset(width, height, fps, engine->pulse_params);
        engine->bos.reset(fps, engine->bos_params);
    } catch (const std::exception& ex) {
        LOGI("configure failed: %s", ex.what());
    }
}

JNIEXPORT void JNICALL Java_com_airpulse_app_NativeEngine_nativeUpdate(
    JNIEnv*, jobject, jlong handle, jboolean air_mode, jdouble alpha, jdouble low_hz, jdouble high_hz, jint pyramid,
    jdouble gain, jdouble blur, jint winsize, jdouble highpass) {
    Engine* engine = engine_of(handle);
    if (engine == nullptr) return;
    std::lock_guard<std::mutex> lock(engine->mu);
    engine->capture_ref = air_mode == JNI_TRUE;
    engine->pulse_params.alpha = alpha;
    engine->pulse_params.low_hz = low_hz;
    engine->pulse_params.high_hz = high_hz;
    engine->pulse_params.pyramid_level = pyramid;
    engine->bos_params.gain = gain;
    engine->bos_params.blur_sigma = blur;
    engine->bos_params.winsize = winsize < 5 ? 5 : winsize;
    engine->bos_params.highpass_hz = highpass;
    try {
        if (engine->width > 0) {
            engine->pulse.set_params(engine->pulse_params);
            engine->bos.set_params(engine->bos_params);
        }
    } catch (const std::exception& ex) {
        LOGI("update failed: %s", ex.what());
    }
}

JNIEXPORT void JNICALL Java_com_airpulse_app_NativeEngine_nativeClearReference(JNIEnv*, jobject, jlong handle) {
    Engine* engine = engine_of(handle);
    if (engine == nullptr) return;
    std::lock_guard<std::mutex> lock(engine->mu);
    engine->bos.clear_reference();
}

JNIEXPORT jboolean JNICALL Java_com_airpulse_app_NativeEngine_nativeProcessYuv(
    JNIEnv* env, jobject, jlong handle, jobject y_buf, jint y_pos, jint y_row, jint y_pix, jobject u_buf, jint u_pos,
    jint u_row, jint u_pix, jobject v_buf, jint v_pos, jint v_row, jint v_pix, jint src_w, jint src_h, jint roi_x,
    jint roi_y, jint roi_w, jint roi_h, jintArray pixels, jfloatArray stats, jbyteArray record) {
    Engine* engine = engine_of(handle);
    if (engine == nullptr || pixels == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(engine->mu);
    if (engine->width <= 0 || engine->height <= 0) return JNI_FALSE;
    int y_cap = 0, u_cap = 0, v_cap = 0;
    const uint8_t* y = direct_bytes(env, y_buf, y_pos, y_cap);
    const uint8_t* u = direct_bytes(env, u_buf, u_pos, u_cap);
    const uint8_t* v = direct_bytes(env, v_buf, v_pos, v_cap);
    if (y == nullptr || u == nullptr || v == nullptr || src_w < 2 || src_h < 2) return JNI_FALSE;
    try {
        yuv_to_working(y, y_cap, y_row, y_pix, u, u_cap, u_row, u_pix, v, v_cap, v_row, v_pix, src_w, src_h,
                       engine->bgr, engine->gray, engine->width, engine->height);
        BorrowedImage bgr(engine->width, engine->height, 3, engine->bgr);
        BorrowedImage gray(engine->width, engine->height, 1, engine->gray);
        jint* out = env->GetIntArrayElements(pixels, nullptr);
        if (out == nullptr) return JNI_FALSE;
        FrameStats frame_stats;
        std::vector<uint8_t> recorded;
        bool ok = false;
        try {
            ok = run_frame(*engine, bgr.image, gray.image, roi_x, roi_y, roi_w, roi_h, out, frame_stats,
                           record != nullptr ? &recorded : nullptr);
        } catch (...) {
            env->ReleaseIntArrayElements(pixels, out, JNI_ABORT);
            throw;
        }
        env->ReleaseIntArrayElements(pixels, out, 0);
        if (record != nullptr && recorded.size() == bgr.image.data.size()) {
            const jsize n = env->GetArrayLength(record);
            if (n >= static_cast<jsize>(recorded.size())) {
                env->SetByteArrayRegion(record, 0, static_cast<jsize>(recorded.size()),
                                        reinterpret_cast<const jbyte*>(recorded.data()));
            }
        }
        store_stats(env, stats, frame_stats);
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& ex) {
        LOGI("processYuv failed: %s", ex.what());
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL Java_com_airpulse_app_NativeEngine_nativeProcessBgr(
    JNIEnv* env, jobject, jlong handle, jbyteArray bgr_in, jint roi_x, jint roi_y, jint roi_w, jint roi_h,
    jintArray pixels, jfloatArray stats) {
    Engine* engine = engine_of(handle);
    if (engine == nullptr || bgr_in == nullptr || pixels == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(engine->mu);
    const jsize bytes = env->GetArrayLength(bgr_in);
    const size_t need = static_cast<size_t>(engine->width) * engine->height * 3;
    if (engine->width <= 0 || static_cast<size_t>(bytes) < need) return JNI_FALSE;
    try {
        engine->bgr.resize(need);
        env->GetByteArrayRegion(bgr_in, 0, static_cast<jsize>(need), reinterpret_cast<jbyte*>(engine->bgr.data()));
        engine->gray.resize(static_cast<size_t>(engine->width) * engine->height);
        for (int i = 0; i < engine->width * engine->height; ++i) {
            const int b = engine->bgr[i * 3 + 0];
            const int g = engine->bgr[i * 3 + 1];
            const int r = engine->bgr[i * 3 + 2];
            int y = (b * 1868 + g * 9617 + r * 4899 + 8192) >> 14;
            engine->gray[i] = static_cast<uint8_t>(clamp_int(y, 0, 255));
        }
        BorrowedImage bgr(engine->width, engine->height, 3, engine->bgr);
        BorrowedImage gray(engine->width, engine->height, 1, engine->gray);
        jint* out = env->GetIntArrayElements(pixels, nullptr);
        if (out == nullptr) return JNI_FALSE;
        FrameStats frame_stats;
        try {
            run_frame(*engine, bgr.image, gray.image, roi_x, roi_y, roi_w, roi_h, out, frame_stats, nullptr);
        } catch (...) {
            env->ReleaseIntArrayElements(pixels, out, JNI_ABORT);
            throw;
        }
        env->ReleaseIntArrayElements(pixels, out, 0);
        store_stats(env, stats, frame_stats);
        return JNI_TRUE;
    } catch (const std::exception& ex) {
        LOGI("processBgr failed: %s", ex.what());
        return JNI_FALSE;
    }
}

JNIEXPORT jint JNICALL Java_com_airpulse_app_NativeEngine_nativeCopyTrace(JNIEnv* env, jobject, jlong handle,
                                                                          jfloatArray dest) {
    Engine* engine = engine_of(handle);
    if (engine == nullptr || dest == nullptr) return 0;
    std::lock_guard<std::mutex> lock(engine->mu);
    const auto& trace = engine->pulse.green_trace();
    const jsize cap = env->GetArrayLength(dest);
    const int n = std::min(static_cast<int>(trace.size()), static_cast<int>(cap));
    if (n <= 0) return 0;
    std::vector<float> tmp(n);
    const int offset = static_cast<int>(trace.size()) - n;
    for (int i = 0; i < n; ++i) tmp[i] = static_cast<float>(trace[offset + i]);
    env->SetFloatArrayRegion(dest, 0, n, tmp.data());
    return n;
}

}  // extern "C"
