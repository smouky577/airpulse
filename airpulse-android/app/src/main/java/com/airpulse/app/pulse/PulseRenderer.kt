package com.airpulse.app.pulse

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Streaming GPU pipeline, one pass chain per camera frame, all on a dedicated GL thread:
 *
 *  camera OES ─► level1 (G, blur+½) ─► … ─► level L (R32F)
 *                                              │
 *                       IIR band-pass state (RG32F ping-pong: lp(fh), lp(fl))
 *                                              │
 *                 lock-in I/Q (RG32F ping-pong) ┤   ROI mean ─► PBO ─► CPU ring buffer ─► FFT ─► f0
 *                                              │
 *   full-res composite (gray base + bilinear-upsampled overlay) ─► screen  (+ MediaCodec surface)
 *
 * Only O(level-L) state is kept between frames; no frames are stored.
 */
class PulseRenderer(private val settings: PulseSettings, private val listener: Listener) {

    interface Listener {
        fun onStats(fps: Float, bpm: Float, snr: Float, locked: Boolean)
        fun onError(message: String)
    }

    private val thread = HandlerThread("PulseGL").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())


    // --- EGL / GL objects -------------------------------------------------------------------
    private var egl: EglCore? = null
    private var offscreen: EGLSurface = EGL14.EGL_NO_SURFACE
    private var display: EGLSurface = EGL14.EGL_NO_SURFACE
    private var displayW = 0
    private var displayH = 0
    private var glReady = false

    private lateinit var progDownOes: Program
    private lateinit var progDown: Program
    private lateinit var progIir: Program
    private lateinit var progLock: Program
    private lateinit var progComposite: Program
    private var vao = 0
    private val pbo = IntArray(2)
    private var pboBytes = 0
    private var skinEma = FloatArray(0)
    private var skinEmaInit = false
    private val histR = FloatArray(HIST)
    private val histG = FloatArray(HIST)
    private val histB = FloatArray(HIST)
    private var histN = 0
    private var farSince = -1.0
    private var skinVarPeak = FloatArray(0)
    private var skinVar = FloatArray(0)
    private var cellRgb = FloatArray(0)
    private var cellWt = FloatArray(0)
    private var varScratch = FloatArray(0)
    private var lastDt = 1.0 / 30.0

    // --- camera -----------------------------------------------------------------------------
    private var camTex = 0
    private var camSt: SurfaceTexture? = null
    private var bufW = 0
    private var bufH = 0
    private var rotation = 0
    @Volatile private var mirror = false
    private val stMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val tmpM = FloatArray(16)
    private val tmpV = FloatArray(4)
    private val tmpOut = FloatArray(4)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    // --- pipeline state ---------------------------------------------------------------------
    private var frameW = 0
    private var frameH = 0
    private var allocLevel = -1
    private val levels = ArrayList<RenderTarget>()
    private val lpHigh = arrayOfNulls<RenderTarget>(2) // YIQ low-pass at F_HIGH (ping-pong)
    private val lpLow = arrayOfNulls<RenderTarget>(2)  // YIQ low-pass at F_LOW (ping-pong)
    private val lock = arrayOfNulls<RenderTarget>(2)
    private var iirCur = 0
    private var lockCur = 0
    private var iirInit = true
    private var lockReset = true
    private var lockValid = false

    private var lastTs = 0L
    private var fpsEma = 0.0
    private var lastStatsTime = 0.0
    private var lastEstimateTime = 0.0

    // ROI / heart rate
    private val estimator = PulseEstimator(10.0)
    private val pos = PosFilter()

    // Coherence check: the left and right halves of the skin area get their own POS + estimator.
    // A real pulse is the same everywhere on the skin; camera noise on a beige desk or keyboard
    // is not - so a number is shown only when both halves agree with the full-area estimate.
    private val posH = arrayOf(PosFilter(), PosFilter())
    private val estH = arrayOf(PulseEstimator(10.0), PulseEstimator(10.0))
    private val hR = DoubleArray(2)
    private val hG = DoubleArray(2)
    private val hB = DoubleArray(2)
    private val hW = DoubleArray(2)
    private var halvesOk = false
    private var incoherentSince = -1.0
    @Volatile private var noPulse = false
    private var motionStreakStart = -1.0
    private var noSkin = false
    private val recentHz = ArrayDeque<Pair<Double, Double>>() // (time, hz) of accepted estimates
    private var prevR = 0.0
    private var prevG = 0.0
    private var prevB = 0.0
    private var havePrev = false
    private var motionUntil = 0.0
    private var posReady = false
    private var pboIdx = 0
    private var pboPrimed = false
    private var pendingRoiTime = 0.0
    private var f0 = 0.0          // accepted lock-in reference frequency, Hz (0 = unknown)
    private var lastSnr = 0.0
    private var refPhase = 0.0

    // recording
    private var encoder: VideoEncoder? = null
    private var encSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encFirstTs = -1L
    private var encDone: ((Boolean) -> Unit)? = null

    private var errorReported = false

    init {
        handler.post {
            try {
                initGl()
            } catch (e: Throwable) {
                reportError("GL init failed: ${e.message}")
            }
        }
    }

    // =========================================================================================
    // Public API (any thread)
    // =========================================================================================

    fun setMirror(front: Boolean) {
        mirror = front
    }

    fun setDisplaySurface(surface: Surface, width: Int, height: Int) = runSync {
        val e = egl ?: return@runSync
        if (display != EGL14.EGL_NO_SURFACE) {
            e.makeCurrent(offscreen)
            e.releaseSurface(display)
            display = EGL14.EGL_NO_SURFACE
        }
        display = e.createWindowSurface(surface)
        displayW = width
        displayH = height
    }

    fun resizeDisplay(width: Int, height: Int) = handler.post {
        displayW = width
        displayH = height
    }

    fun clearDisplaySurface() = runSync {
        val e = egl ?: return@runSync
        if (display != EGL14.EGL_NO_SURFACE) {
            e.makeCurrent(offscreen)
            e.releaseSurface(display)
            display = EGL14.EGL_NO_SURFACE
        }
    }

    /** Resets temporal filters and the heart-rate estimator (e.g. after switching camera). */
    fun resetFilters() = handler.post { resetState() }

    /** Starts writing the composited output to [pfd]. [onStarted] runs on the main thread. */
    fun startRecording(pfd: ParcelFileDescriptor, onStarted: (Boolean) -> Unit, onFinished: (Boolean) -> Unit) {
        handler.post {
            val e = egl
            if (e == null || !glReady || frameW == 0 || encoder != null) {
                runCatching { pfd.close() }
                main.post { onStarted(false) }
                return@post
            }
            try {
                val scale = min(1f, 1920f / max(frameW, frameH))
                val w = ((frameW * scale).roundToInt()) and 1.inv()
                val h = ((frameH * scale).roundToInt()) and 1.inv()
                val bitrate = (w.toLong() * h * 30 * 0.15).toInt().coerceIn(2_000_000, 20_000_000)
                val enc = VideoEncoder(w, h, 30, bitrate, pfd)
                encSurface = e.createWindowSurface(enc.inputSurface)
                encoder = enc
                encFirstTs = -1L
                encDone = onFinished
                main.post { onStarted(true) }
            } catch (t: Throwable) {
                Log.e(TAG, "startRecording", t)
                runCatching { pfd.close() }
                main.post { onStarted(false) }
            }
        }
    }

    fun stopRecording() = handler.post { finishRecording() }

    /** Debug CSV (one line per frame, a few bytes); set before the camera starts. Null = off. */
    @Volatile var logFile: java.io.File? = null
    private var logWriter: java.io.BufferedWriter? = null

    private fun log(line: String) {
        val f = logFile ?: return
        try {
            val w = logWriter ?: java.io.BufferedWriter(java.io.FileWriter(f)).also {
                logWriter = it
                it.write("# S,t,r,g,b,skin,pos | E,t,hz,snr,f0")
                it.newLine()
            }
            w.write(line)
            w.newLine()
            if (line[0] == 'E') w.flush()
        } catch (t: Throwable) {
            logFile = null
        }
    }

    fun release() {
        handler.post {
            runCatching { logWriter?.close() }
            logWriter = null
            finishRecording()
            releaseGl()
        }
        thread.quitSafely()
    }

    // =========================================================================================
    // GL thread
    // =========================================================================================

    private fun initGl() {
        val e = EglCore()
        egl = e
        offscreen = e.createOffscreenSurface()
        e.makeCurrent(offscreen)

        if (!Gl.hasExtension("GL_EXT_color_buffer_float")) {
            reportError("This GPU lacks GL_EXT_color_buffer_float (needed for float render targets).")
            return
        }
        if (!Gl.hasExtension("GL_OES_EGL_image_external_essl3")) {
            reportError("This GPU lacks GL_OES_EGL_image_external_essl3.")
            return
        }

        progDownOes = Program(Shaders.VERTEX, Shaders.DOWN_OES)
        progDown = Program(Shaders.VERTEX, Shaders.DOWN)
        progIir = Program(Shaders.VERTEX, Shaders.IIR)
        progLock = Program(Shaders.VERTEX, Shaders.LOCKIN)
        progComposite = Program(Shaders.VERTEX, Shaders.COMPOSITE)

        val v = IntArray(1)
        GLES30.glGenVertexArrays(1, v, 0)
        vao = v[0]

        GLES30.glGenBuffers(2, pbo, 0)

        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        glReady = true
    }

    private fun releaseGl() {
        val e = egl ?: return
        runCatching { e.makeCurrent(offscreen) }
        if (glReady) {
            releasePyramid()
            GLES30.glDeleteBuffers(2, pbo, 0)
            GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
            listOf(progDownOes, progDown, progIir, progLock, progComposite).forEach { it.release() }
        }
        releaseCameraInput()
        if (display != EGL14.EGL_NO_SURFACE) e.releaseSurface(display)
        e.releaseSurface(offscreen)
        e.release()
        egl = null
        glReady = false
    }

    private var camSurface: Surface? = null

    /**
     * Creates the camera input surface (Camera2 output target) of [width]x[height] buffers.
     * [rotationDeg] = sensor orientation, [front] = selfie camera (mirrored). Any previous
     * camera surface is released, so close the old capture session first.
     */
    fun createCameraSurface(width: Int, height: Int, rotationDeg: Int, front: Boolean): Surface? {
        var out: Surface? = null
        runSync {
            val e = egl
            if (e == null || !glReady) return@runSync
            e.makeCurrent(drawSurface())
            releaseCameraInput()
            val tex = Gl.createOesTexture()
            val st = SurfaceTexture(tex)
            st.setDefaultBufferSize(width, height)
            st.setOnFrameAvailableListener({ onFrame(it) }, handler)
            val surface = Surface(st)
            camSt = st
            camTex = tex
            camSurface = surface
            bufW = width
            bufH = height
            rotation = rotationDeg
            mirror = front
            updateGeometry()
            resetState()
            out = surface
        }
        return out
    }

    /** Call after the camera session using the surface is closed. */
    fun releaseCameraSurface() = runSync { releaseCameraInput() }

    private fun releaseCameraInput() {
        egl?.let { runCatching { it.makeCurrent(drawSurface()) } }
        camSurface?.release()
        camSurface = null
        camSt?.release()
        camSt = null
        if (camTex != 0) Gl.deleteTexture(camTex)
        camTex = 0
    }

    /** Copies the newest POS samples (oldest first) for the green trace; returns the count. */
    fun copyTrace(dest: FloatArray): Int = synchronized(trace) {
        val n = min(traceCount, min(dest.size, trace.size))
        for (i in 0 until n) dest[i] = trace[(traceHead - n + i + trace.size) % trace.size]
        n
    }

    private val trace = FloatArray(TRACE_LEN)
    private var traceHead = 0
    private var traceCount = 0

    private fun pushTrace(v: Float) = synchronized(trace) {
        trace[traceHead] = v
        traceHead = (traceHead + 1) % trace.size
        if (traceCount < trace.size) traceCount++
    }

    private fun drawSurface(): EGLSurface = if (display != EGL14.EGL_NO_SURFACE) display else offscreen

    private fun updateGeometry() {
        val rot = ((rotation % 360) + 360) % 360
        val (w, h) = if (rot == 90 || rot == 270) bufH to bufW else bufW to bufH
        if (w != frameW || h != frameH) {
            frameW = w
            frameH = h
            allocLevel = -1
        }
    }

    private fun resetState() {
        iirInit = true
        lockReset = true
        lockValid = false
        lastTs = 0L
        estimator.reset()
        posReady = false
        recentHz.clear()
        skinEmaInit = false
        havePrev = false
        histN = 0
        farSince = -1.0
        motionUntil = 0.0
        pboPrimed = false
        f0 = 0.0
        lastSnr = 0.0
        refPhase = 0.0
        synchronized(trace) { traceCount = 0 }
        fingerOn = false
        noFinger = false
        fingerVotes = -10
        estH[0].reset(); estH[1].reset()
        incoherentSince = -1.0
        noPulse = false
        motionStreakStart = -1.0
        noSkin = false
    }

    private fun releasePyramid() {
        levels.forEach { it.release() }
        levels.clear()
        for (i in 0..1) {
            lpHigh[i]?.release(); lpHigh[i] = null
            lpLow[i]?.release(); lpLow[i] = null
            lock[i]?.release(); lock[i] = null
        }
        allocLevel = -1
    }

    /**
     * [level] is AirPulse-relative (640 px working width). A 1280x720 camera gets one extra
     * halving so the pyramid top has the same cell size AirPulse uses.
     */
    private fun allocatePyramid(level: Int) {
        releasePyramid()
        val extra = max(0, (ln(max(frameW, frameH) / PulseSettings.WORK_WIDTH) / ln(2f)).roundToInt())
        var w = frameW
        var h = frameH
        for (k in 1..(level + extra)) {
            w = max(1, (w + 1) / 2)
            h = max(1, (h + 1) / 2)
            levels += RenderTarget.rgba32f(w, h)
        }
        for (i in 0..1) {
            lpHigh[i] = RenderTarget.rgba32f(w, h)
            lpLow[i] = RenderTarget.rgba32f(w, h)
            lock[i] = RenderTarget.rg32f(w, h)
        }
        Log.w(TAG, "frame ${frameW}x$frameH, level $level (+$extra) -> top ${w}x$h")
        allocLevel = level
        iirInit = true
        lockReset = true
        lockValid = false
    }

    private fun onFrame(st: SurfaceTexture) {
        if (st !== camSt) return
        val e = egl ?: return
        try {
            e.makeCurrent(drawSurface())
            st.updateTexImage()
            if (!glReady || frameW == 0) return
            st.getTransformMatrix(stMatrix)
            processFrame(st.timestamp)
        } catch (t: Throwable) {
            Log.e(TAG, "frame", t)
            reportError("Render error: ${t.message}")
        }
    }

    private fun processFrame(ts: Long) {
        val e = egl ?: return
        val dt = if (lastTs == 0L || ts <= lastTs) 1.0 / 30.0 else ((ts - lastTs) / 1e9).coerceIn(1.0 / 240.0, 0.25)
        lastTs = ts
        lastDt = dt
        val time = ts / 1e9
        fpsEma = if (fpsEma == 0.0) 1.0 / dt else fpsEma + 0.05 * (1.0 / dt - fpsEma)

        val level = settings.level.coerceIn(3, 6)
        if (level != allocLevel) allocatePyramid(level)
        val lockIn = settings.lockIn
        if (!lockIn) {
            lockReset = true
            lockValid = false
        }

        buildTexMatrix()
        GLES30.glBindVertexArray(vao)

        // 1) camera -> level 1 (G channel, blur + decimate)
        val l1 = levels[0]
        l1.bind()
        progDownOes.use()
        progDownOes.mat4("uTexMatrix", texMatrix)
        progDownOes.tex("uTex", 0, camTex, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        dirToBuffer(1f / frameW, 0f).let { progDownOes.f2("uStepX", it[0], it[1]) }
        dirToBuffer(0f, 1f / frameH).let { progDownOes.f2("uStepY", it[0], it[1]) }
        draw()

        // 2) remaining pyramid levels
        progDown.use()
        progDown.mat4("uTexMatrix", identity)
        for (k in 1 until levels.size) {
            val src = levels[k - 1]
            levels[k].bind()
            progDown.tex("uSrc", 0, src.tex)
            progDown.i2("uSrcSize", src.width, src.height)
            draw()
        }
        val top = levels.last()

        // 3) temporal IIR band-pass on Y, I, Q (difference of two first-order low-passes)
        val aH = (1.0 - exp(-2.0 * PI * settings.highHz * dt)).toFloat()
        val aL = (1.0 - exp(-2.0 * PI * settings.lowHz * dt)).toFloat()
        progIir.use()
        progIir.mat4("uTexMatrix", identity)
        progIir.i("uInit", if (iirInit) 1 else 0)
        for ((states, a) in listOf(lpHigh to aH, lpLow to aL)) {
            states[1 - iirCur]!!.bind()
            progIir.tex("uLevel", 0, top.tex)
            progIir.tex("uPrev", 1, states[iirCur]!!.tex)
            progIir.f("uA", a)
            draw()
        }
        iirInit = false
        iirCur = 1 - iirCur

        // 4) ROI mean -> async PBO readback -> ring buffer -> FFT
        roiPass(top, time)
        if (time - lastEstimateTime > 0.5) {
            lastEstimateTime = time
            // Finger: clean signal, allow 42-180 BPM; face: the user's band (default 48-120).
            val r = if (settings.finger) estimator.estimate(0.7, 3.0) else estimator.estimate(settings.lowHz, settings.highHz)
            lastSnr = r?.snr ?: 0.0
            var hz = r?.hz ?: 0.0
            // The very first number needs a clear peak; once locked, tracking keeps it honest.
            var accept = r != null && r.snr >= (if (f0 > 0.0) MIN_SNR else FIRST_SNR)
            // Coherence (face only): left and right skin halves, estimated separately.
            val checkHalves = !settings.finger && halvesOk
            val ha = if (checkHalves) estH[0].estimate(settings.lowHz, settings.highHz) else null
            val hb = if (checkHalves) estH[1].estimate(settings.lowHz, settings.highHz) else null
            fun coherent(h: Double): Boolean {
                if (!checkHalves) return true
                // Each half's own strongest peak must be the same rate (not just some peak).
                val ta = ha?.peaks?.firstOrNull() ?: return false
                val tb = hb?.peaks?.firstOrNull() ?: return false
                return abs(ta.first - h) < COHERE_HZ && abs(tb.first - h) < COHERE_HZ
            }
            // Tracking: the heart rate does not jump by 12+ BPM from one 0.5 s step to the next.
            // A sudden far peak is usually a blink/twitch transient - if the spectrum still has a
            // reasonable peak near the current rate, stay on it. A real change is accepted after
            // FAST_CONFIRM s when both halves agree on it, else after JUMP_CONFIRM s.
            if (accept && f0 > 0.0 && abs(hz - f0) > JUMP_HZ) {
                val pMax = r!!.peaks.firstOrNull()?.second ?: 0.0
                val near = r.peaks.firstOrNull { abs(it.first - f0) < NEAR_HZ && it.second >= 0.3 * pMax }
                if (farSince < 0.0) farSince = time
                val waited = time - farSince
                if (waited >= JUMP_CONFIRM || (checkHalves && coherent(hz) && waited >= FAST_CONFIRM)) {
                    farSince = -1.0
                    recentHz.clear()
                } else if (near != null && coherent(near.first)) {
                    hz = near.first
                } else {
                    accept = false
                }
            } else if (accept) {
                farSince = -1.0
            }
            if (accept && !coherent(hz)) accept = false
            if (checkHalves) {
                log(String.format(Locale.US, "H,%.4f,%.4f,%.4f,%d", time, ha?.hz ?: 0.0, hb?.hz ?: 0.0, if (accept) 1 else 0))
            }
            if (accept) {
                recentHz.addLast(time to hz)
                if (recentHz.size > 5) recentHz.removeFirst()
                f0 = medianHz()
                incoherentSince = -1.0
                noPulse = false
            } else if (!settings.finger && time < motionUntil && motionStreakStart >= 0.0 &&
                time - motionStreakStart > MOTION_NO_PULSE_SEC
            ) {
                // "Motion" that never stops (flickering light, a moving scene): nothing to measure.
                f0 = 0.0
                recentHz.clear()
                noPulse = true
            } else if (!settings.finger && time >= motionUntil && r != null) {
                // Data is coming in but nothing pulse-like for NO_PULSE_SEC: drop the number.
                if (incoherentSince < 0.0) incoherentSince = time
                if (time - incoherentSince > NO_PULSE_SEC) {
                    f0 = 0.0
                    recentHz.clear()
                    noPulse = true
                }
            }
            log(String.format(Locale.US, "E,%.4f,%s,%s,%.4f", time, r?.hz?.let { String.format(Locale.US, "%.4f", it) } ?: "", r?.snr?.let { String.format(Locale.US, "%.3f", it) } ?: "", f0))
        }

        // 5) lock-in demodulation at f0
        if (lockIn && f0 > 0.0) {
            refPhase = (refPhase + 2.0 * PI * f0 * dt) % (2.0 * PI)
            val k = (1.0 - exp(-dt / PulseSettings.LOCKIN_TAU)).toFloat()
            val src = lock[lockCur]!!
            val dst = lock[1 - lockCur]!!
            dst.bind()
            progLock.use()
            progLock.mat4("uTexMatrix", identity)
            progLock.tex("uHigh", 0, lpHigh[iirCur]!!.tex)
            progLock.tex("uLow", 1, lpLow[iirCur]!!.tex)
            progLock.tex("uLock", 2, src.tex)
            progLock.f("uCos", cos(refPhase).toFloat())
            progLock.f("uSin", sin(refPhase).toFloat())
            progLock.f("uK", k)
            progLock.i("uReset", if (lockReset) 1 else 0)
            draw()
            lockReset = false
            lockValid = true
            lockCur = 1 - lockCur
        }

        // 6) composite to screen
        if (display != EGL14.EGL_NO_SURFACE && displayW > 0 && displayH > 0) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, displayW, displayH)
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            val fa = frameW.toFloat() / frameH
            val sa = displayW.toFloat() / displayH
            if (sa > fa) {
                val w = (displayH * fa).roundToInt()
                GLES30.glViewport((displayW - w) / 2, 0, w, displayH)
            } else {
                val h = (displayW / fa).roundToInt()
                GLES30.glViewport(0, (displayH - h) / 2, displayW, h)
            }
            drawComposite(lockIn)
            e.swap(display)
        }

        // 7) same composite into the encoder surface
        val enc = encoder
        if (enc != null) {
            try {
                e.makeCurrent(encSurface)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                GLES30.glViewport(0, 0, enc.width, enc.height)
                drawComposite(lockIn)
                if (encFirstTs < 0) encFirstTs = ts
                e.setPresentationTime(encSurface, ts - encFirstTs)
                e.swap(encSurface)
                enc.drain(false)
            } catch (t: Throwable) {
                Log.e(TAG, "encode", t)
                finishRecording()
            } finally {
                e.makeCurrent(drawSurface())
            }
        }

        if (time - lastStatsTime > 0.5) {
            lastStatsTime = time
            val fps = fpsEma.toFloat()
            val bpm = if (f0 > 0) (f0 * 60.0).toFloat() else Float.NaN
            val snr = when {
                settings.finger && noFinger -> -2f // no finger on the lens
                !settings.finger && noPulse -> -3f // skin-coloured, but no coherent pulse
                time < motionUntil -> -1f
                else -> lastSnr.toFloat()
            }
            val locked = lockIn && lockValid
            main.post { listener.onStats(fps, bpm, snr, locked) }
        }
    }

    /**
     * Reads the (tiny, e.g. 40x23) pyramid top back through a PBO, one frame late so the
     * GPU never stalls, and averages skin pixels on the CPU. Skin = AirPulse's YCrCb test,
     * restricted to a central ellipse so warm-lit walls don't count. Falls back to the
     * whole ellipse when there is too little skin.
     */
    private fun roiPass(top: RenderTarget, time: Double) {
        val bytes = top.width * top.height * 16
        if (bytes != pboBytes) {
            pboBytes = bytes
            for (b in pbo) {
                GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, b)
                GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, bytes, null, GLES30.GL_STREAM_READ)
            }
            pboPrimed = false
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, top.fbo)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[pboIdx])
        GLES30.glReadPixels(0, 0, top.width, top.height, GLES30.GL_RGBA, GLES30.GL_FLOAT, 0)
        if (pboPrimed) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo[1 - pboIdx])
            val buf = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, bytes, GLES30.GL_MAP_READ_BIT) as? ByteBuffer
            if (buf != null) {
                val fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
                val w = top.width
                val h = top.height
                // The skin weights come from a slow (~2 s) average of the image, not from the
                // current frame: a hard per-frame mask flickers at the threshold edges and makes
                // the mean jump by several levels, far more than the pulse (~0.5 level).
                if (skinEma.size != w * h * 3) {
                    skinEma = FloatArray(w * h * 3)
                    skinEmaInit = false
                }
                if (skinVar.size != w * h) {
                    skinVar = FloatArray(w * h)
                    skinVarPeak = FloatArray(w * h)
                    cellRgb = FloatArray(w * h * 3)
                    cellWt = FloatArray(w * h)
                    varScratch = FloatArray(w * h)
                }
                val kEma = if (skinEmaInit) (1.0 - exp(-lastDt / 2.0)).toFloat() else 1f
                val first = !skinEmaInit
                val peakDecay = exp(-lastDt / 10.0).toFloat()
                skinEmaInit = true
                var sr = 0.0; var sg = 0.0; var sb = 0.0; var sw = 0.0
                var cr0 = 0.0; var cg0 = 0.0; var cb0 = 0.0; var nc = 0
                var sy = 0.0; var syy = 0.0
                var nv = 0
                for (y in 0 until h) {
                    val dy = ((y + 0.5) / h - 0.5) / 0.42
                    for (x in 0 until w) {
                        val c = y * w + x
                        cellWt[c] = 0f
                        val dx = ((x + 0.5) / w - 0.5) / 0.38
                        if (dx * dx + dy * dy > 1.0) continue
                        val o = c * 4
                        val yy = fb.get(o); val ii = fb.get(o + 1); val qq = fb.get(o + 2)
                        val r = (yy + 0.956f * ii + 0.621f * qq).coerceIn(0f, 1f) * 255f
                        val g = (yy - 0.272f * ii - 0.647f * qq).coerceIn(0f, 1f) * 255f
                        val b = (yy - 1.106f * ii + 1.703f * qq).coerceIn(0f, 1f) * 255f
                        cr0 += r; cg0 += g; cb0 += b; nc++
                        val lum = 0.299 * r + 0.587 * g + 0.114 * b
                        sy += lum; syy += lum * lum
                        val e = c * 3
                        cellRgb[e] = r; cellRgb[e + 1] = g; cellRgb[e + 2] = b
                        skinEma[e] += kEma * (r - skinEma[e])
                        skinEma[e + 1] += kEma * (g - skinEma[e + 1])
                        skinEma[e + 2] += kEma * (b - skinEma[e + 2])
                        // Temporal flicker of this cell (deviation from its own 2 s mean).
                        val d0 = r - skinEma[e]; val d1 = g - skinEma[e + 1]; val d2 = b - skinEma[e + 2]
                        val dv = d0 * d0 + d1 * d1 + d2 * d2
                        skinVar[c] = if (first) 0f else skinVar[c] + kEma * (dv - skinVar[c])
                        // Peak-hold (~10 s decay): eyes blink / mouth twitches only now and then,
                        // but each event is a big transient - keep such cells out between events.
                        skinVarPeak[c] = if (first) 0f else maxOf(skinVar[c], skinVarPeak[c] * peakDecay)
                        val wt = skinWeight(skinEma[e], skinEma[e + 1], skinEma[e + 2])
                        cellWt[c] = wt
                        if (wt > 0.3f) varScratch[nv++] = skinVarPeak[c]
                    }
                }
                GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
                // Things that flicker far more than skin (a pulse oximeter's LED shining through
                // a finger, its blinking display, a phone screen) are skin-coloured but would own
                // the spectrum: drop cells whose flicker is > 2-3x (std) the typical skin cell.
                var medVar = 0f
                if (nv > 0) {
                    java.util.Arrays.sort(varScratch, 0, nv)
                    medVar = varScratch[nv / 2]
                }
                for (q in 0..1) { hR[q] = 0.0; hG[q] = 0.0; hB[q] = 0.0; hW[q] = 0.0 }
                for (c in 0 until w * h) {
                    var wt = cellWt[c]
                    if (wt <= 0f) continue
                    if (medVar > 0f) wt *= ((9f * medVar - skinVarPeak[c]) / (5f * medVar)).coerceIn(0f, 1f)
                    if (wt <= 0f) continue
                    val e = c * 3
                    sr += wt * cellRgb[e]; sg += wt * cellRgb[e + 1]; sb += wt * cellRgb[e + 2]; sw += wt
                    val q = if (c % w < w / 2) 0 else 1
                    val wd = wt.toDouble()
                    hR[q] += wd * cellRgb[e]; hG[q] += wd * cellRgb[e + 1]; hB[q] += wd * cellRgb[e + 2]; hW[q] += wd
                }
                // Both halves need a fair share of the skin to be compared at all.
                halvesOk = sw > 0.0 && hW[0] >= 0.15 * sw && hW[1] >= 0.15 * sw
                if (settings.finger) {
                    if (nc > 0) {
                        val my = sy / nc
                        val cv = kotlin.math.sqrt(max(0.0, syy / nc - my * my)) / max(my, 1.0)
                        fingerSample(cr0 / nc, cg0 / nc, cb0 / nc, cv)
                    }
                } else {
                val skin = if (nc > 0 && sw * 10 >= nc) 1f else 0f
                if (skin == 0f && nc > 0) {
                    // No skin in view (desk, wall, dark): nothing to measure - say so at once.
                    if (f0 > 0.0 || !noPulse) {
                        f0 = 0.0
                        recentHz.clear()
                        estimator.reset()
                        estH[0].reset(); estH[1].reset()
                        posReady = false
                    }
                    noPulse = true
                    noSkin = true
                    havePrev = false
                } else if (skin > 0f && noSkin) {
                    // Skin is back: "collecting" again, not "no pulse".
                    noSkin = false
                    noPulse = false
                    incoherentSince = -1.0
                }
                val r = sr / sw
                val g = sg / sw
                val b = sb / sw
                if (skin > 0f && !r.isNaN() && !g.isNaN() && !b.isNaN()) {
                    // Motion gate: when still, the skin mean moves < 0.5 level per frame; a head
                    // movement moves it by tens of levels and would own the spectrum for the whole
                    // 10 s window. Drop that data, restart the window, keep showing the last BPM.
                    val step = maxOf(abs(r - prevR), abs(g - prevG), abs(b - prevB))
                    prevR = r; prevG = g; prevB = b
                    // Slow movements (leaning, turning the head a little) change the mean by a few
                    // levels over ~1 s, never > 2 per frame: compare a 3-frame mean with the one
                    // 1/3 s earlier. The pulse itself moves it < 1 level in that time.
                    val hi = histN % HIST
                    histR[hi] = r.toFloat(); histG[hi] = g.toFloat(); histB[hi] = b.toFloat()
                    histN++
                    var drift = 0.0
                    if (histN >= HIST) {
                        fun m3(a: FloatArray, back: Int): Double {
                            var s = 0.0
                            for (q in 0 until 3) s += a[((histN - 1 - back - q) % HIST + HIST) % HIST]
                            return s / 3
                        }
                        drift = maxOf(abs(m3(histR, 0) - m3(histR, 10)), abs(m3(histG, 0) - m3(histG, 10)),
                            abs(m3(histB, 0) - m3(histB, 10)))
                    }
                    if (havePrev && (step > MOTION_STEP || drift > MOTION_DRIFT)) {
                        // Estimates from just before the jump may already be contaminated by the
                        // start of the movement: fall back to what we had ~3 s earlier.
                        if (pendingRoiTime >= motionUntil) {
                            while (recentHz.isNotEmpty() && recentHz.last().first > pendingRoiTime - 3.0) recentHz.removeLast()
                            if (recentHz.isNotEmpty()) f0 = medianHz()
                        }
                        if (pendingRoiTime >= motionUntil) motionStreakStart = pendingRoiTime
                        motionUntil = pendingRoiTime + MOTION_HOLD
                        estimator.reset()
                        estH[0].reset(); estH[1].reset()
                        posReady = false
                    }
                    havePrev = true
                    var p: Double? = null
                    if (pendingRoiTime >= motionUntil) {
                        if (!posReady) {
                            val fs = if (fpsEma > 5.0) fpsEma else 30.0
                            pos.reset(fs)
                            posH[0].reset(fs); posH[1].reset(fs)
                            posReady = true
                        }
                        if (halvesOk) {
                            for (q in 0..1) {
                                val ph = posH[q].push(hR[q] / hW[q], hG[q] / hW[q], hB[q] / hW[q])
                                if (ph != null) estH[q].add(pendingRoiTime, ph)
                            }
                        }
                        // POS output lags one window; its timestamps follow the frame clock.
                        p = pos.push(r, g, b)
                        if (p != null) {
                            estimator.add(pendingRoiTime, p)
                            pushTrace(p.toFloat())
                        }
                    }
                    log(String.format(Locale.US, "S,%.4f,%.3f,%.3f,%.3f,%.0f,%s", pendingRoiTime, r, g, b, skin, p?.let { String.format(Locale.US, "%.6f", it) } ?: ""))
                }
                }
            }
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        pendingRoiTime = time
        pboPrimed = true
        pboIdx = 1 - pboIdx
    }

    /**
     * Soft version of AirPulse's YCrCb skin test (inputs 0..255): 1 well inside the box,
     * fading to 0 over a few levels at each edge, so weights change smoothly.
     */
    // --- finger on the lens ----------------------------------------------------------------
    @Volatile private var noFinger = false
    private var fingerOn = false
    private var fingerVotes = -10
    private var fingerUseG = true
    private var fingerPrev = 0.0
    private var fingerEma = 0.0

    /**
     * Finger mode: light through the fingertip makes the whole frame red; each heartbeat
     * pushes more blood in, which absorbs more (red channel: cleanest without a torch; green if red clips).
     * Signal = -(channel - its 1 s mean), so the systolic peak points up on the trace.
     */
    private fun fingerSample(r: Double, g: Double, b: Double, cv: Double) {
        val t = pendingRoiTime
        log(String.format(Locale.US, "F,%.4f,%.2f,%.2f,%.2f,%.4f", t, r, g, b, cv))
        // Light through a fingertip: measured red/green 7-13, red/blue 6-30. Not a finger: face/room
        // red/green 1.0-1.3; warm-lit desk 1.74 (blue very low too!); dark keyboard 2.1.
        // Debounced ~1/3 s both ways so one odd frame doesn't flip the state.
        // Enter at red/green > 3, stay while > 2.4 (lighter pressure makes the finger paler).
        val looksLikeFinger = r > 25.0 && r > 2.5 * b && r > (if (fingerOn) 2.4 else 3.0) * g
        fingerVotes = if (looksLikeFinger) min(fingerVotes + 1, 10) else max(fingerVotes - 1, -10)
        val present = if (fingerOn) fingerVotes > -10 else fingerVotes >= 10
        if (!present) {
            if (fingerOn || f0 > 0.0) {
                estimator.reset()
                recentHz.clear()
                f0 = 0.0
                farSince = -1.0
                synchronized(trace) { traceCount = 0 }
            }
            fingerOn = false
            noFinger = true
            return
        }
        noFinger = false
        if (!fingerOn) {
            fingerOn = true
            // Without a torch, mostly red light gets through the fingertip: red is 5-30x cleaner
            // than green (measured). Green only if red is clipped.
            fingerUseG = r > 245.0
            val v0 = if (fingerUseG) g else r
            fingerPrev = v0
            fingerEma = v0
            motionUntil = t + 1.0 // let exposure / pressure settle
            estimator.reset()
        }
        val v = if (fingerUseG) g else r
        // Pressing harder / slipping moves the level by many percent at once; the pulse does not.
        // (auto-exposure keeps adjusting in finger mode: steps of ~10 % are normal, not motion)
        if (abs(v - fingerPrev) > 0.2 * max(v, 1.0)) {
            motionUntil = t + MOTION_HOLD
            estimator.reset()
        }
        fingerPrev = v
        fingerEma += (1.0 - exp(-lastDt / 1.0)) * (v - fingerEma)
        if (t < motionUntil) return
        val s = fingerEma - v
        estimator.add(t, s)
        pushTrace(s.toFloat())
        log(String.format(Locale.US, "S,%.4f,%.3f,%.3f,%.3f,2,%.6f", t, r, g, b, s))
    }

    private fun medianHz(): Double = recentHz.map { it.second }.sorted()[recentHz.size / 2]

    private fun skinWeight(r: Float, g: Float, b: Float): Float {
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        val cr = (r - luma) * 0.713f + 128f
        val cb = (b - luma) * 0.564f + 128f
        fun ramp(v: Float, width: Float) = (v / width).coerceIn(0f, 1f)
        return ramp(cr - 133f, 4f) * ramp(178f - cr, 4f) * ramp(cb - 77f, 4f) * ramp(135f - cb, 4f) *
            ramp(luma - 40f, 10f) * ramp(250f - maxOf(r, g, b), 8f)
    }

    private fun drawComposite(lockIn: Boolean) {
        val top = levels.last()
        val div = (1 shl levels.size).toFloat()
        val p = progComposite
        p.use()
        p.mat4("uTexMatrix", texMatrix)
        p.tex("uCam", 0, camTex, GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        p.tex("uHigh", 1, lpHigh[iirCur]!!.tex)
        p.tex("uLow", 2, lpLow[iirCur]!!.tex)
        p.tex("uLock", 3, lock[lockCur]!!.tex)
        p.i2("uLevelSize", top.width, top.height)
        p.f2("uFrameSize", frameW.toFloat(), frameH.toFloat())
        p.f("uLevelDiv", div)
        val mode = when {
            !lockIn -> 0
            !lockValid -> -1 // nothing to show yet: plain gray
            settings.phaseHue -> 2
            else -> 1
        }
        p.i("uMode", if (mode < 0) 1 else mode)
        p.f("uAlpha", if (mode < 0) 0f else settings.alpha)
        p.f("uLumaGain", settings.lumaGain)
        p.f("uThr", if (mode < 0) 1f else settings.threshold * PulseSettings.THR_MAX)
        p.f("uHeatScale", PulseSettings.HEAT_SCALE)
        p.f3("uRoi", 0f, 0f, 0f)
        draw()
    }

    private fun draw() = GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

    /**
     * texMatrix maps display uv -> camera buffer uv.
     * Some devices (e.g. Huawei) already put the sensor rotation - and the selfie mirror for the
     * front camera - into the SurfaceTexture matrix: then its 2x2 part swaps the axes and we use
     * it as is. Otherwise: ST * T(.5) * Rz(rotation) * mirror * T(-.5).
     */
    private fun buildTexMatrix() {
        val rot = ((rotation % 360) + 360) % 360
        val stSwapsAxes = abs(stMatrix[0]) < 0.5f && abs(stMatrix[5]) < 0.5f
        if ((rot == 90 || rot == 270) && stSwapsAxes) {
            System.arraycopy(stMatrix, 0, texMatrix, 0, 16)
            return
        }
        val r = tmpM
        Matrix.setIdentityM(r, 0)
        Matrix.translateM(r, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(r, 0, rotation.toFloat(), 0f, 0f, 1f)
        if (mirror) Matrix.scaleM(r, 0, -1f, 1f, 1f)
        Matrix.translateM(r, 0, -0.5f, -0.5f, 0f)
        Matrix.multiplyMM(texMatrix, 0, stMatrix, 0, r, 0)
    }

    private fun dirToBuffer(dx: Float, dy: Float): FloatArray {
        tmpV[0] = dx; tmpV[1] = dy; tmpV[2] = 0f; tmpV[3] = 0f
        Matrix.multiplyMV(tmpOut, 0, texMatrix, 0, tmpV, 0)
        return tmpOut
    }

    private fun finishRecording() {
        val enc = encoder ?: return
        encoder = null
        val e = egl
        var ok = false
        try {
            enc.drain(true)
        } catch (t: Throwable) {
            Log.e(TAG, "drain eos", t)
        } finally {
            ok = enc.release()
            if (e != null) {
                runCatching { e.makeCurrent(drawSurface()) }
                e.releaseSurface(encSurface)
            }
            encSurface = EGL14.EGL_NO_SURFACE
        }
        val cb = encDone
        encDone = null
        main.post { cb?.invoke(ok) }
    }

    private fun reportError(msg: String) {
        Log.e(TAG, msg)
        if (errorReported) return
        errorReported = true
        main.post { listener.onError(msg) }
    }

    private fun runSync(block: () -> Unit) {
        if (Looper.myLooper() == thread.looper) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        val posted = handler.post {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "runSync", t)
            } finally {
                latch.countDown()
            }
        }
        if (posted) latch.await(2, TimeUnit.SECONDS)
    }

    companion object {
        private const val TAG = "PulseRenderer"
        private const val MIN_SNR = 2.0
        private const val MOTION_STEP = 2.0 // levels (0..255) per frame
        private const val MOTION_HOLD = 1.5 // s of data dropped after the last big step
        private const val JUMP_HZ = 0.2 // 12 BPM
        private const val NEAR_HZ = 0.13 // 8 BPM
        private const val JUMP_CONFIRM = 8.0 // s a far peak must persist to be believed
        private const val MOTION_DRIFT = 1.5 // levels change of the 3-frame mean over 10 frames
        private const val HIST = 16
        private const val COHERE_HZ = 0.1 // 6 BPM: halves must agree with the full estimate
        private const val NO_PULSE_SEC = 10.0
        private const val FIRST_SNR = 3.0
        private const val FAST_CONFIRM = 2.0 // s, a jump both halves agree on
        private const val MOTION_NO_PULSE_SEC = 8.0
        private const val TRACE_LEN = 180 // ~6 s at 30 fps
    }
}
