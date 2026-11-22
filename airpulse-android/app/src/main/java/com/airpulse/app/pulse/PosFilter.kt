package com.airpulse.app.pulse

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * POS pulse signal (Wang et al., "Algorithmic principles of remote PPG", 2017), ported from
 * AirPulse's jni_engine.cpp. Each ~1.6 s window of skin RGB means is divided by its own mean,
 * then projected onto two axes orthogonal to the skin tone: S1 = G - B, S2 = G + B - 2R.
 * Brightness changes (motion, auto exposure) scale all three channels equally and cancel.
 * Windows are combined by overlap-add; a sample is final one window after it came in.
 */
class PosFilter {
    private var win = 48
    private val rgb = ArrayDeque<DoubleArray>()
    private val acc = ArrayDeque<Double>()
    private var s1 = DoubleArray(win)
    private var s2 = DoubleArray(win)

    fun reset(fps: Double) {
        win = max(8, (1.6 * fps).roundToInt())
        rgb.clear()
        acc.clear()
        s1 = DoubleArray(win)
        s2 = DoubleArray(win)
    }

    /** Returns the next finished POS sample, or null while the first window is filling. */
    fun push(r: Double, g: Double, b: Double): Double? {
        rgb.addLast(doubleArrayOf(r, g, b))
        acc.addLast(0.0)
        if (rgb.size > win) rgb.removeFirst()
        if (rgb.size < win) return null

        val m = DoubleArray(3)
        for (c in rgb) for (k in 0..2) m[k] += c[k]
        for (k in 0..2) m[k] = max(m[k] / win, 1e-6)

        var mean1 = 0.0
        var mean2 = 0.0
        for (i in 0 until win) {
            val c = rgb[i]
            val rn = c[0] / m[0]
            val gn = c[1] / m[1]
            val bn = c[2] / m[2]
            s1[i] = gn - bn
            s2[i] = gn + bn - 2.0 * rn
            mean1 += s1[i]
            mean2 += s2[i]
        }
        mean1 /= win
        mean2 /= win
        var var1 = 0.0
        var var2 = 0.0
        for (i in 0 until win) {
            var1 += (s1[i] - mean1) * (s1[i] - mean1)
            var2 += (s2[i] - mean2) * (s2[i] - mean2)
        }
        val ratio = sqrt(var1 / max(var2, 1e-18))

        var hMean = 0.0
        for (i in 0 until win) {
            s1[i] += ratio * s2[i]
            hMean += s1[i]
        }
        hMean /= win
        for (i in 0 until win) acc[i] = acc[i] + s1[i] - hMean

        return acc.removeFirst()
    }
}
