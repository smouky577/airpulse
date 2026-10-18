#pragma once

// Background Oriented Schlieren: median reference, dense optical flow, magnitude heatmap.
// The reference window is bounded (N frames), so memory does not grow with the clip.

#include "airpulse/image.hpp"

#include <vector>

namespace airpulse {

struct BosParams {
    int ref_frames = 30;
    double gain = 3.0;          // displacement of 1/gain pixel maps to full scale
    double blur_sigma = 0.8;    // 0 disables the pre-blur
    int winsize = 15;
    int levels = 3;
    int iterations = 3;
    int poly_n = 5;
    double poly_sigma = 1.1;
    double highpass_hz = 0.0;   // 0 keeps the raw displacement; >0 removes slow drift
};

class BosStream {
public:
    void reset(double fps, BosParams params = {});
    // Gain and window changes keep the reference. A new blur sigma clears it.
    void set_params(BosParams params);
    void clear_reference();
    int reference_count() const;

    // Store a grayscale frame with no disturbance in front of the pattern.
    // Returns true once ref_frames have been combined into the reference.
    bool add_reference(const ImageU8& gray);
    bool reference_ready() const { return have_ref_; }
    const ImageU8& reference() const { return reference_; }

    // Displacement of gray against the reference. heatmap is BGR inferno.
    // magnitude is single-channel float, pixels.
    void process(const ImageU8& gray, ImageU8& heatmap_bgr, ImageF& magnitude);

    // 99.5th percentile of the last magnitude, for tuning gain on screen.
    float last_percentile995() const { return last_p995_; }

private:
    BosParams params_{};
    double fps_ = 30.0;
    std::vector<ImageU8> ref_stack_;
    ImageU8 reference_;
    bool have_ref_ = false;
    bool have_drift_ = false;
    ImageF drift_;  // 2-channel running mean of the flow
    float last_p995_ = 0.f;

    ImageU8 blur_gray(const ImageU8& gray) const;
};

}  // namespace airpulse
