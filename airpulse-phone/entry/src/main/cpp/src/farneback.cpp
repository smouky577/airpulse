#include "airpulse/farneback.hpp"

#include <algorithm>
#include <cmath>
#include <stdexcept>
#include <vector>

namespace airpulse {
namespace {

constexpr int kMinLevelSize = 32;

int cv_round_nonneg(double v) {
    return static_cast<int>(std::floor(v + 0.5));
}

bool invert6(double a[6][6], double inv_out[6][6]) {
    double m[6][12] = {};
    for (int i = 0; i < 6; ++i) {
        for (int j = 0; j < 6; ++j) m[i][j] = a[i][j];
        m[i][i + 6] = 1.0;
    }
    for (int col = 0; col < 6; ++col) {
        int piv = col;
        for (int r = col + 1; r < 6; ++r) {
            if (std::fabs(m[r][col]) > std::fabs(m[piv][col])) piv = r;
        }
        if (std::fabs(m[piv][col]) < 1e-14) return false;
        if (piv != col) {
            for (int j = 0; j < 12; ++j) std::swap(m[piv][j], m[col][j]);
        }
        const double div = m[col][col];
        for (int j = col; j < 12; ++j) m[col][j] /= div;
        for (int r = 0; r < 6; ++r) {
            if (r == col) continue;
            const double f = m[r][col];
            for (int j = col; j < 12; ++j) m[r][j] -= f * m[col][j];
        }
    }
    for (int i = 0; i < 6; ++i)
        for (int j = 0; j < 6; ++j) inv_out[i][j] = m[i][j + 6];
    return true;
}

struct PolyBasis {
    int n = 5;
    std::vector<double> g, xg, xxg;  // index 0 is offset -n
    double ig11 = 0, ig03 = 0, ig33 = 0, ig55 = 0;

