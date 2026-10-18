package com.airpulse.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.Image
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.util.Range
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as DrawSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val engine = NativeEngine()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val session = Session()
    private lateinit var recorder: RawRecorder
    private lateinit var camera: CameraSession
    private lateinit var pump: FramePump
    private lateinit var recordDir: File
    private var wasPaused = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        session.ui.permission = granted
        if (granted) loadCameras()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordDir = File(getExternalFilesDir(null) ?: filesDir, "recordings")
        recorder = RawRecorder(recordDir)
        pump = FramePump(applicationContext, session, mainHandler, recordDir, recorder, engine, ::logLine)
        camera = CameraSession(
            this,
            onFrame = { image -> pump.onImage(image) },
            onCapture = { exposure, iso -> pump.onCapture(exposure, iso) },
            reportError = { message ->
                logLine(message)
                mainHandler.post { session.ui.error = message }
            },
        )
        session.ui.permission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (session.ui.permission) loadCameras()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF3DDC84),
                    background = Color.Black,
                    surface = Color(0xFF161616),
                    onBackground = Color.White,
                    onSurface = Color.White,
                ),
            ) {
                AirPulseScreen(
                    session = session,
                    onAskPermission = { permission.launch(Manifest.permission.CAMERA) },
                    onApplyCamera = { applyCamera() },
                    onClearReference = { engine.clearReference() },
                    recordings = { recorder.list() },
                    onReplay = { file -> replay(file) },
                )
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!session.ui.busy) camera.close()
        wasPaused = true
    }

    override fun onResume() {
        super.onResume()
        if (!wasPaused) return
        wasPaused = false
        if (!session.ui.busy && session.ui.permission) {
            session.ui.error = null
            applyCamera()
        }
    }

    override fun onDestroy() {
        if (::camera.isInitialized) camera.release()
        if (::recorder.isInitialized) recorder.stop()
        engine.close()
        super.onDestroy()
    }

    private fun loadCameras() {
        val list = try {
            camera.cameras()
        } catch (ex: Exception) {
            session.ui.error = ex.message
            emptyList()
        }
        session.ui.cameras = list
        for (info in list) {
            logLine(
                "id=${info.id} back=${info.facingBack} manual=${info.manualSensor} " +
                    "sizes=${info.sizes.joinToString { "${it.width}x${it.height}" }} " +
                    "fps=${info.fpsRanges.joinToString { "${it.lower}-${it.upper}" }} " +
                    "exposure=${info.exposureRangeNs.lower}-${info.exposureRangeNs.upper} " +
                    "iso=${info.isoRange.lower}-${info.isoRange.upper}",
            )
        }
    }

    private fun applyCamera() {
        val controls = session.controls.get()
        val info = session.ui.cameras.firstOrNull { it.id == controls.cameraId } ?: return
        val iso = controls.iso.coerceIn(info.isoRange.lower, info.isoRange.upper)
        val exposure = controls.exposureNs?.coerceIn(info.exposureRangeNs.lower, info.exposureRangeNs.upper)
        camera.update(
            CameraSession.Config(
                cameraId = controls.cameraId,
                size = controls.size,
                fpsRange = controls.fpsRange,
                lock = controls.lock,
                exposureNs = if (controls.lock && info.manualSensor) exposure else if (controls.lock) null else null,
                iso = iso,
            ),
        )
    }

    private fun replay(file: File) {
        pump.paused.set(true)
        camera.close()
        session.ui.busy = true
        Thread {
            try {
                val header = RawRecorder.readHeader(file)
                val controls = session.controls.get()
                engine.configure(
                    header.width, header.height, header.fps, header.airMode,
                    controls.alpha, controls.lowHz, controls.highHz, controls.pyramid,
                    controls.gain, controls.blur, controls.winsize, controls.highpass,
                )
                val csv = File(file.parentFile, file.nameWithoutExtension + ".csv")
                val frameBytes = 8L + header.width.toLong() * header.height * 3
                val totalSec = ((file.length() - 48) / frameBytes / header.fps).roundToInt()
                mainHandler.post {
                    session.ui.bpm = -1f
                    session.ui.trace = FloatArray(0)
                }
                csv.bufferedWriter().use { out ->
                    out.write("t_sec,bpm,p995,contrast,motion,mean_mag,ref_count,snr\n")
                    val pixels = IntArray(header.width * header.height)
                    val stats = FloatArray(9)
                    val traceBuf = FloatArray(512)
                    val swap = header.rotation == 90 || header.rotation == 270
                    val outW = if (swap) header.height else header.width
                    val outH = if (swap) header.width else header.height
                    val shownPixels = IntArray(pixels.size)
                    val bitmaps = Array(3) { Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888) }
                    val roi = roiRect(header.width, header.height, 0.5f, 0.5f, 1f / 3f)
                    var index = 0
                    var firstStamp = 0L
                    val wallStart = System.nanoTime()
                    RawRecorder.readFrames(file) { stamp, bgr ->
                        engine.processBgr(bgr, roi[0], roi[1], roi[2], roi[3], pixels, stats)
                        if (index == 0) firstStamp = stamp
                        val waitMs = ((stamp - firstStamp) - (System.nanoTime() - wallStart)) / 1_000_000
                        if (waitMs > 0) Thread.sleep(waitMs)
                        orientPixels(pixels, header.width, header.height, outW, outH, header.rotation, header.mirror, shownPixels)
                        val bitmap = bitmaps[index % bitmaps.size]
                        bitmap.setPixels(shownPixels, 0, outW, 0, 0, outW, outH)
                        val trace = if (index % 10 == 0) {
                            val count = engine.copyTrace(traceBuf)
                            if (count > 1) traceBuf.copyOf(count) else null
                        } else {
                            null
                        }
                        val bpm = stats[0]
                        val snr = stats[8]
                        val second = (index / header.fps).toInt()
                        mainHandler.post {
                            val ui = session.ui
                            ui.bitmap = bitmap
                            ui.bpm = bpm
                            ui.snr = snr
                            if (trace != null) ui.trace = trace
                            ui.message = getString(R.string.replay_progress, second, totalSec)
                        }
                        val t = index / header.fps
                        out.write(
                            String.format(
                                Locale.US,
                                "%.4f,%.3f,%.5f,%.3f,%.3f,%.5f,%.0f,%.3f\n",
                                t, stats[0], stats[1], stats[2], stats[3], stats[7], stats[5], stats[8],
                            ),
                        )
                        index += 1
                        true
                    }
                }
                mainHandler.post { session.ui.message = getString(R.string.replay_done, csv.name) }
            } catch (ex: Exception) {
                logLine("replay ${ex.message}")
                mainHandler.post { session.ui.error = ex.message }
            } finally {
                pump.forceConfigure.set(true)
                mainHandler.post {
                    pump.paused.set(false)
                    session.ui.busy = false
                    applyCamera()
                }
            }
        }.start()
    }

    private fun logLine(line: String) {
        try {
            File(filesDir, "airpulse.log").appendText("${System.currentTimeMillis()} $line\n")
        } catch (_: Exception) {
        }
    }
}

