#include "airpulse/signal.hpp"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstddef>

namespace airpulse {
namespace {

constexpr double kPi = 3.14159265358979323846;

void fft_inplace(std::vector<std::complex<double>>& a) {
    const int n = static_cast<int>(a.size());
    for (int i = 1, j = 0; i < n; ++i) {
        int bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(a[i], a[j]);
    }
    for (int len = 2; len <= n; len <<= 1) {
        const double ang = -2.0 * kPi / len;
        const std::complex<double> wlen(std::cos(ang), std::sin(ang));
        for (int i = 0; i < n; i += len) {
            std::complex<double> w(1.0, 0.0);
            for (int j = 0; j < len / 2; ++j) {
                const std::complex<double> u = a[i + j];
                const std::complex<double> v = a[i + j + len / 2] * w;
                a[i + j] = u + v;
                a[i + j + len / 2] = u - v;
                w *= wlen;
            }
        }
    }
}

}  // namespace

double iir_coeff(double cutoff_hz, double fps) {
    return 1.0 - std::exp(-2.0 * kPi * cutoff_hz / fps);
}

double iir_step(double& lp, double x, double coeff) {
    lp += coeff * (x - lp);
    return lp;
}

bool estimate_bpm(const std::vector<double>& signal, double fps, double lo_hz, double hi_hz, double& bpm_out) {
    return estimate_bpm(signal, fps, lo_hz, hi_hz, bpm_out, nullptr);
}

bool estimate_bpm(const std::vector<double>& signal, double fps, double lo_hz, double hi_hz, double& bpm_out,
                  double* snr_out) {
    const int n = static_cast<int>(signal.size());
    if (n < 4 || fps <= 0.0) return false;

    double mean = 0.0;
    for (double v : signal) mean += v;
    mean /= n;

    std::vector<double> sig(n);
    for (int i = 0; i < n; ++i) sig[i] = signal[i] - mean;

    // ~1 second moving average. Same alignment as np.convolve(..., mode="same"):
    // the kernel hangs off both ends as zeros, it is not renormalized there.
    const int k = std::max(3, static_cast<int>(fps));
    for (int i = 0; i < n; ++i) {
        const int full = i + (k - 1) / 2;
        const int m0 = std::max(0, full - (k - 1));
        const int m1 = std::min(n - 1, full);
        double sum = 0.0;
        for (int m = m0; m <= m1; ++m) sum += sig[m];
        sig[i] -= sum / k;
    }

    if (n == 1) {
        sig[0] *= 1.0;
    } else {
        for (int i = 0; i < n; ++i) {
            const double hann = 0.5 - 0.5 * std::cos(2.0 * kPi * i / (n - 1));
            sig[i] *= hann;
        }
    }

    constexpr int nfft = 1 << 15;
    std::vector<std::complex<double>> spec(nfft);
    for (int i = 0; i < n; ++i) spec[i] = sig[i];
    fft_inplace(spec);

    double best = -1.0;
    int best_k = -1;
    const int bins = nfft / 2;
    for (int bin = 0; bin <= bins; ++bin) {
        const double freq = bin * fps / nfft;
        if (freq < lo_hz || freq > hi_hz) continue;
        const double mag = std::abs(spec[bin]);
        if (mag > best) {
            best = mag;
            best_k = bin;
        }
    }
    if (best_k < 0) return false;
    bpm_out = best_k * fps / nfft * 60.0;
    if (snr_out != nullptr) {
        const double peak_hz = best_k * fps / nfft;
        double near = 0.0;
        double rest = 0.0;
        for (int bin = 0; bin <= bins; ++bin) {
            const double freq = bin * fps / nfft;
            if (freq < lo_hz || freq > hi_hz) continue;
            const double power = std::norm(spec[bin]);
            if (std::abs(freq - peak_hz) <= 0.1) {
                near += power;
            } else {
                rest += power;
            }
        }
        *snr_out = near / std::max(rest, 1e-12);
    }
    return true;
}

float percentile_linear(const std::vector<float>& values, double percentile) {
    if (values.empty()) return 0.f;
    std::vector<float> v = values;
    std::sort(v.begin(), v.end());
    if (v.size() == 1) return v[0];
    const double pos = (percentile / 100.0) * (static_cast<double>(v.size()) - 1.0);
    const size_t lo = static_cast<size_t>(std::floor(pos));
    const size_t hi = static_cast<size_t>(std::ceil(pos));
    const double t = pos - static_cast<double>(lo);
    return static_cast<float>((1.0 - t) * v[lo] + t * v[hi]);
}

}  // namespace airpulse