    double g_at(int i) const { return g[i + n]; }
    double xg_at(int i) const { return xg[i + n]; }
    double xxg_at(int i) const { return xxg[i + n]; }
};

PolyBasis prepare_gaussian(int n, double sigma) {
    if (sigma < 1e-6) sigma = n * 0.3;
    PolyBasis b;
    b.n = n;
    const int len = 2 * n + 1;
    b.g.assign(len, 0.0);
    b.xg.assign(len, 0.0);
    b.xxg.assign(len, 0.0);
    double sum = 0.0;
    for (int x = -n; x <= n; ++x) {
        const double w = std::exp(-(static_cast<double>(x) * x) / (2.0 * sigma * sigma));
        b.g[x + n] = w;
        sum += w;
    }
    for (int x = -n; x <= n; ++x) {
        b.g[x + n] /= sum;
        b.xg[x + n] = x * b.g[x + n];
        b.xxg[x + n] = static_cast<double>(x) * x * b.g[x + n];
    }

    double G[6][6] = {};
    for (int y = -n; y <= n; ++y) {
        for (int x = -n; x <= n; ++x) {
            const double w = b.g_at(y) * b.g_at(x);
            G[0][0] += w;
            G[1][1] += w * x * x;
            G[3][3] += w * x * x * x * x;
            G[5][5] += w * x * x * y * y;
        }
    }
    G[2][2] = G[0][3] = G[0][4] = G[3][0] = G[4][0] = G[1][1];
    G[4][4] = G[3][3];
    G[3][4] = G[4][3] = G[5][5];
    double inv[6][6];
    if (!invert6(G, inv)) throw std::runtime_error("Farneback polynomial matrix is singular");
    b.ig11 = inv[1][1];
    b.ig03 = inv[0][3];
    b.ig33 = inv[3][3];
    b.ig55 = inv[5][5];
    return b;
}

// 5 coefficients per pixel, the OpenCV channel order.
std::vector<float> poly_exp(const ImageF& src, const PolyBasis& basis) {
    const int n = basis.n;
    const int w = src.width;
    const int h = src.height;
    std::vector<float> dst(static_cast<size_t>(w) * h * 5, 0.f);
    std::vector<double> row(static_cast<size_t>(w) * 3, 0.0);

    auto row_at = [&](int x, int c) -> double {
        if (x < 0) x = 0;
        if (x >= w) x = w - 1;
        return row[static_cast<size_t>(x) * 3 + c];
    };

    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            row[static_cast<size_t>(x) * 3 + 0] = src.at(y, x) * basis.g_at(0);
            row[static_cast<size_t>(x) * 3 + 1] = 0.0;
            row[static_cast<size_t>(x) * 3 + 2] = 0.0;
        }
        for (int k = 1; k <= n; ++k) {
            const float* s0 = src.data.data() + static_cast<size_t>(std::max(y - k, 0)) * w;
            const float* s1 = src.data.data() + static_cast<size_t>(std::min(y + k, h - 1)) * w;
            const double gk = basis.g_at(k);
            const double xk = basis.xg_at(k);
            const double xxk = basis.xxg_at(k);
            for (int x = 0; x < w; ++x) {
                const double p = static_cast<double>(s0[x]) + static_cast<double>(s1[x]);
                row[static_cast<size_t>(x) * 3 + 0] += gk * p;
                row[static_cast<size_t>(x) * 3 + 1] += xk * (static_cast<double>(s1[x]) - s0[x]);
                row[static_cast<size_t>(x) * 3 + 2] += xxk * p;
            }
        }
        for (int x = 0; x < w; ++x) {
            double b1 = row_at(x, 0) * basis.g_at(0);
            double b2 = 0.0;
            double b3 = row_at(x, 1) * basis.g_at(0);
            double b4 = 0.0;
            double b5 = row_at(x, 2) * basis.g_at(0);
            double b6 = 0.0;
            for (int k = 1; k <= n; ++k) {
                const double rp0 = row_at(x + k, 0);
                const double rm0 = row_at(x - k, 0);
                const double rp1 = row_at(x + k, 1);
                const double rm1 = row_at(x - k, 1);
                const double rp2 = row_at(x + k, 2);
                const double rm2 = row_at(x - k, 2);
                const double tg0 = rp0 + rm0;
                b1 += tg0 * basis.g_at(k);
                b4 += tg0 * basis.xxg_at(k);
                b2 += (rp0 - rm0) * basis.xg_at(k);
                b3 += (rp1 + rm1) * basis.g_at(k);
                b6 += (rp1 - rm1) * basis.xg_at(k);
                b5 += (rp2 + rm2) * basis.g_at(k);
            }
            float* d = dst.data() + (static_cast<size_t>(y) * w + x) * 5;
            d[1] = static_cast<float>(b2 * basis.ig11);
            d[0] = static_cast<float>(b3 * basis.ig11);
            d[3] = static_cast<float>(b1 * basis.ig03 + b4 * basis.ig33);
            d[2] = static_cast<float>(b1 * basis.ig03 + b5 * basis.ig33);
            d[4] = static_cast<float>(b6 * basis.ig55);
        }
    }
    return dst;
}

