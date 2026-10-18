#include "airpulse/image.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace airpulse {
namespace {

int reflect101(int p, int len) {
    if (len <= 1) return 0;
    while (p < 0 || p >= len) {
        if (p < 0) p = -p;
        else p = 2 * len - 2 - p;
    }
    return p;
}

int cv_round_nonneg(double v) {
    return static_cast<int>(std::floor(v + 0.5));
}

std::vector<float> gaussian_kernel(int ksize, double sigma) {
    if (ksize < 1 || (ksize % 2) == 0) {
        throw std::invalid_argument("Gaussian ksize must be odd and >= 1");
    }
    // OpenCV getGaussianKernelBitExact: these sizes with sigma <= 0 are binomial, not the formula.
    if (sigma <= 0.0) {
        if (ksize == 1) return {1.f};
        if (ksize == 3) return {0.25f, 0.5f, 0.25f};
        if (ksize == 5) return {0.0625f, 0.25f, 0.375f, 0.25f, 0.0625f};
        if (ksize == 7) return {0.03125f, 0.109375f, 0.21875f, 0.28125f, 0.21875f, 0.109375f, 0.03125f};
        if (ksize == 9) {
            const double w[] = {4, 13, 30, 51, 60, 51, 30, 13, 4};
            std::vector<float> k(9);
            for (int i = 0; i < 9; ++i) k[i] = static_cast<float>(w[i] / 256.0);
            return k;
        }
        sigma = ((ksize - 1) * 0.5 - 1.0) * 0.3 + 0.8;
    }

    const int n2 = (ksize - 1) / 2;
    std::vector<double> vals(n2);
    int x = 1 - ksize;
    for (int i = 0; i < n2; ++i, x += 2) {
        vals[i] = std::exp((static_cast<double>(x) * x) * (-0.125 / (sigma * sigma)));
    }
    double total = 1.0;
    for (double t : vals) total += 2.0 * t;
    const double mul = 1.0 / total;
    std::vector<float> k(ksize);
    for (int i = 0; i < n2; ++i) {
        k[i] = static_cast<float>(vals[i] * mul);
        k[ksize - 1 - i] = k[i];
    }
    k[n2] = static_cast<float>(mul);
    return k;
}

}  // namespace

ImageF ImageF::zeros(int w, int h, int c) {
    ImageF im;
    im.width = w;
    im.height = h;
    im.channels = c;
    im.data.assign(static_cast<size_t>(w) * h * c, 0.f);
    return im;
}

float& ImageF::at(int y, int x, int c) {
    return data[(static_cast<size_t>(y) * width + x) * channels + c];
}

float ImageF::at(int y, int x, int c) const {
    return data[(static_cast<size_t>(y) * width + x) * channels + c];
}

bool ImageF::same_size(const ImageF& o) const {
    return width == o.width && height == o.height && channels == o.channels;
}

ImageU8 ImageU8::zeros(int w, int h, int c) {
    ImageU8 im;
    im.width = w;
    im.height = h;
    im.channels = c;
    im.data.assign(static_cast<size_t>(w) * h * c, 0);
    return im;
}

uint8_t& ImageU8::at(int y, int x, int c) {
    return data[(static_cast<size_t>(y) * width + x) * channels + c];
}

uint8_t ImageU8::at(int y, int x, int c) const {
    return data[(static_cast<size_t>(y) * width + x) * channels + c];
}

ImageF to_float(const ImageU8& src) {
    ImageF dst = ImageF::zeros(src.width, src.height, src.channels);
    for (size_t i = 0; i < src.data.size(); ++i) dst.data[i] = static_cast<float>(src.data[i]);
    return dst;
}

ImageU8 to_u8_trunc(const ImageF& src) {
    ImageU8 dst = ImageU8::zeros(src.width, src.height, src.channels);
    for (size_t i = 0; i < src.data.size(); ++i) {
        float v = src.data[i];
        if (v < 0.f) v = 0.f;
        if (v > 255.f) v = 255.f;
        dst.data[i] = static_cast<uint8_t>(v);
    }
    return dst;
}