class Session {
    val ui = LiveUi()
    val controls = AtomicReference(Controls())

    fun edit(block: (Controls) -> Controls) {
        while (true) {
            val current = controls.get()
            val next = block(current)
            if (controls.compareAndSet(current, next)) return
        }
    }
}

class LiveUi {
    var bitmap by mutableStateOf<Bitmap?>(null)
    var bpm by mutableFloatStateOf(-1f)
    var p995 by mutableFloatStateOf(0f)
    var contrast by mutableFloatStateOf(0f)
    var motion by mutableFloatStateOf(0f)
    var meanMag by mutableFloatStateOf(0f)
    var snr by mutableFloatStateOf(0f)
    var refReady by mutableStateOf(false)
    var refCount by mutableIntStateOf(0)
    var processMs by mutableFloatStateOf(0f)
    var measuredFps by mutableFloatStateOf(0f)
    var dropped by mutableIntStateOf(0)
    var cameraW by mutableIntStateOf(0)
    var cameraH by mutableIntStateOf(0)
    var workW by mutableIntStateOf(0)
    var workH by mutableIntStateOf(0)
    var exposureNs by mutableLongStateOf(0L)
    var iso by mutableIntStateOf(0)
    var recording by mutableStateOf(false)
    var recordSec by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var permission by mutableStateOf(false)
    var cameras by mutableStateOf<List<CameraSession.CameraInfo>>(emptyList())
    var hintMotion by mutableStateOf(false)
    var hintLight by mutableStateOf(false)
    var hintBlur by mutableStateOf(false)
    var hintPattern by mutableStateOf(false)
    var hintHot by mutableStateOf(false)
    var hintPhone by mutableStateOf(false)
    var hintFps by mutableStateOf(false)
    var trace by mutableStateOf(FloatArray(0))
    var busy by mutableStateOf(false)
}