void update_matrices(const std::vector<float>& R0, const std::vector<float>& R1,
                     const ImageF& flow, std::vector<float>& matM, int y0, int y1) {
    static const float border[5] = {0.14f, 0.14f, 0.4472f, 0.4472f, 0.4472f};
    constexpr int kBorder = 5;
    const int w = flow.width;
    const int h = flow.height;
    if (y0 < 0) y0 = 0;
    if (y1 > h) y1 = h;
    for (int y = y0; y < y1; ++y) {
        for (int x = 0; x < w; ++x) {
            const float dx = flow.at(y, x, 0);
            const float dy = flow.at(y, x, 1);
            float fx = static_cast<float>(x) + dx;
            float fy = static_cast<float>(y) + dy;
            const int x1 = static_cast<int>(std::floor(fx));
            const int y1p = static_cast<int>(std::floor(fy));
            fx -= static_cast<float>(x1);
            fy -= static_cast<float>(y1p);

            const float* r0 = R0.data() + (static_cast<size_t>(y) * w + x) * 5;
            double r2, r3, r4, r5, r6;
            if (x1 >= 0 && x1 < w - 1 && y1p >= 0 && y1p < h - 1) {
                const float a00 = (1.f - fx) * (1.f - fy);
                const float a01 = fx * (1.f - fy);
                const float a10 = (1.f - fx) * fy;
                const float a11 = fx * fy;
                const float* p00 = R1.data() + (static_cast<size_t>(y1p) * w + x1) * 5;
                const float* p01 = p00 + 5;
                const float* p10 = R1.data() + (static_cast<size_t>(y1p + 1) * w + x1) * 5;
                const float* p11 = p10 + 5;
                double r[5];
                for (int c = 0; c < 5; ++c) {
                    r[c] = a00 * p00[c] + a01 * p01[c] + a10 * p10[c] + a11 * p11[c];
                }
                r4 = (r0[2] + r[2]) * 0.5;
                r5 = (r0[3] + r[3]) * 0.5;
                r6 = (r0[4] + r[4]) * 0.25;
                r2 = r[0];
                r3 = r[1];
            } else {
                r2 = 0.0;
                r3 = 0.0;
                r4 = r0[2];
                r5 = r0[3];
                r6 = r0[4] * 0.5;
            }
            r2 = (r0[0] - r2) * 0.5;
            r3 = (r0[1] - r3) * 0.5;
            r2 += r4 * dy + r6 * dx;
            r3 += r6 * dy + r5 * dx;

            if (x < kBorder || x >= w - kBorder || y < kBorder || y >= h - kBorder) {
                double scale = 1.0;
                if (x < kBorder) scale *= border[x];
                if (x >= w - kBorder) scale *= border[w - x - 1];
                if (y < kBorder) scale *= border[y];
                if (y >= h - kBorder) scale *= border[h - y - 1];
                r2 *= scale;
                r3 *= scale;
                r4 *= scale;
                r5 *= scale;
                r6 *= scale;
            }
            float* M = matM.data() + (static_cast<size_t>(y) * w + x) * 5;
            M[0] = static_cast<float>(r4 * r4 + r6 * r6);
            M[1] = static_cast<float>((r4 + r5) * r6);
            M[2] = static_cast<float>(r5 * r5 + r6 * r6);
            M[3] = static_cast<float>(r4 * r2 + r6 * r3);
            M[4] = static_cast<float>(r6 * r2 + r5 * r3);
        }
    }
}

void update_flow_blur(const std::vector<float>& R0, const std::vector<float>& R1, ImageF& flow,
                      std::vector<float>& matM, int block_size, bool update_matrices_flag) {
    const int w = flow.width;
    const int h = flow.height;
    const int m = block_size / 2;
    const double scale = 1.0 / (static_cast<double>(block_size) * block_size);
    const int min_update_stripe = std::max((1 << 10) / std::max(w, 1), block_size);
    const int pad = m + 1;
    std::vector<double> vsum(static_cast<size_t>(w + 2 * pad) * 5, 0.0);

    auto pix = [&](int i) -> double* { return vsum.data() + static_cast<size_t>(pad + i) * 5; };

    const float* row0 = matM.data();
    for (int x = 0; x < w; ++x) {
        double* p = pix(x);
        const float* s = row0 + static_cast<size_t>(x) * 5;
        for (int c = 0; c < 5; ++c) p[c] = static_cast<double>(s[c]) * (m + 2);
    }
    for (int y = 1; y < m; ++y) {
        const int yy = std::min(y, h - 1);
        const float* srow = matM.data() + static_cast<size_t>(yy) * w * 5;
        for (int x = 0; x < w; ++x) {
            double* p = pix(x);
            const float* s = srow + static_cast<size_t>(x) * 5;
            for (int c = 0; c < 5; ++c) p[c] += s[c];
        }
    }

    int y_done = 0;
    for (int y = 0; y < h; ++y) {
        const float* sub = matM.data() + static_cast<size_t>(std::max(y - m - 1, 0)) * w * 5;
        const float* add = matM.data() + static_cast<size_t>(std::min(y + m, h - 1)) * w * 5;
        for (int x = 0; x < w; ++x) {
            double* p = pix(x);
            const float* a = add + static_cast<size_t>(x) * 5;
            const float* s = sub + static_cast<size_t>(x) * 5;
            for (int c = 0; c < 5; ++c) p[c] += static_cast<double>(a[c]) - static_cast<double>(s[c]);
        }
        for (int i = 1; i <= m + 1; ++i) {
            const double* src = pix(0);
            double* d = pix(-i);
            for (int c = 0; c < 5; ++c) d[c] = src[c];
        }
        for (int i = w; i <= w + m; ++i) {
            const double* src = pix(w - 1);
            double* d = pix(i);
            for (int c = 0; c < 5; ++c) d[c] = src[c];
        }

        double g[5];
        {
            const double* p0 = pix(0);
            for (int c = 0; c < 5; ++c) g[c] = p0[c] * (m + 2);
        }
        for (int x = 1; x < m; ++x) {
            const double* p = pix(x);
            for (int c = 0; c < 5; ++c) g[c] += p[c];
        }
        for (int x = 0; x < w; ++x) {
            const double* pr = pix(x + m);
            const double* pl = pix(x - m - 1);
            for (int c = 0; c < 5; ++c) g[c] += pr[c] - pl[c];
            const double g11 = g[0] * scale;
            const double g12 = g[1] * scale;
            const double g22 = g[2] * scale;
            const double h1 = g[3] * scale;
            const double h2 = g[4] * scale;
            const double idet = 1.0 / (g11 * g22 - g12 * g12 + 1e-3);
            flow.at(y, x, 0) = static_cast<float>((g11 * h2 - g12 * h1) * idet);
            flow.at(y, x, 1) = static_cast<float>((g22 * h1 - g12 * h2) * idet);
        }

        const int y1 = (y == h - 1) ? h : y - block_size;
        if (update_matrices_flag && (y1 == h || y1 >= y_done + min_update_stripe)) {
            update_matrices(R0, R1, flow, matM, y_done, y1);
            y_done = y1;
        }
    }
}

