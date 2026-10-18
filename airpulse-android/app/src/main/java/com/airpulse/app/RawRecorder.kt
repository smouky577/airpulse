package com.airpulse.app

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Uncompressed BGR frames plus a small header. Lossy video is not used:
 * compression removes the pulse and the air shift.
 *
 * Layout, little-endian: 8-byte magic "AIRPULS1", int32 width, height, channels,
 * mode, double fps, int64 exposureNs, int32 iso, int32 orientation (rotation
 * degrees, bit 16 = mirrored), then frames of
 * int64 timestampNs + width*height*channels bytes.
 */
class RawRecorder(private val directory: File) {
    data class Header(
        val width: Int,
        val height: Int,
        val fps: Double,
        val exposureNs: Long,
        val iso: Int,
        val airMode: Boolean,
        val rotation: Int = 0,
        val mirror: Boolean = false,
    )

    private var stream: BufferedOutputStream? = null
    private var current: File? = null
    private var bytesWritten: Long = 0
    var frameCount: Int = 0
        private set

    fun start(header: Header): File {
        stop()
        directory.mkdirs()
        val file = File(directory, "airpulse-${System.currentTimeMillis()}.apraw")
        val out = BufferedOutputStream(FileOutputStream(file), 1024 * 1024)
        val buf = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("AIRPULS1".toByteArray(Charsets.US_ASCII))
        buf.putInt(header.width)
        buf.putInt(header.height)
        buf.putInt(3)
        buf.putInt(if (header.airMode) 1 else 0)
        buf.putDouble(header.fps)
        buf.putLong(header.exposureNs)
        buf.putInt(header.iso)
        buf.putInt(header.rotation or (if (header.mirror) MIRROR_FLAG else 0))
        out.write(buf.array())
        stream = out
        current = file
        bytesWritten = 48
        frameCount = 0
        return file
    }

    fun writeFrame(timestampNs: Long, bgr: ByteArray, length: Int) {
        val out = stream ?: return
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        head.putLong(timestampNs)
        out.write(head.array())
        out.write(bgr, 0, length)
        bytesWritten += 8L + length
        frameCount += 1
    }

    fun stop(): File? {
        stream?.flush()
        stream?.close()
        stream = null
        val file = current
        current = null
        return file
    }

    fun isRecording(): Boolean = stream != null

    fun bytes(): Long = bytesWritten

    fun list(): List<File> {
        directory.mkdirs()
        return directory.listFiles { f -> f.name.endsWith(".apraw") }?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    companion object {
        private const val MIRROR_FLAG = 1 shl 16

        fun readHeader(file: File): Header {
            DataInputStream(FileInputStream(file)).use { input ->
                val magic = ByteArray(8)
                input.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != "AIRPULS1") {
                    error("Непознат запис")
                }
                val raw = ByteArray(40)
                input.readFully(raw)
                val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                val width = buf.int
                val height = buf.int
                buf.int
                val mode = buf.int
                val fps = buf.double
                val exposure = buf.long
                val iso = buf.int
                val orientation = buf.int
                return Header(
                    width, height, fps, exposure, iso, mode == 1,
                    rotation = orientation and 0xFFFF,
                    mirror = (orientation and MIRROR_FLAG) != 0,
                )
            }
        }

        fun readFrames(file: File, onFrame: (timestampNs: Long, bgr: ByteArray) -> Boolean) {
            val header = readHeader(file)
            val frameBytes = header.width * header.height * 3
            DataInputStream(FileInputStream(file)).use { input ->
                var left = 48L
                while (left > 0) {
                    val skipped = input.skip(left)
                    if (skipped <= 0) return
                    left -= skipped
                }
                val stamp = ByteArray(8)
                val bgr = ByteArray(frameBytes)
                while (true) {
                    val n = input.read(stamp)
                    if (n < 0) break
                    if (n < 8) break
                    var got = n
                    while (got < 8) {
                        val r = input.read(stamp, got, 8 - got)
                        if (r < 0) return
                        got += r
                    }
                    input.readFully(bgr)
                    val ts = ByteBuffer.wrap(stamp).order(ByteOrder.LITTLE_ENDIAN).long
                    if (!onFrame(ts, bgr)) return
                }
            }
        }
    }
}
