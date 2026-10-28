package com.everettpenne.holtzman.save

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES31

/**
 * An OpenGL ES 3.1 context with no window (a 1x1 pbuffer), for compute shaders. It belongs to the thread that created it and must be closed on that
 * thread. Throws from the constructor if the device cannot give one, so callers can fall back to the CPU.
 */
class GlContext : AutoCloseable {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val v = IntArray(2)
            check(EGL14.eglInitialize(display, v, 0, v, 1)) { "EGL did not initialise" }
            val attribs = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
            val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "no ES 3 config" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "no ES 3 context" }
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "could not make the context current" }
            val version = GLES31.glGetString(GLES31.GL_VERSION) ?: ""
            check(version.contains("OpenGL ES 3.1") || version.contains("OpenGL ES 3.2")) { "compute shaders need ES 3.1, this is: $version" }
        } catch (t: Throwable) { close(); throw t }
    }

    override fun close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY; context = EGL14.EGL_NO_CONTEXT; surface = EGL14.EGL_NO_SURFACE
    }

    fun program(source: String): Int {
        val shader = GLES31.glCreateShader(GLES31.GL_COMPUTE_SHADER)
        GLES31.glShaderSource(shader, source); GLES31.glCompileShader(shader)
        val ok = IntArray(1); GLES31.glGetShaderiv(shader, GLES31.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "compute shader did not compile: ${GLES31.glGetShaderInfoLog(shader)}" }
        val p = GLES31.glCreateProgram()
        GLES31.glAttachShader(p, shader); GLES31.glLinkProgram(p)
        GLES31.glGetProgramiv(p, GLES31.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "compute program did not link: ${GLES31.glGetProgramInfoLog(p)}" }
        GLES31.glDeleteShader(shader)
        return p
    }

    fun loc(program: Int, name: String): Int = GLES31.glGetUniformLocation(program, name).also { check(it >= 0) { "shader has no uniform $name" } }

    fun setInts(program: Int, name: String, vararg v: Int) {
        when (v.size) { 1 -> GLES31.glUniform1i(loc(program, name), v[0]); 2 -> GLES31.glUniform2i(loc(program, name), v[0], v[1]); 4 -> GLES31.glUniform4i(loc(program, name), v[0], v[1], v[2], v[3]) else -> error("1, 2 or 4 ints") }
    }

    /** Makes a shader-storage buffer of [size] bytes, filled from [data] when given. */
    fun buffer(size: Int, data: java.nio.ByteBuffer? = null): Int {
        val id = IntArray(1); GLES31.glGenBuffers(1, id, 0)
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, id[0])
        data?.rewind()
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, size, data, if (data != null) GLES31.GL_STATIC_DRAW else GLES31.GL_DYNAMIC_COPY)
        return id[0]
    }

    fun bind(binding: Int, buffer: Int) = GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, binding, buffer)
    fun delete(vararg buffers: Int) = GLES31.glDeleteBuffers(buffers.size, buffers, 0)
}
