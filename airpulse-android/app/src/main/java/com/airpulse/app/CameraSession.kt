package com.airpulse.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
/**
 * Camera2 analysis stream. Frames stay in YUV_420_888 — the measurement path
 * never goes through an encoded video. AE/AF/AWB lock and manual exposure use
 * the public Camera2 request keys (API 26+).
 */
class CameraSession(
    context: Context,
    private val onFrame: (Image) -> Unit,
    private val onCapture: (exposureNs: Long, iso: Int) -> Unit,
    private val reportError: (String) -> Unit,
) {
    data class CameraInfo(
        val id: String,
        val facingBack: Boolean,
        val sizes: List<Size>,
        val fpsRanges: List<Range<Int>>,
        val exposureRangeNs: Range<Long>,
        val isoRange: Range<Int>,
        val manualSensor: Boolean,
        val sensorOrientation: Int,
    )

    data class Config(
        val cameraId: String,
        val size: Size,
        val fpsRange: Range<Int>,
        val lock: Boolean,
        val exposureNs: Long?,
        val iso: Int,
    )

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("airpulse-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var focusDistance = 0f
    @Volatile var runningConfig: Config? = null
        private set

    fun cameras(): List<CameraInfo> {
        val out = ArrayList<CameraInfo>()
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val sizes = yuvSizes(map)
            if (sizes.isEmpty()) continue
            val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
                ?: listOf(Range(15, 30))
            val exposure = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                ?: Range(1_000_000L, 50_000_000L)
            val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(50, 800)
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val manual = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)
            out += CameraInfo(
                id = id,
                facingBack = facing != CameraCharacteristics.LENS_FACING_FRONT,
                sizes = sizes,
                fpsRanges = fps.sortedBy { it.upper },
                exposureRangeNs = exposure,
                isoRange = iso,
                manualSensor = manual,
                sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
            )
        }
        return out.sortedByDescending { it.facingBack }
    }

    @SuppressLint("MissingPermission")
    fun open(config: Config) {
        handler.post {
            try {
                closeOnCameraThread()
                runningConfig = config
                val reader = ImageReader.newInstance(config.size.width, config.size.height, ImageFormat.YUV_420_888, 3)
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    try {
                        onFrame(image)
                    } finally {
                        image.close()
                    }
                }, handler)
                this.reader = reader
                manager.openCamera(config.cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        device = camera
                        try {
                            camera.createCaptureSession(
                                listOf(reader.surface),
                                object : CameraCaptureSession.StateCallback() {
                                    override fun onConfigured(captureSession: CameraCaptureSession) {
                                        session = captureSession
                                        applyRepeating(config)
                                    }

                                    override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                                        reportError("Камерата не прие конфигурацията")
                                    }
                                },
                                handler,
                            )
                        } catch (ex: Exception) {
                            reportError(ex.message ?: "session")
                        }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (device == camera) device = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        this@CameraSession.reportError("Грешка на камерата $error")
                        camera.close()
                        if (device == camera) {
                            device = null
                            session = null
                            runningConfig = null
                        }
                    }
                }, handler)
            } catch (ex: Exception) {
                reportError(ex.message ?: "open")
            }
        }
    }

    fun update(config: Config) {
        val current = runningConfig
        if (current == null || current.cameraId != config.cameraId || current.size != config.size ||
            current.fpsRange != config.fpsRange
        ) {
            open(config)
            return
        }
        runningConfig = config
        handler.post { applyRepeating(config) }
    }

    fun close() {
        runningConfig = null
        handler.post { closeOnCameraThread() }
    }

    fun release() {
        val done = CountDownLatch(1)
        handler.post {
            closeOnCameraThread()
            done.countDown()
        }
        done.await(8, TimeUnit.SECONDS)
        thread.quitSafely()
        thread.join(2000)
    }

    private fun applyRepeating(config: Config) {
        val camera = device ?: return
        val captureSession = session ?: return
        val surface = reader?.surface ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.fpsRange)
            if (config.lock && config.exposureNs != null) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, config.exposureNs)
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, config.iso)
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                if (focusDistance > 0f) builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            } else if (config.lock) {
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                if (focusDistance > 0f) builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false)
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, false)
            }
            captureSession.setRepeatingRequest(builder.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { if (it > 0f) focusDistance = it }
                    val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
                    val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
                    onCapture(exposure, iso)
                }
            }, handler)
        } catch (ex: Exception) {
            reportError(ex.message ?: "request")
        }
    }

    private fun closeOnCameraThread() {
        session?.close()
        session = null
        device?.close()
        device = null
        reader?.close()
        reader = null
        runningConfig = null
    }

    private fun yuvSizes(map: StreamConfigurationMap): List<Size> {
        return map.getOutputSizes(ImageFormat.YUV_420_888)
            ?.filter { it.width in 320..1920 && it.height in 240..1080 }
            ?.sortedByDescending { it.width.toLong() * it.height }
            ?: emptyList()
    }
}
