package com.airpulse.app.pulse

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Heart-rate estimator from one number per frame (ROI mean of G).
 * Keeps the last [windowSec] seconds in a fixed circular buffer (no frames stored),
 * resamples to a uniform grid (camera fps is not perfectly constant), detrends,
 * applies a Hann window and finds the spectral peak in [fMin, fMax].
 */
class PulseEstimator(private val windowSec: Double = 10.0) {

    /** [peaks]: up to 4 interior local maxima as (hz, power), strongest first. */
    data class Result(val hz: Double, val snr: Double, val peaks: List<Pair<Double, Double>> = emptyList())

    private val cap = 1024 // > 10 s at 60 fps
    private val t = DoubleArray(cap)
    private val v = DoubleArray(cap)
    private var head = 0
    private var count = 0

    private val re = DoubleArray(NFFT)
    private val im = DoubleArray(NFFT)
    private val power = DoubleArray(NFFT / 2)

    fun reset() {
        head = 0
        count = 0
    }

    fun add(time: Double, value: Double) {
        if (count > 0 && time <= t[(head - 1 + cap) % cap]) return
        t[head] = time
        v[head] = value
        head = (head + 1) % cap
        if (count < cap) count++
    }

    fun estimate(fMin: Double = 0.8, fMax: Double = 3.0): Result? {
        if (count < 40) return null
        val newest = (head - 1 + cap) % cap
        val tEnd = t[newest]
        var n = 0
        var idx = newest
        while (n < count && tEnd - t[idx] <= windowSec) {
            n++
            idx = (idx - 1 + cap) % cap
        }
        if (n < 40) return null
        val start = (newest - n + 1 + cap) % cap
        val tStart = t[start]
        val span = tEnd - tStart
        if (span < MIN_SPAN_SEC) return null

        // Uniform resampling (linear interpolation) at FS.
        val m = minOf((span * FS).toInt(), NFFT)
        var j = 0 // offset from start
        for (i in 0 until m) {
            val tt = tStart + i / FS
            while (j < n - 2 && t[(start + j + 1) % cap] < tt) j++
            val i0 = (start + j) % cap
            val i1 = (start + j + 1) % cap
            val dt = t[i1] - t[i0]
            val a = if (dt > 0) ((tt - t[i0]) / dt).coerceIn(0.0, 1.0) else 0.0
            re[i] = v[i0] + a * (v[i1] - v[i0])
        }

        // Linear detrend.
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in 0 until m) {
            val x = i.toDouble()
            sx += x; sy += re[i]; sxx += x * x; sxy += x * re[i]
        }
        val den = m * sxx - sx * sx
        val slope = if (den != 0.0) (m * sxy - sx * sy) / den else 0.0
        val icpt = (sy - slope * sx) / m
        for (i in 0 until m) re[i] -= icpt + slope * i
        // ~1 s moving-average removal (as in AirPulse estimate_bpm): kills slow drift,
        // breathing and motion that otherwise pile up at the low band edge.
        val k = FS.toInt()
        for (i in 0 until m) im[i] = re[i]
        for (i in 0 until m) {
            val a = maxOf(0, i - k / 2)
            val b = minOf(m - 1, i + k / 2)
            var s = 0.0
            for (q in a..b) s += im[q]
            re[i] = im[i] - s / (b - a + 1)
        }
        for (i in 0 until m) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / (m - 1))
            re[i] *= w
        }
        for (i in m until NFFT) re[i] = 0.0
        im.fill(0.0)
        fft(re, im)
        for (k in 0 until NFFT / 2) power[k] = re[k] * re[k] + im[k] * im[k]

        val df = FS / NFFT
        val kLo = maxOf(1, (fMin / df).toInt())
        val kHi = minOf(NFFT / 2 - 2, (fMax / df).toInt() + 1)
        // Strongest *interior* local maximum: a peak sitting on the band edge is leakage
        // from slow drift/motion below the band, not a heartbeat.
        var kPk = -1
        for (k in kLo + 1 until kHi) {
            if (power[k] >= power[k - 1] && power[k] >= power[k + 1] && (kPk < 0 || power[k] > power[kPk])) kPk = k
        }
        if (kPk < 0 || power[kPk] <= 0.0) return null

        // Parabolic interpolation on log power.
        val a = ln(power[kPk - 1] + 1e-30)
        val b = ln(power[kPk] + 1e-30)
        val c = ln(power[kPk + 1] + 1e-30)
        val d = a - 2 * b + c
        val delta = if (d != 0.0) (0.5 * (a - c) / d).coerceIn(-0.5, 0.5) else 0.0
        val hz = (kPk + delta) * df

        // SNR: peak (±2 bins) vs the rest of the band.
        var peak = 0.0; var rest = 0.0; var nRest = 0
        for (k in kLo..kHi) {
            if (kotlin.math.abs(k - kPk) <= 2) peak += power[k] else { rest += power[k]; nRest++ }
        }
        val snr = if (nRest > 0 && rest > 0) (peak / 5.0) / (rest / nRest) else 0.0
        if (hz < fMin || hz > fMax) return null
        val peaks = ArrayList<Pair<Double, Double>>()
        for (k in kLo + 1 until kHi) {
            if (power[k] >= power[k - 1] && power[k] >= power[k + 1]) peaks.add(k * df to power[k])
        }
        peaks.sortByDescending { it.second }
        return Result(hz, snr, peaks.take(4))
    }

    companion object {
        const val FS = 30.0
        const val NFFT = 2048
        const val MIN_SPAN_SEC = 6.0 // AirPulse bpm_min_sec

        /** In-place iterative radix-2 FFT. */
        fun fft(re: DoubleArray, im: DoubleArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
                j = j xor bit
                if (i < j) {
                    var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                    tmp = im[i]; im[i] = im[j]; im[j] = tmp
                }
            }
            var len = 2
            while (len <= n) {
                val ang = -2.0 * PI / len
                val wr = cos(ang); val wi = sin(ang)
                var i = 0
                while (i < n) {
                    var cr = 1.0; var ci = 0.0
                    for (k in 0 until len / 2) {
                        val ur = re[i + k]; val ui = im[i + k]
                        val xr = re[i + k + len / 2]; val xi = im[i + k + len / 2]
                        val vr = xr * cr - xi * ci
                        val vi = xr * ci + xi * cr
                        re[i + k] = ur + vr; im[i + k] = ui + vi
                        re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                        val nr = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = nr
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
