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
 * two demosaics and tones. The demosaic taps are generated from [IxRender.kernels], so the CPU and GPU renderers share their numbers. Needs a
 * thread of its own while it lives (it owns an EGL context). Throws if the device cannot run it; callers fall back to the CPU renderer.
 */
class GpuRender(private val gl: GlContext = GlContext(), private val ownsContext: Boolean = true) : AutoCloseable {
    private var linearProgram = 0
    private var toneProgram = 0
    private var sharpenProgram = 0

    init {
        try { linearProgram = gl.program(LINEAR_SHADER); toneProgram = gl.program(toneShader()); sharpenProgram = gl.program(SHARPEN_SHADER) } catch (t: Throwable) { close(); throw t }
    }

    /** Renders the whole mosaic [raw] (a [width] x [height] ARGB bitmap comes back), with the same inputs as the CPU renderer. */
    fun toBitmap(raw: ShortArray, width: Int, height: Int, s: IxRenderSettings, exposure: Float): Bitmap {
        require(raw.size >= width * height && width % 2 == 0) { "need a tight mosaic with an even width" }
        val pixels = width * height
        val buffers = IntArray(5)   // raw, shading gains, linear floats, packed output, sharpened output
        GLES31.glGenBuffers(5, buffers, 0)
        try {
            val rawBytes = ByteBuffer.allocateDirect(pixels * 2).order(ByteOrder.nativeOrder()).also { it.asShortBuffer().put(raw, 0, pixels); it.rewind() }
            upload(buffers[0], rawBytes, pixels * 2)
            val shading = s.shading
            val gains = ByteBuffer.allocateDirect(maxOf(16, (shading?.gains?.size ?: 4) * 4)).order(ByteOrder.nativeOrder())
            shading?.let { gains.asFloatBuffer().put(it.gains) }
            upload(buffers[1], gains, gains.capacity())
            allocate(buffers[2], pixels * 4)
            allocate(buffers[3], pixels * 4)
            allocate(buffers[4], pixels * 4)
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, buffers[0])
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 1, buffers[1])
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 2, buffers[2])
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 3, buffers[3])
            GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 4, buffers[4])
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

            GLES31.glUseProgram(toneProgram)
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
            GLES31.glUniform1i(gl.loc(toneProgram, "hasCurve"), if (s.look.curve != null) 1 else 0)
            GLES31.glDispatchCompute(gx, gy, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

            // sharpen: reads the finished picture (binding 3) and writes the sharpened one (binding 4)
            GLES31.glUseProgram(sharpenProgram)
            gl.setInts(sharpenProgram, "size", width, height)
            GLES31.glUniform1f(gl.loc(sharpenProgram, "amount"), s.look.sharpen)
            GLES31.glUniform1f(gl.loc(sharpenProgram, "core"), s.look.sharpenCore / 255f)
            GLES31.glDispatchCompute(gx, gy, 1)
            GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)

            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, buffers[4])
            val mapped = GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, pixels * 4, GLES31.GL_MAP_READ_BIT) as ByteBuffer
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(mapped.order(ByteOrder.nativeOrder()))   // the shader packs bytes as R, G, B, A, which is Bitmap's memory order
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            check(GLES31.glGetError() == GLES31.GL_NO_ERROR) { "GL error during the render" }
            return bitmap
        } finally { GLES31.glDeleteBuffers(5, buffers, 0) }
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

        fun kernelFunction(name: String): String {
            val taps = IxRender.kernels.getValue(name)
            fun f(x: Float) = "%.9f".format(java.util.Locale.ROOT, x)
            val offsets = taps.joinToString(", ") { "ivec2(${it.dx}, ${it.dy})" }
            val weights = taps.joinToString(", ") { f(it.weight) }
            return """
            float $name(int x, int y) {
                const ivec2 o[${taps.size}] = ivec2[${taps.size}]($offsets);
                const float w[${taps.size}] = float[${taps.size}]($weights);
                float a = 0.0;
                for (int i = 0; i < ${taps.size}; i++) a += w[i] * at(x + o[i].x, y + o[i].y);
                return a;
            }"""
        }

        /** Pass two: demosaic, white balance, colour matrix, exposure, shoulder, sRGB, pack as R, G, B, A bytes. */
        fun toneShader(): String = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 2) readonly buffer Lin { float lin[]; };
            layout(std430, binding = 3) writeonly buffer Out { uint pixelsOut[]; };
            uniform ivec2 size; uniform ivec2 redAt; uniform vec3 wb; uniform mat3 ccm; uniform float exposure;
            uniform float blackPoint; uniform float contrast; uniform float saturation; uniform float curveX[12]; uniform float curveY[12]; uniform int hasCurve;
            $CHANNEL
            float at(int x, int y) { return lin[clamp(y, 0, size.y - 1) * size.x + clamp(x, 0, size.x - 1)]; }
            ${IxRender.kernels.keys.joinToString("\n") { kernelFunction(it) }}
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
                float v = at(p.x, p.y); float r; float g; float b;
                int c = channelAt(p.x, p.y);
                if (c == 0) { r = v; g = greenAtRedBlue(p.x, p.y); b = rbAtBlueRed(p.x, p.y); }
                else if (c == 2) { b = v; g = greenAtRedBlue(p.x, p.y); r = rbAtBlueRed(p.x, p.y); }
                else {
                    g = v;
                    bool redInRow = channelAt(p.x + 1, p.y) == 0 || channelAt(p.x - 1, p.y) == 0;
                    r = redInRow ? rbAtGreenSameRow(p.x, p.y) : rbAtGreenSameCol(p.x, p.y);
                    b = redInRow ? rbAtGreenSameCol(p.x, p.y) : rbAtGreenSameRow(p.x, p.y);
                }
                vec3 w = vec3(max(r, 0.0) * wb.x, max(g, 0.0) * wb.y, max(b, 0.0) * wb.z);
                vec3 o = (ccm * w) * exposure;
                vec3 d = look(vec3(srgb(shoulder(o.r)), srgb(shoulder(o.g)), srgb(shoulder(o.b))));
                pixelsOut[p.y * size.x + p.x] = byteOf(d.r) | (byteOf(d.g) << 8) | (byteOf(d.b) << 16) | 0xFF000000u;
            }
        """
    }
}