data class Controls(
    val air: Boolean = false,
    val alpha: Double = 60.0,
    val lowHz: Double = 0.8,
    val highHz: Double = 2.0,
    val pyramid: Int = 4,
    val gain: Double = 3.0,
    val blur: Double = 0.8,
    val winsize: Int = 15,
    val highpass: Double = 0.0,
    val cameraId: String = "",
    val size: Size = Size(1280, 720),
    val fpsRange: Range<Int> = Range(30, 30),
    val lock: Boolean = false,
    val exposureNs: Long? = 20_000_000L,
    val iso: Int = 200,
    val roiCx: Float = 0.5f,
    val roiCy: Float = 0.5f,
    val roiScale: Float = 1f / 3f,
    val workW: Int = 640,
    val workH: Int = 360,
    val revision: Int = 0,
    val record: Boolean = false,
    val rotation: Int = 0,
    val mirror: Boolean = false,
)

class FramePump(
    private val context: Context,
    private val session: Session,
    private val main: Handler,
    private val recordDir: File,
    private val recorder: RawRecorder,
    private val engine: NativeEngine,
    private val log: (String) -> Unit,
) {
    val paused = AtomicBoolean(false)
    val forceConfigure = AtomicBoolean(true)

    private var pixels = IntArray(0)
    private var recordBuf = ByteArray(0)
    private val stats = FloatArray(9)
    private var configuredW = 0
    private var configuredH = 0
    private var configuredFps = 0.0
    private var seenRevision = -1
    private var lastStamp = 0L
    private var fpsEma = 0.0
    private var dropped = 0
    private var frames = 0
    private var loggedReject = false
    private var recordStartedNs = 0L
    private var recordW = 0
    private var recordH = 0
    private var csv: BufferedWriter? = null
    private val traceBuf = FloatArray(180)
    private var lightDrift = false
    private var latestTrace: FloatArray? = null
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private var slot = 0
    private var lastExposure = 0L
    private var lastIso = 0
    private var recordSec = 0
    private var oriented = IntArray(0)

    fun onCapture(exposureNs: Long, iso: Int) {
        val changed = lastExposure != exposureNs || lastIso != iso
        lastExposure = exposureNs
        lastIso = iso
        if (!changed) return
        main.post {
            session.ui.exposureNs = exposureNs
            session.ui.iso = iso
        }
    }

    fun onImage(image: Image) {
        if (paused.get()) return
        val controls = session.controls.get()
        if (controls.cameraId.isEmpty() || controls.workW < 16 || controls.workH < 16) return
        val targetFps = controls.fpsRange.upper.coerceAtLeast(1).toDouble()
        try {
            if (recorder.isRecording() && (recordW != controls.workW || recordH != controls.workH)) {
                finishRecord()
                session.edit { it.copy(record = false) }
            }
            if (forceConfigure.getAndSet(false) || configuredW != controls.workW || configuredH != controls.workH ||
                configuredFps != targetFps
            ) {
                engine.configure(
                    controls.workW, controls.workH, targetFps, controls.air,
                    controls.alpha, controls.lowHz, controls.highHz, controls.pyramid,
                    controls.gain, controls.blur, controls.winsize, controls.highpass,
                )
                configuredW = controls.workW
                configuredH = controls.workH
                configuredFps = targetFps
                seenRevision = controls.revision
                pixels = IntArray(controls.workW * controls.workH)
                recordBuf = ByteArray(pixels.size * 3)
                log("work ${controls.workW}x${controls.workH} @ $targetFps")
            } else if (seenRevision != controls.revision) {
                engine.update(
                    controls.air, controls.alpha, controls.lowHz, controls.highHz, controls.pyramid,
                    controls.gain, controls.blur, controls.winsize, controls.highpass,
                )
                seenRevision = controls.revision
            }
            val planes = image.planes
            if (planes.size < 3) return
            val y = planes[0]
            val u = planes[1]
            val v = planes[2]
            val center = sensorCenter(controls.roiCx, controls.roiCy, controls.rotation, controls.mirror)
            val roi = roiRect(controls.workW, controls.workH, center.first, center.second, controls.roiScale)
            val ok = engine.processYuv(
                y.buffer, y.buffer.position(), y.rowStride, y.pixelStride.coerceAtLeast(1),
                u.buffer, u.buffer.position(), u.rowStride, u.pixelStride.coerceAtLeast(1),
                v.buffer, v.buffer.position(), v.rowStride, v.pixelStride.coerceAtLeast(1),
                image.width, image.height,
                roi[0], roi[1], roi[2], roi[3],
                pixels, stats,
                if (controls.record) recordBuf else null,
            )
            if (!ok) {
                if (!loggedReject) {
                    loggedReject = true
                    log("processYuv rejected ${image.width}x${image.height} directY=${y.buffer.isDirect}")
                }
                return
            }
            frames += 1
            val stamp = image.timestamp
            if (lastStamp != 0L && stamp > lastStamp) {
                val dt = (stamp - lastStamp) / 1e9
                if (dt > 0) {
                    val instant = 1.0 / dt
                    fpsEma = if (fpsEma == 0.0) instant else fpsEma * 0.9 + instant * 0.1
                    val miss = ((stamp - lastStamp) / (1e9 / targetFps)).toInt() - 1
                    if (miss > 0) dropped += miss
                }
            }
            lastStamp = stamp
            if (!controls.air && frames % 10 == 0) {
                val count = engine.copyTrace(traceBuf)
                if (count > 1) latestTrace = traceBuf.copyOf(count)
                if (count > 4) {
                    val span = targetFps.roundToInt().coerceIn(2, count)
                    var lo = traceBuf[count - 1]
                    var hi = lo
                    for (i in count - span until count) {
                        val value = traceBuf[i]
                        if (value < lo) lo = value
                        if (value > hi) hi = value
                    }
                    lightDrift = hi - lo > 2.5f
                }
            }
            handleRecord(controls, image)
            publish(controls, image.width, image.height)
        } catch (ex: Exception) {
            log("frame ${ex.javaClass.simpleName}: ${ex.message}")
        }
    }

    private fun handleRecord(controls: Controls, image: Image) {
        if (controls.record && !recorder.isRecording()) {
            recordDir.mkdirs()
            val file = recorder.start(
                RawRecorder.Header(
                    width = controls.workW,
                    height = controls.workH,
                    fps = configuredFps,
                    exposureNs = lastExposure,
                    iso = lastIso,
                    airMode = controls.air,
                    rotation = controls.rotation,
                    mirror = controls.mirror,
                ),
            )
            csv = BufferedWriter(FileWriter(File(file.parentFile, file.nameWithoutExtension + ".csv")))
            csv?.write("t_sec,bpm,p995,contrast,motion,mean_mag,snr\n")
            recordStartedNs = System.nanoTime()
            recordW = controls.workW
            recordH = controls.workH
            main.post { session.ui.recording = true }
        }
        if (!controls.record && recorder.isRecording()) finishRecord()
        if (!recorder.isRecording()) return
        recordDir.mkdirs()
        val elapsed = (System.nanoTime() - recordStartedNs) / 1e9
        recordSec = elapsed.toInt()
        val free = StatFs(recordDir.absolutePath).availableBytes
        if (elapsed >= 20.0 || recorder.bytes() >= 400L * 1024 * 1024 || free < 200L * 1024 * 1024) {
            finishRecord()
            session.edit { it.copy(record = false) }
            return
        }
        recorder.writeFrame(image.timestamp, recordBuf, controls.workW * controls.workH * 3)
        val t = recorder.frameCount / configuredFps.coerceAtLeast(1.0)
        csv?.write(
            String.format(
                Locale.US,
                "%.4f,%.3f,%.5f,%.3f,%.3f,%.5f,%.3f\n",
                t, stats[0], stats[1], stats[2], stats[3], stats[7], stats[8],
            ),
        )
    }

    private fun finishRecord() {
        csv?.flush()
        csv?.close()
        csv = null
        val file = recorder.stop()
        val text = file?.let { context.getString(R.string.saved, it.name) }
        main.post {
            session.ui.recording = false
            if (text != null) session.ui.message = text
        }
    }

    private fun publish(controls: Controls, cameraW: Int, cameraH: Int) {
        val width = configuredW
        val height = configuredH
        if (width <= 0 || height <= 0 || pixels.size < width * height) return
        val swap = controls.rotation == 90 || controls.rotation == 270
        val outW = if (swap) height else width
        val outH = if (swap) width else height
        val source = orient(width, height, outW, outH, controls.rotation, controls.mirror)
        val current = bitmaps[slot]
        val bitmap = if (current == null || current.width != outW || current.height != outH) {
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also { bitmaps[slot] = it }
        } else {
            current
        }
        bitmap.setPixels(source, 0, outW, 0, 0, outW, outH)
        val recordSecNow = recordSec
        val shown = bitmap
        slot = (slot + 1) % bitmaps.size
        val trace = latestTrace
        latestTrace = null
        val snap = stats.copyOf()
        val fpsNow = fpsEma.toFloat()
        val droppedNow = dropped
        val lightNow = lightDrift
        val fpsUnstable = frames > 20 && fpsEma > 1 && abs(fpsEma - configuredFps) / configuredFps > 0.2
        main.post {
            val ui = session.ui
            ui.bitmap = shown
            ui.bpm = snap[0]
            ui.p995 = snap[1]
            ui.contrast = snap[2]
            ui.motion = snap[3]
            ui.meanMag = snap[7]
            ui.snr = snap[8]
            ui.refReady = snap[4] >= 0.5f
            ui.refCount = snap[5].toInt()
            ui.processMs = snap[6]
            ui.measuredFps = fpsNow
            ui.dropped = droppedNow
            ui.cameraW = cameraW
            ui.cameraH = cameraH
            ui.workW = width
            ui.workH = height
            ui.hintMotion = !controls.air && snap[3] > 8f
            ui.hintLight = !controls.air && lightNow
            ui.hintBlur = controls.air && snap[2] < 6f
            ui.hintPattern = controls.air && snap[2] >= 6f
            ui.hintHot = controls.air && snap[4] >= 0.5f && snap[1] * controls.gain.toFloat() > 1.2f
            ui.hintPhone = controls.air && snap[4] >= 0.5f && snap[7] > 0.25f
            ui.hintFps = fpsUnstable
            ui.recordSec = recordSecNow
            if (trace != null) ui.trace = trace
        }
    }

    private fun orient(w: Int, h: Int, outW: Int, outH: Int, rotation: Int, mirror: Boolean): IntArray {
        if (rotation == 0 && !mirror) return pixels
        if (oriented.size != w * h) oriented = IntArray(w * h)
        orientPixels(pixels, w, h, outW, outH, rotation, mirror, oriented)
        return oriented
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AirPulseScreen(
    session: Session,
    onAskPermission: () -> Unit,
    onApplyCamera: () -> Unit,
    onClearReference: () -> Unit,
    recordings: () -> List<File>,
    onReplay: (File) -> Unit,
) {
    val ui = session.ui
    if (!ui.permission) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Button(onClick = onAskPermission) { Text(stringResource(R.string.permission)) }
        }
        return
    }
    val cameras = ui.cameras
    if (cameras.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.permission), color = Color.White)
        }
        return
    }
    var camIndex by remember { mutableIntStateOf(cameras.indexOfFirst { it.facingBack }.coerceAtLeast(0)) }
    var sizeIndex by remember { mutableIntStateOf(0) }
    var fpsIndex by remember { mutableIntStateOf(0) }
    var air by remember { mutableStateOf(false) }
    var lock by remember { mutableStateOf(false) }
    var shutterNs by remember { mutableStateOf<Long?>(20_000_000L) }
    var alpha by remember { mutableFloatStateOf(60f) }
    var lowHz by remember { mutableFloatStateOf(0.8f) }
    var highHz by remember { mutableFloatStateOf(2.0f) }
    var pyramid by remember { mutableFloatStateOf(4f) }
    var gain by remember { mutableFloatStateOf(3f) }
    var blur by remember { mutableFloatStateOf(0.8f) }
    var iso by remember { mutableFloatStateOf(200f) }
    var showFiles by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var started by remember { mutableStateOf(false) }
    val replayText = stringResource(R.string.replay)

    fun infoAt(index: Int) = cameras[index.coerceIn(0, cameras.lastIndex)]

    fun push(reopenHint: Boolean = false) {
        val info = infoAt(camIndex)
        val size = info.sizes[sizeIndex.coerceIn(0, info.sizes.lastIndex)]
        val fps = info.fpsRanges[fpsIndex.coerceIn(0, info.fpsRanges.lastIndex)]
        val work = workingSize(size, air)
        session.edit {
            it.copy(
                air = air,
                alpha = alpha.toDouble(),
                lowHz = lowHz.toDouble(),
                highHz = highHz.toDouble().coerceAtLeast(lowHz.toDouble() + 0.2),
                pyramid = pyramid.roundToInt().coerceIn(1, 6),
                gain = gain.toDouble(),
                blur = blur.toDouble(),
                cameraId = info.id,
                size = size,
                fpsRange = fps,
                lock = lock,
                exposureNs = shutterNs,
                iso = iso.roundToInt(),
                workW = work.first,
                workH = work.second,
                revision = it.revision + 1,
                record = if (it.workW != work.first || it.workH != work.second) false else it.record,
                rotation = ((info.sensorOrientation % 360) + 360) % 360,
                mirror = !info.facingBack,
            )
        }
        if (reopenHint || !started) onApplyCamera()
    }

    LaunchedEffect(cameras) {
        if (!started && cameras.isNotEmpty()) {
            val info = infoAt(camIndex)
            sizeIndex = info.sizes.indexOf(pickSize(info.sizes)).coerceAtLeast(0)
            fpsIndex = info.fpsRanges.indexOf(pickFps(info.fpsRanges)).coerceAtLeast(0)
            iso = info.isoRange.lower.toFloat()
            push(reopenHint = true)
            started = true
        }
    }

    val info = infoAt(camIndex)
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds(),
        ) {
            val bitmap = ui.bitmap
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                if (air) {
                    Text(
                        if (ui.refReady) stringResource(R.string.p995, ui.p995) else stringResource(R.string.ref_progress, ui.refCount, 30),
                        color = Color.White,
                        fontSize = 22.sp,
                    )
                } else {
                    Text(
                        when {
                            ui.bpm < 0f -> stringResource(R.string.collecting)
                            ui.snr < MIN_PULSE_SNR -> stringResource(R.string.weak_signal)
                            else -> stringResource(R.string.bpm, ui.bpm)
                        },
                        color = Color.White,
                        fontSize = 28.sp,
                    )
                    val trace = ui.trace
                    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                        if (trace.size < 2) return@Canvas
                        var lo = trace[0]
                        var hi = trace[0]
                        for (value in trace) {
                            if (value < lo) lo = value
                            if (value > hi) hi = value
                        }
                        val span = (hi - lo).coerceAtLeast(1e-3f)
                        for (i in 1 until trace.size) {
                            val x0 = (i - 1) / (trace.size - 1f) * size.width
                            val x1 = i / (trace.size - 1f) * size.width
                            val y0 = size.height - (trace[i - 1] - lo) / span * size.height
                            val y1 = size.height - (trace[i] - lo) / span * size.height
                            drawLine(Color(0xFF3DDC84), Offset(x0, y0), Offset(x1, y1), strokeWidth = 2f)
                        }
                    }
                }
                Text(
                    "${stringResource(R.string.resolution)} ${ui.cameraW}×${ui.cameraH} → ${ui.workW}×${ui.workH}    " +
                        stringResource(R.string.overlay_exposure, ui.exposureNs / 1_000_000f, ui.iso),
                    color = Color(0xFFB0B0B0),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                Text(
                    stringResource(R.string.overlay_rate, ui.measuredFps, ui.processMs, ui.dropped),
                    color = Color(0xFFB0B0B0),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
                val hints = buildList {
                    if (ui.hintMotion) add(stringResource(R.string.hint_motion))
                    if (ui.hintLight) add(stringResource(R.string.hint_light))
                    if (ui.hintBlur) add(stringResource(R.string.hint_blur))
                    if (ui.hintPattern) add(stringResource(R.string.hint_pattern))
                    if (ui.hintHot) add(stringResource(R.string.hint_hot))
                    if (ui.hintPhone) add(stringResource(R.string.hint_phone))
                    if (ui.hintFps) add(stringResource(R.string.hint_fps))
                    if (air && !ui.refReady) add(stringResource(R.string.hint_ref))
                    if (!info.manualSensor && shutterNs != null && lock) add(stringResource(R.string.hint_manual))
                    if (shutterNs != 10_000_000L && shutterNs != 20_000_000L) add(stringResource(R.string.hint_flicker))
                }
                Column(Modifier.fillMaxWidth().height(54.dp).clipToBounds()) {
                    if (hints.isNotEmpty()) {
                        Text(
                            hints.joinToString(" · "),
                            color = Color(0xFFFFC46B),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    ui.message?.let { Text(it, color = Color(0xFF9EE6B8), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    ui.error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Column(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeButton(stringResource(R.string.mode_pulse), !air, Color(0xFFE53935), !ui.busy, Modifier.weight(1f)) {
                            if (air) {
                                air = false
                                push(reopenHint = false)
                            }
                        }
                        ModeButton(stringResource(R.string.mode_air), air, Color(0xFF4FC3F7), !ui.busy, Modifier.weight(1f)) {
                            if (!air) {
                                air = true
                                push(reopenHint = false)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val backIndex = cameras.indexOfFirst { it.facingBack }
                        val frontIndex = cameras.indexOfFirst { !it.facingBack }
                        Button(
                            modifier = Modifier.weight(1f).height(48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            enabled = !ui.busy && backIndex >= 0 && frontIndex >= 0,
                            onClick = {
                                camIndex = if (info.facingBack) frontIndex else backIndex
                                val next = infoAt(camIndex)
                                sizeIndex = next.sizes.indexOf(pickSize(next.sizes)).coerceAtLeast(0)
                                fpsIndex = next.fpsRanges.indexOf(pickFps(next.fpsRanges)).coerceAtLeast(0)
                                push(reopenHint = true)
                            },
                        ) {
                            Text(
                                stringResource(if (info.facingBack) R.string.camera_back else R.string.camera_front),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Button(
                            modifier = Modifier.weight(1f).height(48.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            enabled = !ui.busy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (ui.recording) Color(0xFF2A2A2A) else MaterialTheme.colorScheme.primary,
                                contentColor = if (ui.recording) Color.White else MaterialTheme.colorScheme.onPrimary,
                            ),
                            onClick = {
                                val next = !ui.recording
                                session.edit { it.copy(record = next) }
                                ui.recording = next
                            },
                        ) {
                            if (ui.recording) {
                                Box(Modifier.size(12.dp).background(Color(0xFFE53935), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(String.format(Locale.US, "%d:%02d", ui.recordSec / 60, ui.recordSec % 60), maxLines = 1)
                            } else {
                                Text(stringResource(R.string.record), maxLines = 1)
                            }
                        }
                        Button(
                            modifier = Modifier.weight(1f).height(48.dp),
                            contentPadding = PaddingValues(0.dp),
                            enabled = !ui.busy,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (lock) Color(0xFFFFB300) else Color(0xFF2A2A2A),
                                contentColor = if (lock) Color.Black else Color.White,
                            ),
                            onClick = {
                                lock = !lock
                                push(reopenHint = true)
                            },
                        ) {
                            LockIcon(locked = lock, color = if (lock) Color.Black else Color.White)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = !ui.busy && !ui.recording,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A), contentColor = Color.White),
                            onClick = {
                                files = recordings()
                                showFiles = true
                            },
                        ) { Text(stringResource(R.string.open_recording)) }
                        Button(
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A), contentColor = Color.White),
                            onClick = { showSettings = true },
                        ) { Text(stringResource(R.string.settings)) }
                        if (air) {
                            Button(modifier = Modifier.weight(1f), enabled = !ui.busy, onClick = onClearReference) {
                                Text(stringResource(if (ui.refReady) R.string.reference_again else R.string.reference))
                            }
                        }
                    }
                }
            }
            if (showSettings) {
                Column(
                    Modifier
                        .matchParentSize()
                        .background(Color.Black)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.settings),
                            color = Color.White,
                            fontSize = 18.sp,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showSettings = false }) { Text(stringResource(R.string.close)) }
                    }
                    Text(stringResource(R.string.compression_note), color = Color(0xFFB0B0B0), fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val size = info.sizes[sizeIndex.coerceIn(0, info.sizes.lastIndex)]
                        TextButton(onClick = {
                            sizeIndex = (sizeIndex + 1) % info.sizes.size
                            push(reopenHint = true)
                        }) { Text("${stringResource(R.string.resolution)} ${size.width}×${size.height}") }
                        val fps = info.fpsRanges[fpsIndex.coerceIn(0, info.fpsRanges.lastIndex)]
                        TextButton(onClick = {
                            fpsIndex = (fpsIndex + 1) % info.fpsRanges.size
                            push(reopenHint = true)
                        }) { Text("${stringResource(R.string.fps)} ${fps.lower}–${fps.upper}") }
                    }
                    Text(stringResource(R.string.exposure), color = Color.White, fontSize = 13.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        val choices = listOf<Long?>(null, 10_000_000L, 20_000_000L, 30_000_000L, 40_000_000L)
                        choices.forEach { choice ->
                            val allowed = choice == null ||
                                (choice >= info.exposureRangeNs.lower && choice <= info.exposureRangeNs.upper)
                            if (!allowed) return@forEach
                            TextButton(onClick = {
                                shutterNs = choice
                                lock = choice != null
                                push(reopenHint = true)
                            }) {
                                Text(
                                    if (choice == null) stringResource(R.string.auto) else "${choice / 1_000_000} ms",
                                    color = if (shutterNs == choice) Color(0xFF3DDC84) else Color.White,
                                )
                            }
                        }
                    }
                    if (lock && shutterNs != null && info.manualSensor) {
                        LabeledSlider(stringResource(R.string.iso), iso, info.isoRange.lower.toFloat()..info.isoRange.upper.toFloat()) {
                            iso = it
                            push(reopenHint = true)
                        }
                    }
                    if (!air) {
                        LabeledSlider(stringResource(R.string.gain), alpha, 5f..150f) { alpha = it; push() }
                        LabeledSlider(stringResource(R.string.low_hz), lowHz, 0.4f..1.5f) { lowHz = it; push() }
                        LabeledSlider(stringResource(R.string.high_hz), highHz, 1.2f..4f) { highHz = it; push() }
                        LabeledSlider(stringResource(R.string.pyramid), pyramid, 1f..6f) { pyramid = it; push() }
                    } else {
                        LabeledSlider(stringResource(R.string.air_gain), gain, 0.5f..20f) { gain = it; push() }
                        LabeledSlider(stringResource(R.string.blur), blur, 0f..3f) { blur = it; push() }
                    }
                }
            }
        }
    }
    if (showFiles) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showFiles = false },
            title = { Text(stringResource(R.string.open_recording)) },
            text = {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    if (files.isEmpty()) Text("—", color = Color.White)
                    files.forEach { file ->
                        TextButton(onClick = {
                            showFiles = false
                            session.ui.message = replayText
                            onReplay(file)
                        }) { Text(file.name) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFiles = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    activeColor: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) activeColor else Color(0xFF2A2A2A),
            contentColor = if (selected) Color.Black else Color.White,
        ),
    ) { Text(label) }
}

