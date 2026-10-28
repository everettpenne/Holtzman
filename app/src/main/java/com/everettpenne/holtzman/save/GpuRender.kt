package com.everettpenne.holtzman.save

import android.graphics.Bitmap
import android.opengl.GLES31
import com.everettpenne.ix.IxRender
import com.everettpenne.ix.IxRenderSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stage 3 on the GPU: the same render as [IxRender.renderRows] (black level, lens shading, Malvar-He-Cutler demosaic, white balance, the camera's
 * colour matrix, shoulder, sRGB) as two OpenGL ES 3.1 compute passes. Pass one turns counts into linear floats with the shading gain applied, pass
 * two demosaics and tones (the green plane first, then colour differences around it, as IxRender does). Needs a
 * thread of its own while it lives (it owns an EGL context). Throws if the device cannot run it; callers fall back to the CPU renderer.
 */
class GpuRender(private val gl: GlContext = GlContext(), private val ownsContext: Boolean = true) : AutoCloseable {
    private var linearProgram = 0
    private var greenProgram = 0
    private var toneProgram = 0
    private var sharpenProgram = 0
    private var lumaProgram = 0
    private var finishProgram = 0
    private var histogramProgram = 0
    private var gridProgram = 0
    private var smoothProgram = 0
    private var lumaHistProgram = 0
    private var curveProgram = 0
    /** False when the adaptive tone shaders did not build here: the fixed curve is used. */
    var autoToneAvailable = false; private set
    /** False when the local tone mapping shaders did not build here: the picture is then toned globally. */
    var localToneAvailable = false; private set
    /** False when the noise-aware finish could not be built on this device: the plain sharpen pass is used instead. */
    var noiseAwareFinish = false; private set

    init {
        try { linearProgram = gl.program(LINEAR_SHADER); greenProgram = gl.program(GREEN_SHADER); toneProgram = gl.program(toneShader()); sharpenProgram = gl.program(SHARPEN_SHADER) } catch (t: Throwable) { close(); throw t }
        // the noise-aware finish is newer: if its shaders do not build here the render still works, with the plain sharpen pass
        try {
            lumaProgram = gl.program(LUMA3_SHADER); finishProgram = gl.program(FINISH_SHADER); histogramProgram = gl.program(NOISE_HISTOGRAM_SHADER)
            for (u in listOf("size", "sigmas", "denoise", "detail", "core", "clarity", "chromaMix", "grain")) gl.loc(finishProgram, u)
            gl.loc(lumaProgram, "size"); gl.loc(histogramProgram, "size")
            noiseAwareFinish = true
        } catch (t: Throwable) { android.util.Log.w("HoltzmanGpu", "noise-aware finish unavailable, using the plain sharpen pass: $t") }
        try {
            gridProgram = gl.program(GRID_SHADER); smoothProgram = gl.program(SMOOTH_SHADER)
            for (u in listOf("size", "redAt", "wb", "exposure")) gl.loc(gridProgram, u)
            gl.loc(smoothProgram, "gridSize")
            localToneAvailable = true
        } catch (t: Throwable) { android.util.Log.w("HoltzmanGpu", "local tone mapping unavailable, toning globally: $t") }
        try {
            lumaHistProgram = gl.program(LUMA_HISTOGRAM_SHADER); curveProgram = gl.program(CURVE_SHADER)
            gl.loc(lumaHistProgram, "size"); gl.loc(lumaHistProgram, "region"); gl.loc(lumaHistProgram, "stride"); gl.loc(curveProgram, "size"); gl.loc(curveProgram, "curveY[0]")
            autoToneAvailable = true
        } catch (t: Throwable) { android.util.Log.w("HoltzmanGpu", "adaptive tone unavailable, using the fixed curve: $t") }
    }

    /** Renders the whole mosaic [raw] (a [width] x [height] ARGB bitmap comes back), with the same inputs as the CPU renderer. */
    private var lap = 0L
    private val laps = StringBuilder()
    /** Time since the last mark, once the GPU has finished what was queued (only when [PROFILE] is on: it stalls the pipeline). */
    private fun mark(name: String) { if (!PROFILE) return; GLES31.glFinish(); val t = System.nanoTime(); laps.append(" $name ${(t - lap) / 1_000_000}"); lap = t }