ImageF blur_and_resize(const ImageF& src, int smooth, double sigma, int dw, int dh) {
    ImageF blurred;
    gaussian_blur(src, blurred, smooth, sigma);
    ImageF out;
    resize_linear(blurred, out, dw, dh);
    return out;
}

}  // namespace

void calc_optical_flow_farneback(const ImageF& prev, const ImageF& next, ImageF& flow,
                                 double pyr_scale, int levels, int winsize, int iterations,
                                 int poly_n, double poly_sigma) {
    if (prev.channels != 1 || next.channels != 1 || prev.width != next.width || prev.height != next.height) {
        throw std::invalid_argument("Farneback wants two single-channel images of the same size");
    }
    if (!(pyr_scale > 0.0 && pyr_scale < 1.0) || levels < 1 || winsize < 3 || poly_n < 1) {
        throw std::invalid_argument("Farneback parameters");
    }

    int used = 0;
    double scale_acc = 1.0;
    for (int k = 0; k < levels; ++k) {
        scale_acc *= pyr_scale;
        if (prev.width * scale_acc < kMinLevelSize || prev.height * scale_acc < kMinLevelSize) break;
        ++used;
    }

    const PolyBasis basis = prepare_gaussian(poly_n, poly_sigma);
    ImageF prev_flow;
    ImageF cur;

    for (int k = used; k >= 0; --k) {
        double scale = 1.0;
        for (int i = 0; i < k; ++i) scale *= pyr_scale;
        const double sigma = (1.0 / scale - 1.0) * 0.5;
        const int smooth = std::max(cv_round_nonneg(sigma * 5.0) | 1, 3);
        const int width = cv_round_nonneg(prev.width * scale);
        const int height = cv_round_nonneg(prev.height * scale);

        if (prev_flow.width == 0) {
            cur = ImageF::zeros(width, height, 2);
        } else {
            resize_linear(prev_flow, cur, width, height);
            const float s = static_cast<float>(1.0 / pyr_scale);
            for (float& v : cur.data) v *= s;
        }

        const ImageF I0 = blur_and_resize(prev, smooth, sigma, width, height);
        const ImageF I1 = blur_and_resize(next, smooth, sigma, width, height);
        const std::vector<float> R0 = poly_exp(I0, basis);
        const std::vector<float> R1 = poly_exp(I1, basis);
        std::vector<float> matM(static_cast<size_t>(width) * height * 5, 0.f);
        update_matrices(R0, R1, cur, matM, 0, height);
        for (int it = 0; it < iterations; ++it) {
            update_flow_blur(R0, R1, cur, matM, winsize, it < iterations - 1);
        }
        prev_flow = cur;
    }
    flow = std::move(cur);
}

}  // namespace airpulse