@Composable
private fun LockIcon(locked: Boolean, color: Color) {
    Canvas(Modifier.size(width = 18.dp, height = 22.dp)) {
        val stroke = size.width * 0.16f
        val bodyTop = size.height * 0.45f
        val shackleW = size.width * 0.62f
        val shackleLeft = (size.width - shackleW) / 2f
        val lift = if (locked) 0f else size.height * 0.18f
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(shackleLeft, stroke / 2f - lift),
            size = DrawSize(shackleW, shackleW),
            style = Stroke(stroke),
        )
        val legTop = stroke / 2f + shackleW / 2f - lift
        drawLine(color, Offset(shackleLeft, legTop), Offset(shackleLeft, bodyTop), strokeWidth = stroke)
        if (locked) {
            drawLine(color, Offset(shackleLeft + shackleW, legTop), Offset(shackleLeft + shackleW, bodyTop), strokeWidth = stroke)
        }
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, bodyTop),
            size = DrawSize(size.width, size.height - bodyTop),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(stroke, stroke),
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    val start = range.start
    val end = range.endInclusive
    if (end <= start) return
    val shown = value.coerceIn(start, end)
    Text("$label  ${String.format(Locale.US, "%.2f", shown)}", color = Color.White, fontSize = 13.sp)
    Slider(value = shown, onValueChange = onChange, valueRange = start..end)
}

