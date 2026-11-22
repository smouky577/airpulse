package com.airpulse.app.pulse

import android.opengl.GLES11Ext
import android.opengl.GLES30

/** Compiled shader program with cached uniform locations. */
class Program(vertex: String, fragment: String) {
    val id: Int
    private val locations = HashMap<String, Int>()

    init {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, vs)
        GLES30.glAttachShader(id, fs)
        GLES30.glLinkProgram(id)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(id)
            GLES30.glDeleteProgram(id)
            error("Program link failed: $log")
        }
    }

    fun use() = GLES30.glUseProgram(id)

    fun loc(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun i(name: String, v: Int) = GLES30.glUniform1i(loc(name), v)
    fun f(name: String, v: Float) = GLES30.glUniform1f(loc(name), v)
    fun f2(name: String, x: Float, y: Float) = GLES30.glUniform2f(loc(name), x, y)
    fun f3(name: String, x: Float, y: Float, z: Float) = GLES30.glUniform3f(loc(name), x, y, z)
    fun i2(name: String, x: Int, y: Int) = GLES30.glUniform2i(loc(name), x, y)
    fun mat4(name: String, m: FloatArray) = GLES30.glUniformMatrix4fv(loc(name), 1, false, m, 0)

    /** Binds a 2D texture to [unit] and points sampler [name] at it. */
    fun tex(name: String, unit: Int, texId: Int, target: Int = GLES30.GL_TEXTURE_2D) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(target, texId)
        i(name, unit)
    }

    fun release() = GLES30.glDeleteProgram(id)

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            error("Shader compile failed: $log")
        }
        return s
    }
}

/** A texture + framebuffer pair. Textures use NEAREST filtering (float textures are read via texelFetch). */
class RenderTarget(val width: Int, val height: Int, internalFormat: Int, format: Int, type: Int) {
    val tex: Int
    val fbo: Int

    init {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        tex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, internalFormat, width, height)
        val f = IntArray(1)
        GLES30.glGenFramebuffers(1, f, 0)
        fbo = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex, 0)
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) {
            "Framebuffer incomplete (0x${Integer.toHexString(status)}) for ${width}x$height fmt=0x${Integer.toHexString(internalFormat)}"
        }
    }

    fun bind() {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, width, height)
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
    }

    companion object {
        fun r32f(w: Int, h: Int) = RenderTarget(w, h, GLES30.GL_R32F, GLES30.GL_RED, GLES30.GL_FLOAT)
        fun rg32f(w: Int, h: Int) = RenderTarget(w, h, GLES30.GL_RG32F, GLES30.GL_RG, GLES30.GL_FLOAT)
        fun rgba32f(w: Int, h: Int) = RenderTarget(w, h, GLES30.GL_RGBA32F, GLES30.GL_RGBA, GLES30.GL_FLOAT)
    }
}

object Gl {
    fun createOesTexture(): Int {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t[0]
    }

    fun deleteTexture(id: Int) {
        if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
    }

    fun hasExtension(name: String): Boolean {
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: return false
        return ext.split(' ').contains(name)
    }
}
