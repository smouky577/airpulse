#pragma once

// Eulerian color magnification, streaming. Memory stays at a few frames
// (two low-pass images at the pyramid top, plus a fixed-length green trace).
// There is no whole-clip FFT of the video.

#include "airpulse/image.hpp"

#include <vector>

namespace airpulse {

struct PulseParams {
    double alpha = 60.0;       // gain on I and Q
    double luma_gain = 0.0;    // gain on Y; 0 leaves brightness alone
    double low_hz = 0.8;
    double high_hz = 2.0;
    int pyramid_level = 4;
    double window_sec = 12.0;  // BPM sliding window
    double bpm_min_sec = 6.0;  // no number until this much signal exists
};

class PulseStream {
public:
    void reset(int width, int height, double fps, PulseParams params = {});

    // Slider changes. Keeps the green trace. Rebuilds the spatial filter only when
    // the pyramid level or the frequency cutoffs change.
    void set_params(PulseParams params);

    // Magnify one BGR frame. Size must match reset(). The first frame is unchanged
    // (both low-passes are seeded with it, so the band-pass is zero).
    ImageU8 magnify(const ImageU8& bgr);

    // Average green (channel 1) inside roi on the raw frame, appended to the trace.
    void observe_roi(const ImageU8& raw_bgr, Rect roi);

    // Appends one already averaged green sample to the trace.
    void observe_value(double green);

    // Recomputed from the current window. False until bpm_min_sec of samples exist.
    bool final_bpm(double& bpm_out) const;

    // Same estimator, refreshed about twice a second, for a live readout.
    bool displayed_bpm(double& bpm_out) const;

    // Peak quality of the last live readout (see estimate_bpm).
    double displayed_snr() const { return displayed_snr_; }

    int frames_seen() const { return frames_seen_; }
    const std::vector<double>& green_trace() const { return green_; }

private:
    PulseParams params_{};
    double fps_ = 30.0;
    int width_ = 0;
    int height_ = 0;
    int frames_seen_ = 0;
    bool have_lp_ = false;
    ImageF lp_high_;
    ImageF lp_low_;
    std::vector<double> green_;
    int window_samples_ = 0;
    bool have_display_ = false;
    double displayed_ = 0.0;
    double displayed_snr_ = 0.0;

    bool bpm_from_trace(double& bpm_out, double* snr_out) const;
};

}  // namespace airpulse