private fun workingSize(size: Size, air: Boolean): Pair<Int, Int> {
    val maxW = 640
    val scale = min(1f, maxW.toFloat() / size.width.coerceAtLeast(1))
    var w = (size.width * scale).roundToInt().coerceIn(32, maxW)
    var h = (size.height * scale).roundToInt().coerceAtLeast(32)
    if (w % 2 != 0) w -= 1
    if (h % 2 != 0) h -= 1
    return w to h
}

private fun pickSize(sizes: List<Size>): Size {
    return sizes.minByOrNull { abs(it.width - 1280) + abs(it.height - 720) } ?: sizes.first()
}

private fun pickFps(ranges: List<Range<Int>>): Range<Int> {
    ranges.firstOrNull { it.lower == 30 && it.upper == 30 }?.let { return it }
    val covers = ranges.filter { it.lower <= 30 && it.upper >= 30 }
    if (covers.isNotEmpty()) return covers.maxBy { it.lower }
    return ranges.maxBy { it.upper * 1000 + it.lower }
}

private fun roiRect(w: Int, h: Int, cx: Float, cy: Float, scale: Float): IntArray {
    val rw = (w * scale).roundToInt().coerceIn(8, w)
    val rh = (h * scale).roundToInt().coerceIn(8, h)
    val x = (cx * w - rw / 2f).roundToInt().coerceIn(0, w - rw)
    val y = (cy * h - rh / 2f).roundToInt().coerceIn(0, h - rh)
    return intArrayOf(x, y, rw, rh)
}

private fun sensorCenter(cx: Float, cy: Float, rotation: Int, mirror: Boolean): Pair<Float, Float> {
    val x = if (mirror) 1f - cx else cx
    return when (rotation) {
        90 -> cy to 1f - x
        180 -> 1f - x to 1f - cy
        270 -> 1f - cy to x
        else -> x to cy
    }
}

private const val MIN_PULSE_SNR = 2f

private fun orientPixels(
    src: IntArray, w: Int, h: Int, outW: Int, outH: Int, rotation: Int, mirror: Boolean, dst: IntArray,
) {
    for (y in 0 until outH) {
        val row = y * outW
        for (x in 0 until outW) {
            val dx = if (mirror) outW - 1 - x else x
            val index = when (rotation) {
                90 -> (h - 1 - dx) * w + y
                180 -> (h - 1 - y) * w + (w - 1 - dx)
                270 -> dx * w + (w - 1 - y)
                else -> y * w + dx
            }
            dst[row + x] = src[index]
        }
    }
}
