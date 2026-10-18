#pragma once

// Portable image buffers for the AirPulse core. No HarmonyOS or OpenCV types:
// the phone app (later) and the desktop tests both call this code.
//
// Color frames are interleaved BGR, the same channel order as evm_prototype.py,
// so channel 1 is the green sample used for pulse.

#include <cstddef>
#include <cstdint>
#include <vector>

namespace airpulse {

struct Rect {
    int x = 0;
    int y = 0;
    int width = 0;
    int height = 0;
};

struct ImageF {
    int width = 0;
    int height = 0;
    int channels = 1;
    std::vector<float> data;  // row-major, channels interleaved

    static ImageF zeros(int w, int h, int c);
    float& at(int y, int x, int c = 0);
    float at(int y, int x, int c = 0) const;
    bool same_size(const ImageF& o) const;
};

struct ImageU8 {
    int width = 0;
    int height = 0;
    int channels = 1;
    std::vector<uint8_t> data;

    static ImageU8 zeros(int w, int h, int c);
    uint8_t& at(int y, int x, int c = 0);
    uint8_t at(int y, int x, int c = 0) const;
};

ImageF to_float(const ImageU8& src);
ImageU8 to_u8_trunc(const ImageF& src);  // clip to [0, 255] then truncate, like NumPy astype(uint8)

// OpenCV COLOR_BGR2GRAY: round-to-nearest of 0.114 B + 0.587 G + 0.299 R.
ImageU8 bgr_to_gray(const ImageU8& bgr);

// Separable Gaussian. sigma <= 0 with ksize 1/3/5/7/9 uses OpenCV's fixed binomial kernel;
// otherwise the kernel matches getGaussianKernel (double exp, center weight exactly 1 before normalize).
// ksize must be odd and >= 1. Border is REFLECT_101, which is OpenCV's default.
void gaussian_blur(const ImageF& src, ImageF& dst, int ksize, double sigma);

// 8-bit blur used by the BOS prototype: auto ksize from sigma when ksize == 0
// (cvRound(sigma*6+1)|1), then the float kernel, rounded to nearest uint8.
void gaussian_blur_u8(const ImageU8& src, ImageU8& dst, int ksize, double sigma);

// OpenCV INTER_LINEAR with pixel-center alignment: fx = (x+0.5)*src/dst - 0.5, clamped.
void resize_linear(const ImageF& src, ImageF& dst, int dst_w, int dst_h);

// OpenCV pyrDown: 5-tap [1,4,6,4,1]/16, keep even pixels, REFLECT_101.
void pyr_down(const ImageF& src, ImageF& dst);

// Per-pixel median. Even counts average the two central values and truncate to uint8 (NumPy median).
ImageU8 median_u8(const std::vector<ImageU8>& frames);

// ((x/cell) + (y/cell)) % 2 * 255. cell >= 1. Exact integer pixels, no resampling.
ImageU8 make_checkerboard(int width, int height, int cell);

// cv2.COLORMAP_INFERNO. gray is single channel; result is BGR.
ImageU8 apply_inferno(const ImageU8& gray);

// Mean of one BGR/gray channel inside the rectangle. Empty ROI returns 0.
double mean_u8(const ImageU8& image, Rect roi, int channel);

}  // namespace airpulse
