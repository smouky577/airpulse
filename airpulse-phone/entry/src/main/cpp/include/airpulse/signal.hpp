#pragma once

// Streaming signal helpers shared by pulse (BPM) and air (drift removal).

#include <cstddef>
#include <vector>

namespace airpulse {

// One-pole low-pass coefficient: a = 1 - exp(-2*pi*f/fps).
double iir_coeff(double cutoff_hz, double fps);

// Step the filter once. lp is the running state (start it at 0 for a cold filter).
double iir_step(double& lp, double x, double coeff);

// Heart rate from a green-channel trace.
// Removes the mean, subtracts a ~1 second moving average (NumPy convolve mode "same",
// zero outside the trace), applies a symmetric Hann window, zero-pads to 2^15 and
// picks the peak in [lo_hz, hi_hz]. Returns false when the band is empty or the
// trace is shorter than 4 samples.
bool estimate_bpm(const std::vector<double>& signal, double fps, double lo_hz, double hi_hz, double& bpm_out);

// Same estimate. snr_out is the in-band power within 0.1 Hz of the peak divided by
// the rest of the band; below about 2 the peak is not trustworthy.
bool estimate_bpm(const std::vector<double>& signal, double fps, double lo_hz, double hi_hz, double& bpm_out,
                  double* snr_out);

// NumPy percentile, linear method, on a copy of the values.
float percentile_linear(const std::vector<float>& values, double percentile);

}  // namespace airpulse
