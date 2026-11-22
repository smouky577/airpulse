package com.airpulse.app.pulse

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface

/**
 * Minimal EGL14 wrapper: one GLES 3 context with a *recordable* config so the same
 * context can render both to the screen and to a MediaCodec input surface.
 */
class EglCore {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val config: EGLConfig
    val context: EGLContext

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize failed" }
        config = chooseConfig(recordable = true, pbuffer = true)
            ?: chooseConfig(recordable = true, pbuffer = false)
            ?: chooseConfig(recordable = false, pbuffer = true)
            ?: error("No suitable EGL config (GLES 3)")
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: 0x" + Integer.toHexString(EGL14.eglGetError()) }
    }

    private fun chooseConfig(recordable: Boolean, pbuffer: Boolean): EGLConfig? {
        val attrs = ArrayList<Int>()
        attrs += listOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, if (pbuffer) EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT else EGL14.EGL_WINDOW_BIT
        )
        if (recordable) attrs += listOf(EGL_RECORDABLE_ANDROID, 1)
        attrs += EGL14.EGL_NONE
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs.toIntArray(), 0, configs, 0, 1, num, 0) || num[0] == 0) return null
        return configs[0]
    }

    fun createWindowSurface(surface: Any): EGLSurface {
        val s = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(s != null && s != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()) }
        return s
    }

    /** 1x1 pbuffer, or EGL_NO_SURFACE (surfaceless) if pbuffers aren't supported by the config. */
    fun createOffscreenSurface(): EGLSurface {
        val s = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        return if (s == null) EGL14.EGL_NO_SURFACE else s
    }

    fun makeCurrent(surface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            error("eglMakeCurrent failed: 0x" + Integer.toHexString(EGL14.eglGetError()))
        }
    }

    fun swap(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun setPresentationTime(surface: EGLSurface, nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)
    }

    fun releaseSurface(surface: EGLSurface) {
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
