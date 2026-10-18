// Desktop checks for the portable core. Fixtures come from tests/make_fixtures.py,
// which runs the Python prototype and OpenCV on the same synthetic clips.

#include "airpulse/bos_flow.hpp"
#include "airpulse/evm_pulse.hpp"
#include "airpulse/farneback.hpp"
#include "airpulse/image.hpp"
#include "airpulse/signal.hpp"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <string>
#include <vector>

namespace {

int g_fails = 0;

void expect(bool ok, const std::string& name) {
    if (ok) {
        std::cout << "ok   " << name << "\n";
    } else {
        std::cout << "FAIL " << name << "\n";
        ++g_fails;
    }
}

void expect_close(double got, double want, double tol, const std::string& name) {
    const double err = std::fabs(got - want);
    if (err <= tol) {
        std::cout << "ok   " << name << "  (" << got << ")\n";
    } else {
        std::cout << "FAIL " << name << "  got " << got << " want " << want << " tol " << tol << "\n";
        ++g_fails;
    }
}

template <typename T>
bool read_exact(std::ifstream& in, T* data, size_t count) {
    in.read(reinterpret_cast<char*>(data), static_cast<std::streamsize>(count * sizeof(T)));
    return static_cast<size_t>(in.gcount()) == count * sizeof(T);
}

bool load_f32(const std::string& path, airpulse::ImageF& image) {
    std::ifstream in(path, std::ios::binary);
    int32_t w = 0, h = 0, c = 0;
    if (!in || !read_exact(in, &w, 1) || !read_exact(in, &h, 1) || !read_exact(in, &c, 1)) return false;
    image = airpulse::ImageF::zeros(w, h, c);
    return read_exact(in, image.data.data(), image.data.size());
}

bool load_u8(const std::string& path, airpulse::ImageU8& image) {
    std::ifstream in(path, std::ios::binary);
    int32_t w = 0, h = 0, c = 0;
    if (!in || !read_exact(in, &w, 1) || !read_exact(in, &h, 1) || !read_exact(in, &c, 1)) return false;
    image = airpulse::ImageU8::zeros(w, h, c);
    return read_exact(in, image.data.data(), image.data.size());
}

float max_abs(const airpulse::ImageF& a, const airpulse::ImageF& b) {
    float m = 0.f;
    const size_t n = std::min(a.data.size(), b.data.size());
    for (size_t i = 0; i < n; ++i) m = std::max(m, std::fabs(a.data[i] - b.data[i]));
    return m;
}

double mean_abs_u8(const airpulse::ImageU8& a, const airpulse::ImageU8& b) {
    double s = 0.0;
    int peak = 0;
    for (size_t i = 0; i < a.data.size(); ++i) {
        const int d = std::abs(static_cast<int>(a.data[i]) - static_cast<int>(b.data[i]));
        peak = std::max(peak, d);
        s += d;
    }
    std::cout << "    mean abs " << (s / a.data.size()) << "  max " << peak << "\n";
    return s / a.data.size();
}

void write_ppm(const std::string& path, const airpulse::ImageU8& bgr) {
    std::ofstream out(path, std::ios::binary);
    out << "P6\n" << bgr.width << " " << bgr.height << "\n255\n";
    for (int i = 0; i < bgr.width * bgr.height; ++i) {
        const char rgb[3] = {
            static_cast<char>(bgr.data[i * 3 + 2]),
            static_cast<char>(bgr.data[i * 3 + 1]),
            static_cast<char>(bgr.data[i * 3 + 0]),
        };
        out.write(rgb, 3);
    }
}

void test_iir_step() {
    const double fps = 30.0;
    const double cutoff = 1.0;
    const double a = airpulse::iir_coeff(cutoff, fps);
    double lp = 0.0;
    double worst = 0.0;
    for (int n = 0; n < 80; ++n) {
        airpulse::iir_step(lp, 1.0, a);
        const double closed = 1.0 - std::pow(1.0 - a, n + 1);
        worst = std::max(worst, std::fabs(lp - closed));
    }
    expect(worst < 1e-9, "IIR step response matches the closed form");
}

void test_checkerboard() {
    const airpulse::ImageU8 board = airpulse::make_checkerboard(8, 5, 2);
    bool exact = board.width == 8 && board.height == 5;
    for (int y = 0; y < 5 && exact; ++y) {
        for (int x = 0; x < 8; ++x) {
            const int want = (((x / 2) + (y / 2)) % 2) * 255;
            if (board.at(y, x) != want) exact = false;
        }
    }
    expect(exact, "checkerboard pixels are exact");
    expect(airpulse::make_checkerboard(4, 4, 1).at(1, 0) == 255, "1 px cell alternates");
}

void test_yiq_roundtrip() {
    airpulse::ImageU8 bgr = airpulse::ImageU8::zeros(4, 3, 3);
    for (size_t i = 0; i < bgr.data.size(); ++i) bgr.data[i] = static_cast<uint8_t>((i * 37) % 256);
    airpulse::PulseStream stream;
    stream.reset(4, 3, 30.0);
    // Frame 0 band-pass is zero, so the result is only the YIQ round trip.
    const airpulse::ImageU8 back = stream.magnify(bgr);
    int peak = 0;
    for (size_t i = 0; i < bgr.data.size(); ++i) {
        peak = std::max(peak, std::abs(static_cast<int>(bgr.data[i]) - static_cast<int>(back.data[i])));
    }
    expect(peak <= 1, "YIQ round trip stays within 1 level on the first frame");
}

void test_bpm_tones() {
    const double fps = 30.0;
    auto tone = [&](double bpm, double noise_amp) {
        const int n = static_cast<int>(12 * fps);
        std::vector<double> sig(n);
        for (int i = 0; i < n; ++i) {
            const double t = i / fps;
            // Deterministic noise so the test does not flicker.
            const double noise = noise_amp * std::sin(12.3 * t + (i % 5));
            sig[i] = std::sin(2.0 * 3.14159265358979323846 * (bpm / 60.0) * t) + 0.15 * t + noise;
        }
        double got = 0.0;
        const bool ok = airpulse::estimate_bpm(sig, fps, 0.8, 2.0, got);
        return ok ? got : -1.0;
    };
    const double clean = tone(72.0, 0.0);
    const double noisy = tone(72.0, 0.35);
    const double other = tone(100.0, 0.1);
    expect(std::fabs(clean - 72.0) <= 1.0, "BPM of a clean 72 bpm sinusoid is within 1");
    expect(std::fabs(noisy - 72.0) <= 2.0, "BPM of a noisy 72 bpm sinusoid is within 2");
    expect(std::fabs(other - 100.0) <= 1.0, "BPM of a 100 bpm sinusoid is within 1");
    std::cout << "    clean " << clean << "  noisy " << noisy << "  100bpm " << other << "\n";
}

void test_image_ops(const std::string& dir) {
    airpulse::ImageF in, want, got;
    expect(load_f32(dir + "/pyr_in.bin", in) && load_f32(dir + "/pyr_out.bin", want), "load pyrDown fixture");
    airpulse::pyr_down(in, got);
    expect(got.width == want.width && got.height == want.height && max_abs(got, want) < 1e-4f, "pyrDown matches OpenCV");

    expect(load_f32(dir + "/gauss0_in.bin", in) && load_f32(dir + "/gauss0_out.bin", want), "load sigma-0 blur fixture");
    airpulse::gaussian_blur(in, got, 3, 0.0);
    expect(max_abs(got, want) < 1e-4f, "Gaussian sigma 0 matches OpenCV binomial kernel");

    expect(load_f32(dir + "/gauss_in.bin", in) && load_f32(dir + "/gauss_out.bin", want), "load sigma 0.8 blur fixture");
    airpulse::gaussian_blur(in, got, 7, 0.8);
    expect(max_abs(got, want) < 1e-4f, "Gaussian sigma 0.8 matches OpenCV");

    expect(load_f32(dir + "/resize_in.bin", in) && load_f32(dir + "/resize_out.bin", want), "load resize fixture");
    airpulse::resize_linear(in, got, want.width, want.height);
    expect(max_abs(got, want) < 1e-4f, "linear resize matches OpenCV");

    airpulse::ImageU8 color, gray_want;
    expect(load_u8(dir + "/gray_in.bin", color) && load_u8(dir + "/gray_out.bin", gray_want), "load gray fixture");
    const airpulse::ImageU8 gray = airpulse::bgr_to_gray(color);
    int gray_peak = 0;
    for (size_t i = 0; i < gray.data.size(); ++i) {
        gray_peak = std::max(gray_peak, std::abs(static_cast<int>(gray.data[i]) - static_cast<int>(gray_want.data[i])));
    }
    expect(gray_peak == 0, "BGR to gray matches OpenCV");
}

void test_farneback(const std::string& dir) {
    airpulse::ImageU8 prev_u8, next_u8;
    airpulse::ImageF want;
    expect(load_u8(dir + "/flow_prev.bin", prev_u8) && load_u8(dir + "/flow_next.bin", next_u8) &&
               load_f32(dir + "/flow_out.bin", want),
           "load Farneback fixture");
    airpulse::ImageF flow;
    airpulse::calc_optical_flow_farneback(airpulse::to_float(prev_u8), airpulse::to_float(next_u8), flow, 0.5, 3, 15,
                                          3, 5, 1.1);
    const float err = max_abs(flow, want);
    std::cout << "    farneback max abs " << err << "\n";
    expect(flow.channels == 2 && err < 1e-3f, "Farneback matches OpenCV on a textured frame");
}

void test_sine_against_python(const std::string& dir) {
    std::ifstream in(dir + "/sine_bpm.txt");
    double want = 0.0;
    in >> want;
    const double fps = 30.0;
    const int n = static_cast<int>(12 * fps);
    std::vector<double> sig(n);
    for (int i = 0; i < n; ++i) sig[i] = std::sin(2.0 * 3.14159265358979323846 * 1.2 * i / fps);
    double got = 0.0;
    const bool ok = airpulse::estimate_bpm(sig, fps, 0.8, 2.0, got);
    expect(ok, "sine BPM estimator returns a value");
    expect_close(got, want, 0.05, "sine BPM matches the Python prototype");
}

void test_demo_pulse(const std::string& dir) {
    std::ifstream meta(dir + "/pulse_meta.txt");
    int w = 0, h = 0, n = 0, rx = 0, ry = 0, rw = 0, rh = 0;
    double fps = 0.0, want_bpm = 0.0;
    meta >> w >> h >> n >> fps >> want_bpm;
    meta >> rx >> ry >> rw >> rh;
    std::ifstream frames(dir + "/pulse_frames.bin", std::ios::binary);
    int32_t fw = 0, fh = 0, fn = 0;
    expect(static_cast<bool>(meta) && read_exact(frames, &fw, 1) && read_exact(frames, &fh, 1) &&
               read_exact(frames, &fn, 1) && fw == w && fh == h && fn == n,
           "load demo pulse frames");
    if (g_fails && n <= 0) return;

    airpulse::PulseStream stream;
    airpulse::PulseParams params;
    stream.reset(w, h, fps, params);
    const airpulse::Rect roi{rx, ry, rw, rh};
    const size_t frame_bytes = static_cast<size_t>(w) * h * 3;
    std::vector<uint8_t> raw(frame_bytes);
    airpulse::ImageU8 frame_200;
    for (int i = 0; i < n; ++i) {
        if (!read_exact(frames, raw.data(), raw.size())) {
            expect(false, "pulse frame truncated");
            return;
        }
        airpulse::ImageU8 frame = airpulse::ImageU8::zeros(w, h, 3);
        frame.data = raw;
        const airpulse::ImageU8 mag = stream.magnify(frame);
        stream.observe_roi(frame, roi);
        if (i == 0) {
            int peak = 0;
            for (size_t p = 0; p < raw.size(); ++p) {
                peak = std::max(peak, std::abs(static_cast<int>(raw[p]) - static_cast<int>(mag.data[p])));
            }
            expect(peak <= 1, "first magnified pulse frame is the YIQ round trip");
        }
        if (i == 200) frame_200 = mag;
    }
    double bpm = 0.0;
    expect(stream.final_bpm(bpm), "demo pulse has enough samples for BPM");
    expect_close(bpm, 72.0, 2.0, "demo pulse BPM is within 2 of 72");
    expect_close(bpm, want_bpm, 0.2, "demo pulse BPM matches the Python prototype");

    airpulse::ImageU8 want_mag;
    expect(load_u8(dir + "/pulse_mag_200.bin", want_mag), "load Python magnified frame 200");
    if (frame_200.data.size() == want_mag.data.size()) {
        const double mean = mean_abs_u8(frame_200, want_mag);
        expect(mean < 1.0, "magnified frame 200 is close to the prototype");
    }
}

void test_demo_air(const std::string& dir) {
    std::ifstream meta(dir + "/air_meta.txt");
    double fps = 0.0, want_p = 0.0;
    int index = 0;
    meta >> fps >> index >> want_p;
    std::ifstream stack_file(dir + "/air_stack.bin", std::ios::binary);
    int32_t w = 0, h = 0, n = 0;
    expect(static_cast<bool>(meta) && read_exact(stack_file, &w, 1) && read_exact(stack_file, &h, 1) &&
               read_exact(stack_file, &n, 1),
           "load air reference stack");

    airpulse::BosStream bos;
    bos.reset(fps);
    const size_t gray_bytes = static_cast<size_t>(w) * h;
    int ready_at = -1;
    for (int i = 0; i < n; ++i) {
        airpulse::ImageU8 gray = airpulse::ImageU8::zeros(w, h, 1);
        if (!read_exact(stack_file, gray.data.data(), gray_bytes)) {
            expect(false, "air stack truncated");
            return;
        }
        if (bos.add_reference(gray)) ready_at = i;
    }
    expect(ready_at == n - 1, "reference becomes ready on the last stored frame");

    airpulse::ImageU8 ref_want;
    if (load_u8(dir + "/air_ref.bin", ref_want) && ref_want.data.size() == bos.reference().data.size()) {
        int peak = 0;
        for (size_t i = 0; i < ref_want.data.size(); ++i) {
            peak = std::max(peak, std::abs(static_cast<int>(ref_want.data[i]) - static_cast<int>(bos.reference().data[i])));
        }
        std::cout << "    reference max gray diff " << peak << "\n";
        expect(peak <= 1, "blurred median reference is within 1 level of OpenCV");
    }

    airpulse::ImageU8 frame;
    expect(load_u8(dir + "/air_frame.bin", frame), "load air test frame");
    airpulse::ImageU8 heat;
    airpulse::ImageF mag;
    bos.process(frame, heat, mag);

    airpulse::ImageF want_mag;
    airpulse::ImageU8 want_heat;
    expect(load_f32(dir + "/air_mag.bin", want_mag) && load_u8(dir + "/air_heat.bin", want_heat), "load air reference output");
    const float mag_err = max_abs(mag, want_mag);
    double mag_mean = 0.0;
    for (size_t i = 0; i < mag.data.size(); ++i) mag_mean += std::fabs(mag.data[i] - want_mag.data[i]);
    mag_mean /= mag.data.size();
    std::cout << "    air magnitude mean abs " << mag_mean << "  max " << mag_err << "\n";
    expect(mag_mean < 0.01 && mag_err < 0.05f, "BOS magnitude matches the prototype");
    expect_close(bos.last_percentile995(), want_p, 0.02, "99.5th percentile displacement");
    const double heat_mean = mean_abs_u8(heat, want_heat);
    expect(heat_mean < 2.0, "inferno heatmap is close to the prototype");
    write_ppm(dir + "/cpp_air_heat.ppm", heat);
    write_ppm(dir + "/py_air_heat.ppm", want_heat);
}

}  // namespace

int main(int argc, char** argv) {
    test_iir_step();
    test_checkerboard();
    test_yiq_roundtrip();
    test_bpm_tones();
    if (argc < 2) {
        std::cerr << "fixture directory required\n";
        return 2;
    }
    const std::string dir = argv[1];
    test_image_ops(dir);
    test_farneback(dir);
    test_sine_against_python(dir);
    test_demo_pulse(dir);
    test_demo_air(dir);
    if (g_fails) {
        std::cout << g_fails << " failed\n";
        return 1;
    }
    std::cout << "all tests passed\n";
    return 0;
}
