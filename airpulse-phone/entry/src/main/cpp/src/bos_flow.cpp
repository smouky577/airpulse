#include "airpulse/bos_flow.hpp"

#include "airpulse/farneback.hpp"
#include "airpulse/signal.hpp"

#include <cmath>
#include <stdexcept>

namespace airpulse {

void BosStream::set_params(BosParams params) {
    if (params.ref_frames < 1) params.ref_frames = 1;
    const bool blur_changed = params.blur_sigma != params_.blur_sigma;
    params_ = params;
    if (blur_changed) clear_reference();
}

void BosStream::clear_reference() {
    ref_stack_.clear();
    reference_ = {};
    have_ref_ = false;
    have_drift_ = false;
    drift_ = {};
    last_p995_ = 0.f;
}

int BosStream::reference_count() const {
    if (have_ref_) return params_.ref_frames;
    return static_cast<int>(ref_stack_.size());
}

void BosStream::reset(double fps, BosParams params) {
    if (fps <= 0.0 || params.ref_frames < 1) throw std::invalid_argument("bos reset");
    params_ = params;
    fps_ = fps;
    ref_stack_.clear();
    reference_ = {};
    have_ref_ = false;
    have_drift_ = false;
    drift_ = {};
    last_p995_ = 0.f;
}

ImageU8 BosStream::blur_gray(const ImageU8& gray) const {
    if (params_.blur_sigma <= 0.0) return gray;
    ImageU8 out;
    gaussian_blur_u8(gray, out, 0, params_.blur_sigma);
    return out;
}

bool BosStream::add_reference(const ImageU8& gray) {
    if (gray.channels != 1) throw std::invalid_argument("reference frame must be grayscale");
    if (have_ref_) return true;
    if (!ref_stack_.empty() &&
        (ref_stack_[0].width != gray.width || ref_stack_[0].height != gray.height)) {
        throw std::invalid_argument("reference frames differ in size");
    }
    ref_stack_.push_back(gray);
    if (static_cast<int>(ref_stack_.size()) < params_.ref_frames) return false;
    reference_ = blur_gray(median_u8(ref_stack_));
    have_ref_ = true;
    ref_stack_.clear();
    return true;
}

void BosStream::process(const ImageU8& gray, ImageU8& heatmap_bgr, ImageF& magnitude) {
    if (!have_ref_) throw std::runtime_error("capture a reference before processing air frames");
    if (gray.channels != 1 || gray.width != reference_.width || gray.height != reference_.height) {
        throw std::invalid_argument("air frame size");
    }
    const ImageF prev = to_float(reference_);
    const ImageF next = to_float(blur_gray(gray));
    ImageF flow;
    calc_optical_flow_farneback(prev, next, flow, 0.5, params_.levels, params_.winsize,
                                params_.iterations, params_.poly_n, params_.poly_sigma);
    if (params_.highpass_hz > 0.0) {
        const float a = static_cast<float>(iir_coeff(params_.highpass_hz, fps_));
        if (!have_drift_ || drift_.width != flow.width || drift_.height != flow.height) {
            drift_ = flow;
            have_drift_ = true;
        }
        for (size_t i = 0; i < flow.data.size(); ++i) {
            drift_.data[i] = static_cast<float>(drift_.data[i] + a * (flow.data[i] - drift_.data[i]));
            flow.data[i] -= drift_.data[i];
        }
    }

    magnitude = ImageF::zeros(flow.width, flow.height, 1);
    ImageU8 vis = ImageU8::zeros(flow.width, flow.height, 1);
    const float gain = static_cast<float>(params_.gain);
    for (int i = 0; i < flow.width * flow.height; ++i) {
        const float dx = flow.data[i * 2];
        const float dy = flow.data[i * 2 + 1];
        const float mag = std::sqrt(dx * dx + dy * dy);
        magnitude.data[i] = mag;
        float scaled = mag * gain * 255.f;
        if (scaled < 0.f) scaled = 0.f;
        if (scaled > 255.f) scaled = 255.f;
        vis.data[i] = static_cast<uint8_t>(scaled);
    }
    last_p995_ = percentile_linear(magnitude.data, 99.5);
    heatmap_bgr = apply_inferno(vis);
}

}  // namespace airpulse
