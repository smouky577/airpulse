#pragma once

#include "airpulse/image.hpp"

namespace airpulse {

// Dense optical flow, Gunnar Farneback, "Two-Frame Motion Estimation Based on
// Polynomial Expansion" (SCIA 2003).
//
// This is a scalar C++17 port of the OpenCV 4.11 CPU path
// cv::calcOpticalFlowFarneback with flags = 0 (box filter, not Gaussian),
// so the displacement matches evm_prototype.py on the same frames.
// Images are single-channel float, usually 0..255. flow is 2-channel (dx, dy).
void calc_optical_flow_farneback(const ImageF& prev, const ImageF& next, ImageF& flow,
                                 double pyr_scale, int levels, int winsize, int iterations,
                                 int poly_n, double poly_sigma);

}  // namespace airpulse