ImageU8 bgr_to_gray(const ImageU8& bgr) {
    if (bgr.channels != 3) throw std::invalid_argument("bgr_to_gray expects 3 channels");
    ImageU8 gray = ImageU8::zeros(bgr.width, bgr.height, 1);
    const int n = bgr.width * bgr.height;
    for (int i = 0; i < n; ++i) {
        const int b = bgr.data[i * 3 + 0];
        const int g = bgr.data[i * 3 + 1];
        const int r = bgr.data[i * 3 + 2];
        // Matches cv2.cvtColor(..., COLOR_BGR2GRAY) on the fixture: round-to-nearest of the
        // standard weights. The fixed-point 14-bit form is off by one level on some colors.
        int y = static_cast<int>(std::nearbyint(0.114 * b + 0.587 * g + 0.299 * r));
        if (y < 0) y = 0;
        if (y > 255) y = 255;
        gray.data[i] = static_cast<uint8_t>(y);
    }
    return gray;
}

void gaussian_blur(const ImageF& src, ImageF& dst, int ksize, double sigma) {
    if (src.width <= 0 || src.height <= 0) throw std::invalid_argument("empty image");
    const std::vector<float> k = gaussian_kernel(ksize, sigma);
    const int r = ksize / 2;
    const int w = src.width;
    const int h = src.height;
    const int c = src.channels;
    ImageF tmp = ImageF::zeros(w, h, c);
    dst = ImageF::zeros(w, h, c);

    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            for (int ch = 0; ch < c; ++ch) {
                float s = 0.f;
                for (int i = 0; i < ksize; ++i) {
                    const int xx = reflect101(x + i - r, w);
                    s += k[i] * src.at(y, xx, ch);
                }
                tmp.at(y, x, ch) = s;
            }
        }
    }
    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            for (int ch = 0; ch < c; ++ch) {
                float s = 0.f;
                for (int i = 0; i < ksize; ++i) {
                    const int yy = reflect101(y + i - r, h);
                    s += k[i] * tmp.at(yy, x, ch);
                }
                dst.at(y, x, ch) = s;
            }
        }
    }
}

void gaussian_blur_u8(const ImageU8& src, ImageU8& dst, int ksize, double sigma) {
    if (ksize <= 0) {
        if (sigma <= 0.0) throw std::invalid_argument("blur needs a ksize or a sigma");
        // 8-bit auto size in OpenCV: cvRound(sigma * 6 + 1) | 1.
        ksize = cv_round_nonneg(sigma * 6.0 + 1.0) | 1;
    }
    ImageF blurred;
    gaussian_blur(to_float(src), blurred, ksize, sigma);
    dst = ImageU8::zeros(src.width, src.height, src.channels);
    for (size_t i = 0; i < blurred.data.size(); ++i) {
        int v = cv_round_nonneg(blurred.data[i]);
        if (v < 0) v = 0;
        if (v > 255) v = 255;
        dst.data[i] = static_cast<uint8_t>(v);
    }
}

void resize_linear(const ImageF& src, ImageF& dst, int dst_w, int dst_h) {
    if (src.width <= 0 || src.height <= 0 || dst_w <= 0 || dst_h <= 0) {
        throw std::invalid_argument("resize size");
    }
    const int c = src.channels;
    ImageF out = ImageF::zeros(dst_w, dst_h, c);
    const double sx = static_cast<double>(src.width) / dst_w;
    const double sy = static_cast<double>(src.height) / dst_h;
    for (int y = 0; y < dst_h; ++y) {
        double fy = (y + 0.5) * sy - 0.5;
        if (fy < 0.0) fy = 0.0;
        if (fy > src.height - 1) fy = src.height - 1;
        const int y0 = static_cast<int>(std::floor(fy));
        const int y1 = std::min(y0 + 1, src.height - 1);
        const float ty = static_cast<float>(fy - y0);
        for (int x = 0; x < dst_w; ++x) {
            double fx = (x + 0.5) * sx - 0.5;
            if (fx < 0.0) fx = 0.0;
            if (fx > src.width - 1) fx = src.width - 1;
            const int x0 = static_cast<int>(std::floor(fx));
            const int x1 = std::min(x0 + 1, src.width - 1);
            const float tx = static_cast<float>(fx - x0);
            for (int ch = 0; ch < c; ++ch) {
                const float a = src.at(y0, x0, ch) * (1.f - tx) + src.at(y0, x1, ch) * tx;
                const float b = src.at(y1, x0, ch) * (1.f - tx) + src.at(y1, x1, ch) * tx;
                out.at(y, x, ch) = a * (1.f - ty) + b * ty;
            }
        }
    }
    dst = std::move(out);
}