    fun toBitmap(raw: ShortArray, width: Int, height: Int, s: IxRenderSettings, exposure: Float, face: IntArray? = null): Bitmap {
        laps.setLength(0); lap = System.nanoTime()
        require(raw.size >= width * height && width % 2 == 0) { "need a tight mosaic with an even width" }
        val pixels = width * height
        val buffers = IntArray(9)   // raw, shading gains, linear floats, packed output, finished output, 3x3 mean luma, green plane, spare, tone-map grid, tone-map base
        GLES31.glGenBuffers(9, buffers, 0)
        try {
            val rawBytes = ByteBuffer.allocateDirect(pixels * 2).order(ByteOrder.nativeOrder()).also { it.asShortBuffer().put(raw, 0, pixels); it.rewind() }
            upload(buffers[0], rawBytes, pixels * 2)
            mark("upload")
            val shading = s.shading
            val gains = ByteBuffer.allocateDirect(maxOf(16, (shading?.gains?.size ?: 4) * 4)).order(ByteOrder.nativeOrder())
            shading?.let { gains.asFloatBuffer().put(it.gains) }
            upload(buffers[1], gains, gains.capacity())
            allocate(buffers[2], pixels * 4)
            allocate(buffers[3], pixels * 4)
            allocate(buffers[4], pixels * 4)
            allocate(buffers[6], pixels * 4)
            if (s.look.denoise > 0f && noiseAwareFinish) allocate(buffers[5], pixels * 4)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffers[0])
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, buffers[1])
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, buffers[2])
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, buffers[3])
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 4, buffers[4])
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 5, buffers[5])
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 6, buffers[6])
            val gridW = (width + 15) / 16; val gridH = (height + 15) / 16
            val useLtm = s.look.localTone > 0f && localToneAvailable
            if (useLtm) { allocate(buffers[7], gridW * gridH * 4); allocate(buffers[8], gridW * gridH * 4); GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 8, buffers[7]); GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 9, buffers[8]) }
            val gx = (width + 15) / 16; val gy = (height + 15) / 16

            GLES31.glUseProgram(linearProgram)
            gl.setInts(linearProgram, "size", width, height)
            gl.setInts(linearProgram, "redAt", s.cfa.redX, s.cfa.redY)
            gl.setInts(linearProgram, "black", *s.black)
            val range = FloatArray(4) { 1f / (s.white - s.black[it]) }
            GLES31.glUniform4f(gl.loc(linearProgram, "range"), range[0], range[1], range[2], range[3])
            GLES31.glUniform1i(gl.loc(linearProgram, "shadingCols"), shading?.cols ?: 0)
            GLES31.glUniform1i(gl.loc(linearProgram, "shadingRows"), shading?.rows ?: 0)
            GLES31.glDispatchCompute(gx, gy, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            mark("linear")

            GLES31.glUseProgram(greenProgram)
            gl.setInts(greenProgram, "size", width, height)
            gl.setInts(greenProgram, "redAt", s.cfa.redX, s.cfa.redY)
            GLES31.glDispatchCompute(gx, gy, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            mark("green")

            if (useLtm) {
                GLES31.glUseProgram(gridProgram)
                gl.setInts(gridProgram, "size", width, height); gl.setInts(gridProgram, "redAt", s.cfa.redX, s.cfa.redY)
                GLES31.glUniform3f(gl.loc(gridProgram, "wb"), s.wb[0], s.wb[1], s.wb[2]); GLES31.glUniform1f(gl.loc(gridProgram, "exposure"), exposure)
                GLES31.glDispatchCompute((gridW + 15) / 16, (gridH + 15) / 16, 1)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
                GLES31.glUseProgram(smoothProgram)
                gl.setInts(smoothProgram, "gridSize", gridW, gridH)
                GLES31.glDispatchCompute((gridW + 15) / 16, (gridH + 15) / 16, 1)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
                mark("ltm")
            }

            GLES31.glUseProgram(toneProgram)
            GLES31.glUniform1i(gl.loc(toneProgram, "hasLtm"), if (useLtm) 1 else 0)
            GLES31.glUniform1f(gl.loc(toneProgram, "ltmC"), 1f - 0.5f * s.look.localTone)
            GLES31.glUniform1f(gl.loc(toneProgram, "ltmMid"), (Math.log(LTM_KEY.toDouble()) / Math.log(2.0)).toFloat())
            gl.setInts(toneProgram, "grid", gridW, gridH)
            gl.setInts(toneProgram, "size", width, height)
            gl.setInts(toneProgram, "redAt", s.cfa.redX, s.cfa.redY)
            GLES31.glUniform3f(gl.loc(toneProgram, "wb"), s.wb[0], s.wb[1], s.wb[2])
            GLES31.glUniformMatrix3fv(gl.loc(toneProgram, "ccm"), 1, true, s.ccm, 0)   // row-major in, so transpose
            GLES31.glUniform1f(gl.loc(toneProgram, "exposure"), exposure)
            GLES31.glUniform1f(gl.loc(toneProgram, "blackPoint"), s.look.blackPoint)
            GLES31.glUniform1f(gl.loc(toneProgram, "contrast"), s.look.contrast)
            GLES31.glUniform1f(gl.loc(toneProgram, "saturation"), s.look.saturation)
            GLES31.glUniform1fv(gl.loc(toneProgram, "curveX[0]"), 12, com.everettpenne.ix.IxLook.CURVE_X, 0)
            GLES31.glUniform1fv(gl.loc(toneProgram, "curveY[0]"), 12, s.look.curve ?: com.everettpenne.ix.IxLook.CURVE_X, 0)
            val useAutoTone = s.look.autoTone > 0f && autoToneAvailable
            GLES31.glUniform1i(gl.loc(toneProgram, "hasCurve"), if (s.look.curve != null && !useAutoTone) 1 else 0)
            GLES31.glDispatchCompute(gx, gy, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            mark("tone")

            if (useAutoTone) {
                // the picture's own brightness distribution decides its tone curve: histogram it, build the curve on the CPU (tiny), apply it in place
                val histBuffer = IntArray(1); GLES31.glGenBuffers(1, histBuffer, 0)
                try {
                    GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, histBuffer[0])
                    GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 256 * 4, ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder()), GLES31.GL_DYNAMIC_COPY)
                    GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 7, histBuffer[0])
                    GLES31.glUseProgram(lumaHistProgram)
                    gl.setInts(lumaHistProgram, "size", width, height)
                    gl.setInts(lumaHistProgram, "region", 0, 0, width, height); gl.setInts(lumaHistProgram, "stride", 2)
                    GLES31.glDispatchCompute((width / 2 + 15) / 16, (height / 2 + 15) / 16, 1)
                    GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
                    val counts = (GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 256 * 4, GLES31.GL_MAP_READ_BIT) as ByteBuffer).order(ByteOrder.nativeOrder()).asIntBuffer()
                    val hist = IntArray(256).also { counts.get(it) }
                    GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
                    var curve = com.everettpenne.ix.IxAutoTone.curve(hist, s.look.autoTone)
                    if (face != null && face[2] - face[0] >= 16 && face[3] - face[1] >= 16) {
                        // a face: measure its own brightness (every pixel in its box) and keep it out of the shadows and highlights
                        val faceHist = IntArray(1); GLES31.glGenBuffers(1, faceHist, 0)
                        try {
                            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, faceHist[0])
                            GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 256 * 4, ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder()), GLES31.GL_DYNAMIC_COPY)
                            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 7, faceHist[0])
                            GLES31.glUseProgram(lumaHistProgram)
                            gl.setInts(lumaHistProgram, "region", face[0], face[1], face[2], face[3]); gl.setInts(lumaHistProgram, "stride", 1)
                            GLES31.glDispatchCompute(((face[2] - face[0]) + 15) / 16, ((face[3] - face[1]) + 15) / 16, 1)
                            GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
                            val fc = (GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 256 * 4, GLES31.GL_MAP_READ_BIT) as ByteBuffer).order(ByteOrder.nativeOrder()).asIntBuffer()
                            val fh = IntArray(256).also { fc.get(it) }
                            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
                            var n = 0L; var sum = 0L; for (i in 0 until 256) { n += fh[i]; sum += fh[i].toLong() * i }
                            if (n > 100) {
                                val faceLuma = sum.toFloat() / n / 255f
                                val before = curve
                                curve = com.everettpenne.ix.IxAutoTone.protectFace(curve, faceLuma)
                                android.util.Log.i("HoltzmanGpu", "face brightness before the curve %.2f; curve %s".format(java.util.Locale.ROOT, faceLuma, if (curve.contentEquals(before)) "left alone" else "adjusted to protect it"))
                            }
                        } finally { GLES31.glDeleteBuffers(1, faceHist, 0) }
                    }
                    android.util.Log.i("HoltzmanGpu", "adaptive tone curve: " + curve.joinToString(" ") { "%.0f".format(java.util.Locale.ROOT, it * 255f) })
                    GLES31.glUseProgram(curveProgram)
                    gl.setInts(curveProgram, "size", width, height)
                    GLES31.glUniform1fv(gl.loc(curveProgram, "curveX[0]"), 12, com.everettpenne.ix.IxLook.CURVE_X, 0)
                    GLES31.glUniform1fv(gl.loc(curveProgram, "curveY[0]"), 12, curve, 0)
                    GLES31.glDispatchCompute(gx, gy, 1)
                    GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
                } finally { GLES31.glDeleteBuffers(1, histBuffer, 0) }
                mark("autotone")
            }

            var sigmas = FloatArray(3)
            if (s.look.denoise > 0f && noiseAwareFinish) {
                // how noisy the toned picture is, by brightness: the GPU histograms the noise filter's response over a sample of the picture and the CPU
                // reads back only the histogram (reading the picture itself back sample by sample took 0.7 s)
                val histogram = IntArray(1); GLES31.glGenBuffers(1, histogram, 0)
                try {
                    val bins = 3 * com.everettpenne.ix.IxDisplayNoise.BINS
                    GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, histogram[0])
                    GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, bins * 4, ByteBuffer.allocateDirect(bins * 4).order(ByteOrder.nativeOrder()), GLES31.GL_DYNAMIC_COPY)
                    GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 7, histogram[0])
                    GLES31.glUseProgram(histogramProgram)
                    gl.setInts(histogramProgram, "size", width, height)
                    GLES31.glDispatchCompute((width / 3 + 15) / 16, (height / 3 + 15) / 16, 1)
                    GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
                    val counts = (GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, bins * 4, GLES31.GL_MAP_READ_BIT) as ByteBuffer).order(ByteOrder.nativeOrder()).asIntBuffer()
                    val hist = IntArray(bins).also { counts.get(it) }
                    GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
                    sigmas = com.everettpenne.ix.IxDisplayNoise.fromHistogram(hist)
                } finally { GLES31.glDeleteBuffers(1, histogram, 0) }
                mark("noiseEstimate")
                android.util.Log.i("HoltzmanGpu", "display noise (levels of 255) dark %.2f mid %.2f bright %.2f".format(java.util.Locale.ROOT, sigmas[0] * 255, sigmas[1] * 255, sigmas[2] * 255))
                GLES31.glUseProgram(lumaProgram)
                gl.setInts(lumaProgram, "size", width, height)
                GLES31.glDispatchCompute(gx, gy, 1)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
                mark("luma3")
                GLES31.glUseProgram(finishProgram)
                gl.setInts(finishProgram, "size", width, height)
                GLES31.glUniform3f(gl.loc(finishProgram, "sigmas"), sigmas[0], sigmas[1], sigmas[2])
                GLES31.glUniform1f(gl.loc(finishProgram, "denoise"), s.look.denoise)
                GLES31.glUniform1f(gl.loc(finishProgram, "detail"), s.look.detail)
                GLES31.glUniform1f(gl.loc(finishProgram, "core"), s.look.detailCore)
                GLES31.glUniform1f(gl.loc(finishProgram, "clarity"), s.look.clarity)
                GLES31.glUniform1f(gl.loc(finishProgram, "chromaMix"), s.look.chromaSmoothing)
                GLES31.glUniform1f(gl.loc(finishProgram, "grain"), s.look.grain)
                GLES31.glDispatchCompute(gx, gy, 1)
                GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
                mark("finish")
            } else {
                // plain sharpen: reads the finished picture (binding 3) and writes the sharpened one (binding 4)
                GLES31.glUseProgram(sharpenProgram)
                gl.setInts(sharpenProgram, "size", width, height)
                GLES31.glUniform1f(gl.loc(sharpenProgram, "amount"), s.look.sharpen)
                GLES31.glUniform1f(gl.loc(sharpenProgram, "core"), s.look.sharpenCore / 255f)
                GLES31.glDispatchCompute(gx, gy, 1)
                GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
            }

            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[4])
            val mapped = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, pixels * 4, GLES31.GL_MAP_READ_BIT) as ByteBuffer
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(mapped.order(ByteOrder.nativeOrder()))   // the shader packs bytes as R, G, B, A, which is Bitmap's memory order
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            check(GLES31.glGetError() == GLES31.GL_NO_ERROR) { "GL error during the render" }
            mark("readback")
            if (PROFILE) android.util.Log.i("HoltzmanGpu", "render passes (ms):$laps")
            return bitmap
        } finally { GLES31.glDeleteBuffers(9, buffers, 0) }
    }

    private fun upload(buffer: Int, data: ByteBuffer, size: Int) {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer)
        data.rewind()
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, size, data, GLES31.GL_STATIC_DRAW)
    }

    private fun allocate(buffer: Int, size: Int) {
        GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffer)
        GLES31.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, size, null, GLES31.GL_DYNAMIC_COPY)
    }

    override fun close() { if (ownsContext) gl.close() }

    private companion object {
        /** Logs how long each pass takes (stalls the pipeline to do it, so only for measuring). */
        const val PROFILE = false
        /** The scene key (linear) the local tone mapping compresses about: what the exposure brings an average picture to. */
        const val LTM_KEY = 0.12f
        /** The channel at (x, y): 0 red, 1 green, 2 blue, from where red sits in the 2x2 cell. Same rule as IxCfa.channelAt. */
        const val CHANNEL = """
            int channelAt(int x, int y) {
                int px = (x & 1) - redAt.x; int py = (y & 1) - redAt.y;
                if (px == 0 && py == 0) return 0;
                if (px != 0 && py != 0) return 2;
                return 1;
            }
        """

        /** Pass one: counts to linear floats, black level and range, then the lens-shading gain (bilinear, as IxShadingMap.gain). */
        val LINEAR_SHADER = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 0) readonly buffer Raw { uint raw[]; };
            layout(std430, binding = 1) readonly buffer Shading { float gains[]; };
            layout(std430, binding = 2) writeonly buffer Lin { float lin[]; };
            uniform ivec2 size; uniform ivec2 redAt; uniform ivec4 black; uniform vec4 range;
            uniform int shadingCols; uniform int shadingRows;
            $CHANNEL
            float gainAt(int ch, int cx, int cy) { return gains[(cy * shadingCols + cx) * 4 + ch]; }
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p.x >= size.x || p.y >= size.y) return;
                int i = p.y * size.x + p.x;
                uint word = raw[i >> 1];
                int count = int((i & 1) == 0 ? (word & 0xFFFFu) : (word >> 16));
                int cell = (p.x & 1) + 2 * (p.y & 1);
                float v = float(max(count - black[cell], 0)) * range[cell];
                if (shadingCols >= 2) {
                    int c = channelAt(p.x, p.y);
                    int ch = c == 0 ? 0 : (c == 2 ? 3 : ((channelAt(p.x + 1, p.y) == 0 || channelAt(p.x - 1, p.y) == 0) ? 1 : 2));
                    float fx = clamp(float(p.x) / float(size.x - 1), 0.0, 1.0) * float(shadingCols - 1);
                    float fy = clamp(float(p.y) / float(size.y - 1), 0.0, 1.0) * float(shadingRows - 1);
                    int x0 = min(int(fx), shadingCols - 2); int y0 = min(int(fy), shadingRows - 2);
                    float tx = fx - float(x0); float ty = fy - float(y0);
                    float g = (gainAt(ch, x0, y0) * (1.0 - tx) + gainAt(ch, x0 + 1, y0) * tx) * (1.0 - ty)
                            + (gainAt(ch, x0, y0 + 1) * (1.0 - tx) + gainAt(ch, x0 + 1, y0 + 1) * tx) * ty;
                    v *= g;
                }
                lin[i] = v;
            }
        """

        /**
         * Finishing on the 8-bit picture: smooth chroma, smooth luma in the shadows, and unsharp-mask luma (the pixel minus a 5x5 Gaussian blur, sigma about
         * 1, cored so noise-sized detail is left alone and limited so edges do not halo), with the sharpening fading out in the shadows.
         */
        val SHARPEN_SHADER = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 3) readonly buffer In { uint src[]; };
            layout(std430, binding = 4) writeonly buffer Out { uint dst[]; };
            uniform ivec2 size; uniform float amount; uniform float core;
            vec3 px(int x, int y) {
                uint v = src[clamp(y, 0, size.y - 1) * size.x + clamp(x, 0, size.x - 1)];
                return vec3(float(v & 0xFFu), float((v >> 8) & 0xFFu), float((v >> 16) & 0xFFu)) / 255.0;
            }
            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p.x >= size.x || p.y >= size.y) return;
                vec3 c = px(p.x, p.y);
                if (amount <= 0.0) { dst[p.y * size.x + p.x] = src[p.y * size.x + p.x]; return; }   // sharpening off means untouched
                const float k[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);
                vec3 blur = vec3(0.0);
                for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) blur += k[i + 2] * k[j + 2] * px(p.x + i, p.y + j);
                float l = luma(c); float lb = luma(blur);
                // chroma noise is smoothed everywhere (colour noise is the ugliest and carries no detail), luma noise only in the shadows, where a
                // sensor's noise is strongest and the eye reads least detail; sharpening fades out there so it does not sharpen noise
                float shadow = 1.0 - smoothstep(0.04, 0.22, lb);
                vec3 chroma = mix(c - vec3(l), blur - vec3(lb), 0.6);
                float d = l - lb;
                float lOut = lb + d * (1.0 - 0.85 * shadow);
                float cored = sign(d) * max(abs(d) - core * (1.0 + 2.0 * shadow), 0.0);
                lOut += amount * (1.0 - shadow) * clamp(cored, -0.1, 0.1);
                c = clamp(vec3(lOut) + chroma, 0.0, 1.0);
                dst[p.y * size.x + p.x] = uint(c.r * 255.0 + 0.5) | (uint(c.g * 255.0 + 0.5) << 8) | (uint(c.b * 255.0 + 0.5) << 16) | 0xFF000000u;
            }
        """

        /** Histogram of the toned picture's display luma, 256 bins, over every second pixel each way. */
        val LUMA_HISTOGRAM_SHADER = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 3) readonly buffer In { uint src[]; };
            layout(std430, binding = 7) buffer Hist { uint hist[]; };
            uniform ivec2 size; uniform ivec4 region; uniform int stride;
            void main() {
                ivec2 g = region.xy + ivec2(gl_GlobalInvocationID.xy) * stride;
                if (g.x >= region.z || g.y >= region.w || g.x >= size.x || g.y >= size.y) return;
                uint v = src[g.y * size.x + g.x];
                float l = dot(vec3(float(v & 0xFFu), float((v >> 8) & 0xFFu), float((v >> 16) & 0xFFu)), vec3(0.2126, 0.7152, 0.0722));
                atomicAdd(hist[min(int(l + 0.5), 255)], 1u);
            }
        """

        /** Applies a piecewise-linear tone curve to the toned picture in place (each pixel depends only on itself). */
        val CURVE_SHADER = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 3) buffer Pixels { uint px[]; };
            uniform ivec2 size; uniform float curveX[12]; uniform float curveY[12];
            float curved(float x) {
                if (x <= 0.0) return curveY[0];
                for (int i = 1; i < 12; i++) if (x <= curveX[i]) { float t = (x - curveX[i - 1]) / (curveX[i] - curveX[i - 1]); return curveY[i - 1] + (curveY[i] - curveY[i - 1]) * t; }
                return curveY[11];
            }
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p.x >= size.x || p.y >= size.y) return;
                uint v = px[p.y * size.x + p.x];
                vec3 c = vec3(float(v & 0xFFu), float((v >> 8) & 0xFFu), float((v >> 16) & 0xFFu)) / 255.0;
                c = vec3(curved(c.r), curved(c.g), curved(c.b));
                px[p.y * size.x + p.x] = uint(c.r * 255.0 + 0.5) | (uint(c.g * 255.0 + 0.5) << 8) | (uint(c.b * 255.0 + 0.5) << 16) | 0xFF000000u;
            }
        """

        /** Histograms the noise filter's response (IxDisplayNoise's layout) over every third pixel of the toned picture: 3 areas by brightness, 2048 bins each. */
        val NOISE_HISTOGRAM_SHADER = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 3) readonly buffer In { uint src[]; };
            layout(std430, binding = 7) buffer Hist { uint hist[]; };
            uniform ivec2 size;
            const int STRIDE = 3; const int BINS = 2048; const float TOP = 0.5;
            float lumaAt(int x, int y) {
                uint v = src[y * size.x + x];
                return dot(vec3(float(v & 0xFFu), float((v >> 8) & 0xFFu), float((v >> 16) & 0xFFu)), vec3(0.2126, 0.7152, 0.0722)) / 255.0;
            }
            void main() {
                ivec2 g = ivec2(gl_GlobalInvocationID.xy);
                int x = STRIDE + g.x * STRIDE; int y = STRIDE + g.y * STRIDE;
                if (x >= size.x - STRIDE || y >= size.y - STRIDE) return;
                float l[9]; float mean = 0.0; int k = 0;
                for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) { l[k] = lumaAt(x + i * STRIDE, y + j * STRIDE); mean += l[k]; k++; }
                mean /= 9.0;
                float r = abs(l[0] - 2.0 * l[1] + l[2] - 2.0 * l[3] + 4.0 * l[4] - 2.0 * l[5] + l[6] - 2.0 * l[7] + l[8]);
                int bin = mean < 0.12 ? 0 : (mean < 0.4 ? 1 : 2);
                atomicAdd(hist[bin * BINS + min(int(r / TOP * float(BINS)), BINS - 1)], 1u);
            }
        """

        /** One value per 16x16 block: the log2 brightness (white-balanced, exposed linear luminance) of the block, from the raw mosaic's own samples. */
        val GRID_SHADER = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 2) readonly buffer Lin { float lin[]; };
            layout(std430, binding = 8) writeonly buffer Grid { float grid[]; };
            uniform ivec2 size; uniform ivec2 redAt; uniform vec3 wb; uniform float exposure;
            $CHANNEL
            void main() {
                ivec2 g = ivec2(gl_GlobalInvocationID.xy);
                ivec2 cells = (size + 15) / 16;
                if (g.x >= cells.x || g.y >= cells.y) return;
                vec3 sum = vec3(0.0); vec3 n = vec3(0.0);
                for (int j = 0; j < 16; j++) for (int i = 0; i < 16; i++) {
                    int x = g.x * 16 + i; int y = g.y * 16 + j;
                    if (x >= size.x || y >= size.y) continue;
                    int c = channelAt(x, y);
                    sum[c] += lin[y * size.x + x]; n[c] += 1.0;
                }
                vec3 m = sum / max(n, vec3(1.0));
                float lum = 0.2126 * m.r * wb.x + 0.7152 * m.g * wb.y + 0.0722 * m.b * wb.z;
                grid[g.y * cells.x + g.x] = log2(max(lum * exposure, 1e-4));
            }
        """

        /** Smooths the block brightness map without crossing strong edges (a bilateral filter in log brightness), so the gain follows regions, not detail. */
        val SMOOTH_SHADER = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 8) readonly buffer Grid { float grid[]; };
            layout(std430, binding = 9) writeonly buffer Base { float base[]; };
            uniform ivec2 gridSize;
            void main() {
                ivec2 g = ivec2(gl_GlobalInvocationID.xy);
                if (g.x >= gridSize.x || g.y >= gridSize.y) return;
                float c = grid[g.y * gridSize.x + g.x];
                float num = 0.0; float den = 0.0;
                for (int j = -6; j <= 6; j++) for (int i = -6; i <= 6; i++) {
                    ivec2 q = clamp(g + ivec2(i, j), ivec2(0), gridSize - 1);
                    float v = grid[q.y * gridSize.x + q.x];
                    float d = v - c;
                    float w = exp(-float(i * i + j * j) / 18.0 - d * d / 1.0);
                    num += w * v; den += w;
                }
                base[g.y * gridSize.x + g.x] = num / den;
            }
        """

        /** The 3x3 mean luma of the toned picture: a steadier picture of where the edges are than single noisy pixels give. */
        val LUMA3_SHADER = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 3) readonly buffer In { uint src[]; };
            layout(std430, binding = 5) writeonly buffer Luma { float luma3[]; };
            uniform ivec2 size;
            float lumaAt(int x, int y) {
                uint v = src[clamp(y, 0, size.y - 1) * size.x + clamp(x, 0, size.x - 1)];
                return dot(vec3(float(v & 0xFFu), float((v >> 8) & 0xFFu), float((v >> 16) & 0xFFu)), vec3(0.2126, 0.7152, 0.0722)) / 255.0;
            }
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p.x >= size.x || p.y >= size.y) return;
                float a = 0.0;
                for (int j = -1; j <= 1; j++) for (int i = -1; i <= 1; i++) a += lumaAt(p.x + i, p.y + j);
                luma3[p.y * size.x + p.x] = a / 9.0;
            }
        """

        /**
         * The noise-aware finish (prototyped and tuned on a host against the Pixel app's pictures, see docs/ix-pipeline.md): an edge-aware 7x7 average
         * of luma and colour whose reach follows the measured noise (neighbours count only if their 3x3 mean is within [denoise] noise deviations),
         * then sharpening of the detail that survives (cored at a multiple of the noise), a medium-scale local-contrast boost, all faded in the
         * shadows and near the colour gamut's edge, where clipped channels would turn detail into speckle.
         */
        val FINISH_SHADER = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 3) readonly buffer In { uint src[]; };
            layout(std430, binding = 4) writeonly buffer Out { uint dst[]; };
            layout(std430, binding = 5) readonly buffer Luma { float luma3[]; };
            uniform ivec2 size; uniform vec3 sigmas; uniform float denoise; uniform float detail; uniform float core; uniform float clarity; uniform float chromaMix; uniform float grain;
            vec3 px(int x, int y) {
                uint v = src[clamp(y, 0, size.y - 1) * size.x + clamp(x, 0, size.x - 1)];
                return vec3(float(v & 0xFFu), float((v >> 8) & 0xFFu), float((v >> 16) & 0xFFu)) / 255.0;
            }
            float l3(int x, int y) { return luma3[clamp(y, 0, size.y - 1) * size.x + clamp(x, 0, size.x - 1)]; }
            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
            // film grain: a hash of the pixel position, averaged over a 2x2 so the grain has a size of a pixel or two like real grain
            float hash(ivec2 p) {
                uint h = uint(p.x) * 1597334677u ^ uint(p.y) * 3812015801u;
                h ^= h >> 15; h *= 0x2c1b3c6du; h ^= h >> 12; h *= 0x297a2d39u; h ^= h >> 15;
                return float(h) / 4294967295.0 - 0.5;
            }
            float grainAt(ivec2 p) { return (hash(p) + hash(p + ivec2(1, 0)) + hash(p + ivec2(0, 1)) + hash(p + ivec2(1, 1))) * 0.5 / 0.289; }
            float noiseAt(float l) {
                if (l <= 0.06) return sigmas.x;
                if (l <= 0.26) return mix(sigmas.x, sigmas.y, (l - 0.06) / 0.20);
                if (l <= 0.7) return mix(sigmas.y, sigmas.z, (l - 0.26) / 0.44);
                return sigmas.z;
            }
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p.x >= size.x || p.y >= size.y) return;
                vec3 c = px(p.x, p.y);
                float l = luma(c);
                float m = l3(p.x, p.y);
                float sm = noiseAt(m);
                float rs2 = 2.0 * denoise * denoise * sm * sm + 1e-12;
                const float gk[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);
                float numL = 0.0; vec3 numC = vec3(0.0); float den = 0.0; float g1 = 0.0;
                for (int dy = -3; dy <= 3; dy++) for (int dx = -3; dx <= 3; dx++) {
                    vec3 q = px(p.x + dx, p.y + dy);
                    float lq = luma(q);
                    float d = l3(p.x + dx, p.y + dy) - m;
                    float w = exp(-(d * d) / rs2 - float(dx * dx + dy * dy) / 5.12);
                    numL += w * lq; numC += w * (q - vec3(lq)); den += w;
                    if (abs(dx) <= 2 && abs(dy) <= 2) g1 += gk[dx + 2] * gk[dy + 2] * lq;
                }
                float lD = numL / den;
                vec3 chroma = (c - vec3(l)) * (1.0 - chromaMix) + (numC / den) * chromaMix;
                // the wide blur for local contrast: a sparse 5x5 over the 3x3-smoothed luma, weights of a Gaussian of about 3 pixels
                float g4 = 0.0; float g4w = 0.0;
                for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) {
                    float w = exp(-float((i * i + j * j) * 4) / 16.0);
                    g4 += w * l3(p.x + i * 2, p.y + j * 2); g4w += w;
                }
                g4 /= g4w;
                float s = clamp((m - 0.04) / 0.18, 0.0, 1.0);
                float shadow = 1.0 - s * s * (3.0 - 2.0 * s);
                float g = clamp((min(c.r, min(c.g, c.b)) - 0.03) / 0.12, 0.0, 1.0);
                g = g * g * (3.0 - 2.0 * g);
                float dl = lD - g1;
                float cored = sign(dl) * max(abs(dl) - core * sm, 0.0);
                float outL = lD + detail * (1.0 - 0.5 * shadow) * g * clamp(cored, -0.1, 0.1)
                           + clarity * (1.0 - shadow) * g * clamp(g1 - g4, -0.08, 0.08);
                // grain restores the texture heavy smoothing takes out: none in the deepest shadows, where it would only read as noise, and less in highlights
                if (grain > 0.0) outL += grain / 255.0 * grainAt(p) * smoothstep(0.03, 0.2, m) * (1.0 - 0.6 * smoothstep(0.6, 1.0, m));
                vec3 o = clamp(vec3(outL) + chroma, 0.0, 1.0);
                dst[p.y * size.x + p.x] = uint(o.r * 255.0 + 0.5) | (uint(o.g * 255.0 + 0.5) << 8) | (uint(o.b * 255.0 + 0.5) << 16) | 0xFF000000u;
            }
        """

        /**
         * The green plane: measured where the sensor is green, elsewhere the Hamilton-Adams estimate of IxRender.greenEstimate (row and column
         * interpolation blended by the inverse squared gradient, aggregated over the neighbouring same-colour sites).
         */
        val GREEN_SHADER = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 2) readonly buffer Lin { float lin[]; };
            layout(std430, binding = 6) writeonly buffer Green { float green[]; };
            uniform ivec2 size; uniform ivec2 redAt;
            const float EPS = 0.02;
            $CHANNEL
            float at(int x, int y) { return lin[clamp(y, 0, size.y - 1) * size.x + clamp(x, 0, size.x - 1)]; }
            float dh(int x, int y) { return abs(at(x - 1, y) - at(x + 1, y)) + abs(2.0 * at(x, y) - at(x - 2, y) - at(x + 2, y)); }
            float dv(int x, int y) { return abs(at(x, y - 1) - at(x, y + 1)) + abs(2.0 * at(x, y) - at(x, y - 2) - at(x, y + 2)); }
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p.x >= size.x || p.y >= size.y) return;
                int x = p.x; int y = p.y;
                float c = at(x, y);
                float result = c;
                if (channelAt(x, y) != 1) {
                    float gh = (at(x - 1, y) + at(x + 1, y)) * 0.5 + (2.0 * c - at(x - 2, y) - at(x + 2, y)) * 0.25;
                    float gv = (at(x, y - 1) + at(x, y + 1)) * 0.5 + (2.0 * c - at(x, y - 2) - at(x, y + 2)) * 0.25;
                    float sh = 2.0 * dh(x - 2, y) + 4.0 * dh(x, y) + 2.0 * dh(x + 2, y) + 2.0 * dh(x, y - 2) + 2.0 * dh(x, y + 2)
                             + dh(x - 2, y - 2) + dh(x + 2, y + 2) + dh(x - 2, y + 2) + dh(x + 2, y - 2);
                    float sv = 2.0 * dv(x - 2, y) + 4.0 * dv(x, y) + 2.0 * dv(x + 2, y) + 2.0 * dv(x, y - 2) + 2.0 * dv(x, y + 2)
                             + dv(x - 2, y - 2) + dv(x + 2, y + 2) + dv(x - 2, y + 2) + dv(x + 2, y - 2);
                    float wh = 1.0 / ((EPS + sh / 8.0) * (EPS + sh / 8.0)); float wv = 1.0 / ((EPS + sv / 8.0) * (EPS + sv / 8.0));
                    result = max((wh * gh + wv * gv) / (wh + wv), 0.0);
                }
                green[y * size.x + x] = result;
            }
        """

        /** Pass three: colour from the colour differences (R minus G, B minus G) around each site, then white balance, matrix, exposure, curve and look. */
        fun toneShader(): String = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 2) readonly buffer Lin { float lin[]; };
            layout(std430, binding = 3) writeonly buffer Out { uint pixelsOut[]; };
            layout(std430, binding = 6) readonly buffer Green { float green[]; };
            layout(std430, binding = 9) readonly buffer Base { float base[]; };
            uniform ivec2 size; uniform ivec2 redAt; uniform vec3 wb; uniform mat3 ccm; uniform float exposure;
            uniform float blackPoint; uniform float contrast; uniform float saturation; uniform float curveX[12]; uniform float curveY[12]; uniform int hasCurve;
            uniform float ltmC; uniform float ltmMid; uniform ivec2 grid; uniform int hasLtm;
            const float EPS = 0.02;
            $CHANNEL
            float at(int x, int y) { return lin[clamp(y, 0, size.y - 1) * size.x + clamp(x, 0, size.x - 1)]; }
            float g(int x, int y) { return green[clamp(y, 0, size.y - 1) * size.x + clamp(x, 0, size.x - 1)]; }
            float k(int x, int y) { return at(x, y) - g(x, y); }
            float shoulder(float x) { return x <= 0.75 ? max(x, 0.0) : 0.75 + 0.25 * (1.0 - exp(-(x - 0.75) / 0.25)); }
            float srgb(float x) { float c = clamp(x, 0.0, 1.0); return c <= 0.0031308 ? 12.92 * c : 1.055 * pow(c, 1.0 / 2.4) - 0.055; }
            float curved(float x) {
                if (hasCurve == 0) return x;
                if (x <= 0.0) return curveY[0];
                for (int i = 1; i < 12; i++) if (x <= curveX[i]) { float t = (x - curveX[i - 1]) / (curveX[i] - curveX[i - 1]); return curveY[i - 1] + (curveY[i] - curveY[i - 1]) * t; }
                return curveY[11];
            }
            vec3 look(vec3 c) {
                c = clamp((c - blackPoint) / (1.0 - blackPoint), 0.0, 1.0);
                c += contrast * (c * c * (3.0 - 2.0 * c) - c);
                float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
                c = clamp(vec3(l) + (c - vec3(l)) * saturation, 0.0, 1.0);
                return vec3(curved(c.r), curved(c.g), curved(c.b));
            }
            uint byteOf(float x) { return uint(x * 255.0 + 0.5); }
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p.x >= size.x || p.y >= size.y) return;
                float v = at(p.x, p.y); float r; float gr = g(p.x, p.y); float b;
                int c = channelAt(p.x, p.y);
                if (c == 1) {
                    bool redInRow = channelAt(p.x + 1, p.y) == 0 || channelAt(p.x - 1, p.y) == 0;
                    float kh = 0.5 * (k(p.x - 1, p.y) + k(p.x + 1, p.y)); float kv = 0.5 * (k(p.x, p.y - 1) + k(p.x, p.y + 1));
                    r = gr + (redInRow ? kh : kv); b = gr + (redInRow ? kv : kh);
                } else {
                    float d1 = abs(g(p.x - 1, p.y - 1) - g(p.x + 1, p.y + 1)); float d2 = abs(g(p.x + 1, p.y - 1) - g(p.x - 1, p.y + 1));
                    float k1 = 0.5 * (k(p.x - 1, p.y - 1) + k(p.x + 1, p.y + 1)); float k2 = 0.5 * (k(p.x + 1, p.y - 1) + k(p.x - 1, p.y + 1));
                    float w1 = 1.0 / ((EPS + d1) * (EPS + d1)); float w2 = 1.0 / ((EPS + d2) * (EPS + d2));
                    float other = gr + (w1 * k1 + w2 * k2) / (w1 + w2);
                    if (c == 0) { r = v; b = other; } else { b = v; r = other; }
                }
                vec3 w = vec3(max(r, 0.0) * wb.x, max(gr, 0.0) * wb.y, max(b, 0.0) * wb.z);
                // clipped highlights (see IxRender): where the raw signal is at its limit the colour is unknown, so ease it toward white, not magenta
                float clipMix = smoothstep(0.92, 1.0, max(r, max(gr, b)));
                w = mix(w, vec3(max(w.x, max(w.y, w.z))), clipMix);
                vec3 o = (ccm * w) * exposure;
                if (hasLtm == 1) {
                    // the brightness of the neighbourhood (a smoothed, edge-preserving map at 1/16 resolution), sampled bilinearly
                    vec2 gp = (vec2(p) + 0.5) / 16.0 - 0.5;
                    ivec2 g0 = ivec2(floor(gp)); vec2 f = gp - vec2(g0);
                    ivec2 a = clamp(g0, ivec2(0), grid - 1); ivec2 b = clamp(g0 + 1, ivec2(0), grid - 1);
                    float nb = mix(mix(base[a.y * grid.x + a.x], base[a.y * grid.x + b.x], f.x), mix(base[b.y * grid.x + a.x], base[b.y * grid.x + b.x], f.x), f.y);
                    // compress the range of the neighbourhood brightness about the scene's key: shadows come up, highlights ease down, local detail is untouched
                    o *= clamp(exp2((ltmC - 1.0) * (nb - ltmMid)), 0.6, 3.0);
                }
                vec3 d = look(vec3(srgb(shoulder(o.r)), srgb(shoulder(o.g)), srgb(shoulder(o.b))));
                pixelsOut[p.y * size.x + p.x] = byteOf(d.r) | (byteOf(d.g) << 8) | (byteOf(d.b) << 16) | 0xFF000000u;
            }
        """
    }
}
