package com.airpulse.app

import java.nio.ByteBuffer

/**
 * JNI bridge to the portable C++ core. Camera2 YUV buffers are converted there,
 * then the same pulse / BOS code that the desktop tests check does the math.
 */
class NativeEngine {
    private var handle: Long = nativeCreate()

    fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    fun configure(
        width: Int,
        height: Int,
        fps: Double,
        airMode: Boolean,
        alpha: Double,
        lowHz: Double,
        highHz: Double,
        pyramid: Int,
        gain: Double,
        blur: Double,
        winsize: Int,
        highpass: Double,
    ) {
        nativeConfigure(handle, width, height, fps, airMode, alpha, lowHz, highHz, pyramid, gain, blur, winsize, highpass)
    }

    fun update(
        airMode: Boolean,
        alpha: Double,
        lowHz: Double,
        highHz: Double,
        pyramid: Int,
        gain: Double,
        blur: Double,
        winsize: Int,
        highpass: Double,
    ) {
        nativeUpdate(handle, airMode, alpha, lowHz, highHz, pyramid, gain, blur, winsize, highpass)
    }

    fun clearReference() = nativeClearReference(handle)

    fun processYuv(
        y: ByteBuffer, yPos: Int, yRow: Int, yPix: Int,
        u: ByteBuffer, uPos: Int, uRow: Int, uPix: Int,
        v: ByteBuffer, vPos: Int, vRow: Int, vPix: Int,
        srcW: Int, srcH: Int,
        roiX: Int, roiY: Int, roiW: Int, roiH: Int,
        pixels: IntArray,
        stats: FloatArray,
        record: ByteArray?,
    ): Boolean = nativeProcessYuv(
        handle, y, yPos, yRow, yPix, u, uPos, uRow, uPix, v, vPos, vRow, vPix,
        srcW, srcH, roiX, roiY, roiW, roiH, pixels, stats, record,
    )

    fun processBgr(
        bgr: ByteArray,
        roiX: Int, roiY: Int, roiW: Int, roiH: Int,
        pixels: IntArray,
        stats: FloatArray,
    ): Boolean = nativeProcessBgr(handle, bgr, roiX, roiY, roiW, roiH, pixels, stats)

    fun copyTrace(dest: FloatArray): Int = nativeCopyTrace(handle, dest)

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeConfigure(
        handle: Long, width: Int, height: Int, fps: Double, airMode: Boolean,
        alpha: Double, lowHz: Double, highHz: Double, pyramid: Int,
        gain: Double, blur: Double, winsize: Int, highpass: Double,
    )
    private external fun nativeUpdate(
        handle: Long, airMode: Boolean, alpha: Double, lowHz: Double, highHz: Double,
        pyramid: Int, gain: Double, blur: Double, winsize: Int, highpass: Double,
    )
    private external fun nativeClearReference(handle: Long)
    private external fun nativeProcessYuv(
        handle: Long,
        y: ByteBuffer, yPos: Int, yRow: Int, yPix: Int,
        u: ByteBuffer, uPos: Int, uRow: Int, uPix: Int,
        v: ByteBuffer, vPos: Int, vRow: Int, vPix: Int,
        srcW: Int, srcH: Int,
        roiX: Int, roiY: Int, roiW: Int, roiH: Int,
        pixels: IntArray, stats: FloatArray, record: ByteArray?,
    ): Boolean
    private external fun nativeProcessBgr(
        handle: Long, bgr: ByteArray, roiX: Int, roiY: Int, roiW: Int, roiH: Int,
        pixels: IntArray, stats: FloatArray,
    ): Boolean
    private external fun nativeCopyTrace(handle: Long, dest: FloatArray): Int

    companion object {
        init {
            System.loadLibrary("airpulse")
        }
    }
}