void pyr_down(const ImageF& src, ImageF& dst) {
    if (src.width <= 0 || src.height <= 0) throw std::invalid_argument("empty image");
    static const float k[5] = {1.f / 16.f, 4.f / 16.f, 6.f / 16.f, 4.f / 16.f, 1.f / 16.f};
    const int w = src.width;
    const int h = src.height;
    const int c = src.channels;
    const int ow = (w + 1) / 2;
    const int oh = (h + 1) / 2;
    ImageF tmp = ImageF::zeros(w, h, c);
    ImageF out = ImageF::zeros(ow, oh, c);

    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            for (int ch = 0; ch < c; ++ch) {
                float s = 0.f;
                for (int i = 0; i < 5; ++i) s += k[i] * src.at(y, reflect101(x + i - 2, w), ch);
                tmp.at(y, x, ch) = s;
            }
        }
    }
    for (int y = 0; y < oh; ++y) {
        const int sy = y * 2;
        for (int x = 0; x < ow; ++x) {
            const int sx = x * 2;
            for (int ch = 0; ch < c; ++ch) {
                float s = 0.f;
                for (int i = 0; i < 5; ++i) s += k[i] * tmp.at(reflect101(sy + i - 2, h), sx, ch);
                out.at(y, x, ch) = s;
            }
        }
    }
    dst = std::move(out);
}

ImageU8 median_u8(const std::vector<ImageU8>& frames) {
    if (frames.empty()) throw std::invalid_argument("median of no frames");
    const int w = frames[0].width;
    const int h = frames[0].height;
    const int c = frames[0].channels;
    const int n = static_cast<int>(frames.size());
    ImageU8 out = ImageU8::zeros(w, h, c);
    std::vector<int> hist(256);
    const int pixels = w * h * c;
    for (int i = 0; i < pixels; ++i) {
        std::fill(hist.begin(), hist.end(), 0);
        for (int f = 0; f < n; ++f) ++hist[frames[f].data[i]];
        // Even n: average of order stats n/2-1 and n/2, then truncate (NumPy).
        const int target_a = (n - 1) / 2;
        const int target_b = n / 2;
        int seen = 0;
        int a = 0;
        int b = 0;
        bool got_a = false;
        bool got_b = false;
        for (int v = 0; v < 256 && !got_b; ++v) {
            seen += hist[v];
            if (!got_a && seen > target_a) {
                a = v;
                got_a = true;
            }
            if (seen > target_b) {
                b = v;
                got_b = true;
            }
        }
        out.data[i] = static_cast<uint8_t>((a + b) / 2);
    }
    return out;
}

ImageU8 make_checkerboard(int width, int height, int cell) {
    if (width <= 0 || height <= 0 || cell <= 0) throw std::invalid_argument("checkerboard size");
    ImageU8 img = ImageU8::zeros(width, height, 1);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            img.at(y, x) = static_cast<uint8_t>((((x / cell) + (y / cell)) % 2) * 255);
        }
    }
    return img;
}

ImageU8 apply_inferno(const ImageU8& gray) {
    if (gray.channels != 1) throw std::invalid_argument("inferno expects one channel");
#include "airpulse/inferno_lut.inc"
    ImageU8 bgr = ImageU8::zeros(gray.width, gray.height, 3);
    const int n = gray.width * gray.height;
    for (int i = 0; i < n; ++i) {
        const int g = gray.data[i];
        bgr.data[i * 3 + 0] = kInfernoBgr[g * 3 + 0];
        bgr.data[i * 3 + 1] = kInfernoBgr[g * 3 + 1];
        bgr.data[i * 3 + 2] = kInfernoBgr[g * 3 + 2];
    }
    return bgr;
}

double mean_u8(const ImageU8& image, Rect roi, int channel) {
    if (roi.width <= 0 || roi.height <= 0) return 0.0;
    double sum = 0.0;
    int count = 0;
    const int x1 = std::min(image.width, roi.x + roi.width);
    const int y1 = std::min(image.height, roi.y + roi.height);
    for (int y = std::max(0, roi.y); y < y1; ++y) {
        for (int x = std::max(0, roi.x); x < x1; ++x) {
            sum += image.at(y, x, channel);
            ++count;
        }
    }
    return count ? sum / count : 0.0;
}

}  // namespace airpulse
