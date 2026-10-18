#include "airpulse/evm_pulse.hpp"

#include "airpulse/signal.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <stdexcept>

namespace airpulse {
namespace {

float bits_to_float(uint32_t bits) {
    float v;
    std::memcpy(&v, &bits, sizeof(v));
    return v;
}

// Exact float32 bits of evm_prototype.py's _RGB2YIQ and np.linalg.inv(...).astype(float32).
constexpr uint32_t kRgb2YiqBits[9] = {
    0x3e991687, 0x3f1645a2, 0x3de978d5, 0x3f189375, 0xbe8c49ba, 0xbea4dd2f, 0x3e581062, 0xbf05e354, 0x3e9fbe77,
};
constexpr uint32_t kYiq2RgbBits[9] = {
    0x3f7fffff, 0x3f74c79a, 0x3f1f1634, 0x3f800000, 0xbe8b9dd7, 0xbf25958d, 0x3f800000, 0xbf8d477c, 0x3fd9ae05,
};

ImageF bgr_to_yiq(const ImageU8& bgr) {
    float m[9];
    for (int i = 0; i < 9; ++i) m[i] = bits_to_float(kRgb2YiqBits[i]);
    ImageF yiq = ImageF::zeros(bgr.width, bgr.height, 3);
    const int n = bgr.width * bgr.height;
    for (int i = 0; i < n; ++i) {
        const float r = bgr.data[i * 3 + 2] / 255.f;
        const float g = bgr.data[i * 3 + 1] / 255.f;
        const float b = bgr.data[i * 3 + 0] / 255.f;
        const float rgb[3] = {r, g, b};
        for (int c = 0; c < 3; ++c) {
            float s = 0.f;
            for (int k = 0; k < 3; ++k) s += rgb[k] * m[c * 3 + k];
            yiq.data[i * 3 + c] = s;
        }
    }
    return yiq;
}

ImageU8 yiq_to_bgr(const ImageF& yiq) {
    float m[9];
    for (int i = 0; i < 9; ++i) m[i] = bits_to_float(kYiq2RgbBits[i]);
    ImageU8 bgr = ImageU8::zeros(yiq.width, yiq.height, 3);
    const int n = yiq.width * yiq.height;
    for (int i = 0; i < n; ++i) {
        const float y = yiq.data[i * 3 + 0];
        const float ii = yiq.data[i * 3 + 1];
        const float q = yiq.data[i * 3 + 2];
        const float in[3] = {y, ii, q};
        float rgb[3];
        for (int c = 0; c < 3; ++c) {
            float s = 0.f;
            for (int k = 0; k < 3; ++k) s += in[k] * m[c * 3 + k];
            rgb[c] = s;
        }
        for (int c = 0; c < 3; ++c) {
            float v = rgb[c];
            if (v < 0.f) v = 0.f;
            if (v > 1.f) v = 1.f;
            // BGR, truncating like NumPy astype(uint8) after *255.
            bgr.data[i * 3 + (2 - c)] = static_cast<uint8_t>(v * 255.f);
        }
    }
    return bgr;
}

void iir_image(ImageF& lp, const ImageF& x, double coeff) {
    // NumPy does this in float64 and stores float32. The coeff stays float64.
    for (size_t i = 0; i < lp.data.size(); ++i) {
        const double cur = lp.data[i];
        lp.data[i] = static_cast<float>(cur + coeff * (static_cast<double>(x.data[i]) - cur));
    }
}

}  // namespace

void PulseStream::set_params(PulseParams params) {
    if (params.pyramid_level < 1) params.pyramid_level = 1;
    if (params.pyramid_level > 6) params.pyramid_level = 6;
    const bool rebuild = params.pyramid_level != params_.pyramid_level ||
                         params.low_hz != params_.low_hz || params.high_hz != params_.high_hz;
    params_ = params;
    window_samples_ = std::max(1, static_cast<int>(params.window_sec * fps_));
    if (static_cast<int>(green_.size()) > window_samples_) {
        green_.erase(green_.begin(), green_.end() - window_samples_);
    }
    if (rebuild) have_lp_ = false;
}

void PulseStream::reset(int width, int height, double fps, PulseParams params) {
    if (width <= 0 || height <= 0 || fps <= 0.0) throw std::invalid_argument("pulse reset");
    params_ = params;
    fps_ = fps;
    width_ = width;
    height_ = height;
    frames_seen_ = 0;
    have_lp_ = false;
    lp_high_ = {};
    lp_low_ = {};
    green_.clear();
    window_samples_ = std::max(1, static_cast<int>(params.window_sec * fps));
    have_display_ = false;
    displayed_ = 0.0;
    displayed_snr_ = 0.0;
}

ImageU8 PulseStream::magnify(const ImageU8& bgr) {
    if (bgr.width != width_ || bgr.height != height_ || bgr.channels != 3) {
        throw std::invalid_argument("pulse frame size");
    }
    ImageF yiq = bgr_to_yiq(bgr);
    ImageF small = yiq;
    for (int level = 0; level < params_.pyramid_level; ++level) {
        ImageF next;
        pyr_down(small, next);
        small = std::move(next);
    }
    const double a_hi = iir_coeff(params_.high_hz, fps_);
    const double a_lo = iir_coeff(params_.low_hz, fps_);
    if (!have_lp_) {
        lp_high_ = small;
        lp_low_ = small;
        have_lp_ = true;
    }
    iir_image(lp_high_, small, a_hi);
    iir_image(lp_low_, small, a_lo);

    ImageF band = ImageF::zeros(small.width, small.height, 3);
    const float gain[3] = {
        static_cast<float>(params_.luma_gain),
        static_cast<float>(params_.alpha),
        static_cast<float>(params_.alpha),
    };
    for (int i = 0; i < small.width * small.height; ++i) {
        for (int c = 0; c < 3; ++c) {
            band.data[i * 3 + c] = (lp_high_.data[i * 3 + c] - lp_low_.data[i * 3 + c]) * gain[c];
        }
    }
    ImageF amp;
    resize_linear(band, amp, width_, height_);
    for (size_t i = 0; i < yiq.data.size(); ++i) yiq.data[i] += amp.data[i];
    ++frames_seen_;
    return yiq_to_bgr(yiq);
}

void PulseStream::observe_roi(const ImageU8& raw_bgr, Rect roi) {
    observe_value(mean_u8(raw_bgr, roi, 1));
}

void PulseStream::observe_value(double green) {
    green_.push_back(green);
    if (static_cast<int>(green_.size()) > window_samples_) {
        green_.erase(green_.begin(), green_.end() - window_samples_);
    }
    const int min_samples = static_cast<int>(params_.bpm_min_sec * fps_);
    const int period = std::max(1, static_cast<int>(fps_) / 2);
    const int index = static_cast<int>(green_.size()) - 1;
    // The live readout uses the frame index, which keeps counting even after the
    // trace is trimmed to the window. frames_seen_ is the magnify count; the
    // prototype keys the update off the same frame counter as the green sample.
    const int frame_i = frames_seen_ > 0 ? frames_seen_ - 1 : index;
    if (static_cast<int>(green_.size()) >= min_samples && (frame_i % period) == 0) {
        double bpm = 0.0;
        double snr = 0.0;
        if (bpm_from_trace(bpm, &snr)) {
            displayed_ = bpm;
            displayed_snr_ = snr;
            have_display_ = true;
        }
    }
}

bool PulseStream::bpm_from_trace(double& bpm_out, double* snr_out) const {
    const int min_samples = std::max(4, static_cast<int>(4.0 * fps_));
    if (static_cast<int>(green_.size()) < min_samples &&
        static_cast<int>(green_.size()) < static_cast<int>(params_.bpm_min_sec * fps_)) {
        return false;
    }
    return estimate_bpm(green_, fps_, params_.low_hz, params_.high_hz, bpm_out, snr_out);
}

bool PulseStream::final_bpm(double& bpm_out) const {
    if (static_cast<int>(green_.size()) < static_cast<int>(params_.bpm_min_sec * fps_)) return false;
    return estimate_bpm(green_, fps_, params_.low_hz, params_.high_hz, bpm_out);
}

bool PulseStream::displayed_bpm(double& bpm_out) const {
    if (!have_display_) return false;
    bpm_out = displayed_;
    return true;
}

}  // namespace airpulse
