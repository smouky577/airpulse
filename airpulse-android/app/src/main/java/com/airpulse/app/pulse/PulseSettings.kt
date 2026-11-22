package com.airpulse.app.pulse

import kotlin.math.min

/** UI-controlled parameters, written on the main thread and read by the GL thread every frame. */
class PulseSettings {
    /**
     * Pyramid level 3..6, counted like AirPulse: relative to a 640x360 working image,
     * so level 4 gives the same ~40x23 cells AirPulse uses, whatever the camera resolution.
     */
    @Volatile var level: Int = 4

    /** Amplification of the I/Q chroma band (AirPulse "alpha"), 10..200. */
    @Volatile var alpha: Float = 60f

    /** Noise floor as a fraction of [THR_MAX], 0..1. Below it the overlay is transparent. */
    @Volatile var threshold: Float = 0f

    /** Lock-in mode on/off. */
    @Volatile var lockIn: Boolean = false

    /** In lock-in mode: colour by phase (hue = atan2(Q, I)) instead of amplitude heatmap. */
    @Volatile var phaseHue: Boolean = false

    /** Temporal band (Hz) for the magnification and the heart-rate search. */
    @Volatile var lowHz: Double = F_LOW
    @Volatile var highHz: Double = F_HIGH

    /** Finger-on-lens mode: whole frame, red/green absorption signal instead of face POS. */
    @Volatile var finger: Boolean = false

    /** AirPulse: luma gain = 0.4 * alpha, capped at 50. */
    val lumaGain: Float get() = min(alpha * 0.4f, 50f)

    companion object {
        const val F_LOW = 0.8   // Hz (AirPulse default)
        const val F_HIGH = 2.0  // Hz (AirPulse default)
        const val LOCKIN_TAU = 3.0 // s

        /** Width of the AirPulse working image the pyramid level is referenced to. */
        const val WORK_WIDTH = 640f

        /** Threshold slider at 100% = this amplified chroma magnitude. */
        const val THR_MAX = 0.15f

        /** Lock-in: amplified amplitude that maps to full heatmap opacity. */
        const val HEAT_SCALE = 0.15f

        /** ROI radius as a fraction of min(frame width, height). */
        const val ROI_RADIUS = 0.18f
    }
}
