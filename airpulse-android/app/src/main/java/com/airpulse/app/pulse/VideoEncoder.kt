package com.airpulse.app.pulse

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.view.Surface

/**
 * H.264 encoder fed through an input Surface (rendered by the GL thread) and muxed to MP4.
 * All calls must happen on the GL thread. Nothing is buffered beyond what MediaCodec holds.
 */
class VideoEncoder(val width: Int, val height: Int, fps: Int, bitrate: Int, private val pfd: ParcelFileDescriptor) {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface
    private val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var frames = 0

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()
        } catch (e: Exception) {
            codec.release()
            muxer.release()
            throw e
        }
    }

    /** Moves encoded output into the muxer. Non-blocking unless [endOfStream]. */
    fun drain(endOfStream: Boolean) {
        if (endOfStream) codec.signalEndOfInputStream()
        var idle = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000L else 0L)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idle > 200) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                        frames++
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                // Other negative codes (deprecated OUTPUT_BUFFERS_CHANGED): keep looping.
            }
        }
    }

    /** Releases everything and closes the file. Returns true if a playable file was written. */
    fun release(): Boolean {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { inputSurface.release() }
        val ok = muxerStarted && frames > 0
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        runCatching { pfd.close() }
        return ok
    }
}
