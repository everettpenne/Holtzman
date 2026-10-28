package com.everettpenne.holtzman.save

import android.opengl.GLES31
import com.everettpenne.ix.IxMergeParams
import com.everettpenne.ix.IxMerged
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stages 1 and 2 on the GPU: the same alignment and merge as [com.everettpenne.ix.IxBurst.merge], as OpenGL ES 3.1 compute shaders. Each alternate
 * frame is reduced to a three-level brightness pyramid, every tile of it searched against the reference's pyramid exactly as the CPU does (same
 * levels, same candidates in the same order, same distance penalties, so the same motion is chosen), and then folded into a running weighted sum
 * with the CPU's noise-aware weight. The only differences from the CPU result are float rounding in the weights, so merged counts can differ by 1.
 */
class GpuMerge(private val gl: GlContext) {
    private val gray = gl.program(GRAY_SHADER)
    private val halve = gl.program(HALVE_SHADER)
    private val align = gl.program(alignShader())
    private val accumulate = gl.program(ACCUMULATE_SHADER)
    private val finish = gl.program(FINISH_SHADER)
    /** The spatial denoise of the merged mosaic; 0 if it would not build here (the merge then returns the mosaic undenoised). */
    private val denoiseProgram = try { gl.program(DENOISE_SHADER).also { p -> for (u in listOf("cells", "halfWidth", "black", "shot", "readVar", "neff", "strength")) gl.loc(p, u) } } catch (t: Throwable) { android.util.Log.w("HoltzmanGpu", "raw denoise unavailable: $t"); 0 }

    /**
     * Merges [frames] (tight mosaics, native-endian 16-bit samples, [width] x [height]) around [reference]. Same contract as IxBurst.merge. The frames are
     * read in place from their direct buffers.
     */
    fun merge(frames: List<ByteBuffer>, width: Int, height: Int, reference: Int, black: IntArray, params: IxMergeParams, denoise: Float = 0f): IxMerged {
        require(frames.size >= 1 && reference in frames.indices && width % 2 == 0 && height % 2 == 0) { "bad merge input" }
        val t0 = System.nanoTime()
        val cw = width / 2; val ch = height / 2
        val w2 = cw / 2; val h2 = ch / 2; val w3 = w2 / 2; val h3 = h2 / 2
        val t = params.tileCells; val t2 = maxOf(1, t / 2); val t3 = maxOf(1, t / 4)
        val tilesX = (cw + t - 1) / t; val tilesY = (ch + t - 1) / t
        val range3 = maxOf(1, params.searchCells / 2)
        val owned = ArrayList<Int>()
        fun buf(bytes: Int, data: ByteBuffer? = null): Int = gl.buffer(bytes, data).also { owned += it }
        try {
            val raws = frames.map { buf(width * height * 2, it) }
            val refL1 = buf(cw * ch * 4); val refL2 = buf(w2 * h2 * 4); val refL3 = buf(w3 * h3 * 4)
            val altL1 = buf(cw * ch * 4); val altL2 = buf(w2 * h2 * 4); val altL3 = buf(w3 * h3 * 4)
            val flow = buf(tilesX * tilesY * 8)
            val acc = buf(cw * ch * 16); val weights = buf(cw * ch * 4)
            val counter = buf(4, ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()))
            val out = buf(width * height * 2)
            val smooth = if (denoise > 0f && denoiseProgram != 0) buf(width * height * 2) else 0

            fun pyramid(raw: Int, l1: Int, l2: Int, l3: Int) {
                GLES31.glUseProgram(gray)
                gl.setInts(gray, "cells", cw, ch); gl.setInts(gray, "black", *black); gl.setInts(gray, "width", width)
                gl.bind(0, raw); gl.bind(1, l1); GLES31.glDispatchCompute((cw + 15) / 16, (ch + 15) / 16, 1)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
                GLES31.glUseProgram(halve)
                gl.setInts(halve, "dstSize", w2, h2); gl.setInts(halve, "srcWidth", cw)
                gl.bind(0, l1); gl.bind(1, l2); GLES31.glDispatchCompute((w2 + 15) / 16, (h2 + 15) / 16, 1)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
                gl.setInts(halve, "dstSize", w3, h3); gl.setInts(halve, "srcWidth", w2)
                gl.bind(0, l2); gl.bind(1, l3); GLES31.glDispatchCompute((w3 + 15) / 16, (h3 + 15) / 16, 1)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
            }
            pyramid(raws[reference], refL1, refL2, refL3)
            params.log("gpu reference pyramid ${(System.nanoTime() - t0) / 1_000_000} ms")

            var first = true
            var tp = 0L; var ta = 0L; var tm = 0L
            for (f in frames.indices) {
                if (f == reference) continue
                var q = System.nanoTime()
                pyramid(raws[f], altL1, altL2, altL3)
                GLES31.glFinish(); tp += System.nanoTime() - q; q = System.nanoTime()
                GLES31.glUseProgram(align)
                gl.setInts(align, "l1", cw, ch); gl.setInts(align, "l2", w2, h2); gl.setInts(align, "l3", w3, h3)
                gl.setInts(align, "tiles", tilesX, tilesY)
                GLES31.glUniform3i(gl.loc(align, "tileSizes"), t, t2, t3); GLES31.glUniform1i(gl.loc(align, "range3"), range3)
                gl.bind(0, refL1); gl.bind(1, refL2); gl.bind(2, refL3); gl.bind(3, altL1); gl.bind(4, altL2); gl.bind(5, altL3); gl.bind(6, flow)
                GLES31.glDispatchCompute(tilesX, tilesY, 1)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
                GLES31.glFinish(); ta += System.nanoTime() - q; q = System.nanoTime()

                GLES31.glUseProgram(accumulate)
                gl.setInts(accumulate, "cells", cw, ch); gl.setInts(accumulate, "width", width); gl.setInts(accumulate, "black", *black)
                gl.setInts(accumulate, "tilesX", tilesX); gl.setInts(accumulate, "tile", t); gl.setInts(accumulate, "first", if (first) 1 else 0)
                GLES31.glUniform1f(gl.loc(accumulate, "shot"), params.shot); GLES31.glUniform1f(gl.loc(accumulate, "read"), params.read)
                GLES31.glUniform1f(gl.loc(accumulate, "fullTrust"), params.fullTrust); GLES31.glUniform1f(gl.loc(accumulate, "noTrust"), params.noTrust)
                gl.bind(0, raws[reference]); gl.bind(1, raws[f]); gl.bind(2, flow); gl.bind(3, acc); gl.bind(4, weights); gl.bind(5, counter)
                GLES31.glDispatchCompute((cw + 15) / 16, (ch + 15) / 16, 1)
                GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
                GLES31.glFinish(); tm += System.nanoTime() - q
                first = false
                params.log("gpu merged frame $f at ${(System.nanoTime() - t0) / 1_000_000} ms (queued)")
            }

            params.log("gpu stage totals: pyramids ${tp / 1_000_000} ms, align ${ta / 1_000_000} ms, accumulate ${tm / 1_000_000} ms")
            // how many frames' worth of averaging the merge achieved: read the contributor count now (this waits for the merge passes), because the
            // spatial denoise sizes its tolerance by it
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, counter)
            val contributors = (GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 4, GLES31.GL_MAP_READ_BIT) as ByteBuffer).order(ByteOrder.nativeOrder()).int.toLong() and 0xFFFFFFFFL
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            val meanFrames = 1f + contributors.toFloat() / (cw * ch)
            GLES31.glUseProgram(finish)
            gl.setInts(finish, "cells", cw, ch); gl.setInts(finish, "width", width); gl.setInts(finish, "hasOthers", if (first) 0 else 1)
            gl.bind(0, raws[reference]); gl.bind(1, acc); gl.bind(2, weights); gl.bind(3, out)
            GLES31.glDispatchCompute((cw + 15) / 16, (ch + 15) / 16, 1)
            GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)

            var result = out
            if (smooth != 0) {
                GLES31.glUseProgram(denoiseProgram)
                gl.setInts(denoiseProgram, "cells", cw, ch); gl.setInts(denoiseProgram, "halfWidth", width / 2); gl.setInts(denoiseProgram, "black", *black)
                GLES31.glUniform1f(gl.loc(denoiseProgram, "shot"), params.shot); GLES31.glUniform1f(gl.loc(denoiseProgram, "readVar"), params.read * params.read)
                GLES31.glUniform1f(gl.loc(denoiseProgram, "neff"), meanFrames); GLES31.glUniform1f(gl.loc(denoiseProgram, "strength"), denoise)
                gl.bind(0, out); gl.bind(1, smooth)
                GLES31.glDispatchCompute((cw + 15) / 16, (ch + 15) / 16, 1)
                GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)
                result = smooth
            } else GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT)

            val samples = ShortArray(width * height)
            GLES31.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, result)
            (GLES31.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, width * height * 2, GLES31.GL_MAP_READ_BIT) as ByteBuffer).order(ByteOrder.nativeOrder()).asShortBuffer().get(samples)
            GLES31.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER)
            check(GLES31.glGetError() == GLES31.GL_NO_ERROR) { "GL error during the merge" }
            params.log("gpu merge done in ${(System.nanoTime() - t0) / 1_000_000} ms")
            return IxMerged(samples, width, height, meanFrames)
        } finally { gl.delete(*owned.toIntArray()) }
    }

    private companion object {
        const val RAW = """
            uint rawAt(int i) { uint wd = raw[i >> 1]; return (i & 1) == 0 ? (wd & 0xFFFFu) : (wd >> 16); }
        """

        /** Half-resolution brightness: the black-subtracted sum of each 2x2 cell (IxBurst.cellGray). */
        val GRAY_SHADER = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 0) readonly buffer Raw { uint raw[]; };
            layout(std430, binding = 1) writeonly buffer Gray { int gray[]; };
            uniform ivec2 cells; uniform ivec4 black; uniform int width;
            $RAW
            void main() {
                ivec2 c = ivec2(gl_GlobalInvocationID.xy);
                if (c.x >= cells.x || c.y >= cells.y) return;
                int x = c.x * 2; int y = c.y * 2;
                gray[c.y * cells.x + c.x] = (int(rawAt(y * width + x)) - black.x) + (int(rawAt(y * width + x + 1)) - black.y)
                    + (int(rawAt((y + 1) * width + x)) - black.z) + (int(rawAt((y + 1) * width + x + 1)) - black.w);
            }
        """

        /** Next pyramid level: the mean of each 2x2 block, integer division truncating toward zero as on the CPU. */
        val HALVE_SHADER = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 0) readonly buffer Src { int src[]; };
            layout(std430, binding = 1) writeonly buffer Dst { int dst[]; };
            uniform ivec2 dstSize; uniform int srcWidth;
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p.x >= dstSize.x || p.y >= dstSize.y) return;
                int x = p.x * 2; int y = p.y * 2;
                dst[p.y * dstSize.x + p.x] = (src[y * srcWidth + x] + src[y * srcWidth + x + 1] + src[(y + 1) * srcWidth + x] + src[(y + 1) * srcWidth + x + 1]) / 4;
            }
        """

        /** One level's block distance, as IxBurst.sad: samples outside either picture cost 4096 each. (No early exit: it never changes which candidate wins.) */
        fun sadFn(name: String, a: String, b: String) = """
            int $name(int w, int h, int ax, int ay, int bx, int by, int size) {
                int sum = 0;
                for (int j = 0; j < size; j++) {
                    int ya = ay + j; int yb = by + j;
                    if (ya < 0 || ya >= h || yb < 0 || yb >= h) { sum += size * 4096; continue; }
                    for (int i = 0; i < size; i++) {
                        int xa = ax + i; int xb = bx + i;
                        sum += (xa >= 0 && xa < w && xb >= 0 && xb < w) ? abs($a[ya * w + xa] - $b[yb * w + xb]) : 4096;
                    }
                }
                return sum;
            }"""

        /**
         * The three-level tile search of IxBurst.align, one workgroup per tile: the candidates of a level are scored in parallel (one thread each), then
         * thread 0 picks the lowest cost, the first of equals in the CPU's loop order (rows of candidates, then columns), so the choice is the CPU's.
         */
        fun alignShader() = """#version 310 es
            layout(local_size_x = 128) in;
            layout(std430, binding = 0) readonly buffer RefL1 { int r1[]; };
            layout(std430, binding = 1) readonly buffer RefL2 { int r2[]; };
            layout(std430, binding = 2) readonly buffer RefL3 { int r3[]; };
            layout(std430, binding = 3) readonly buffer AltL1 { int a1[]; };
            layout(std430, binding = 4) readonly buffer AltL2 { int a2[]; };
            layout(std430, binding = 5) readonly buffer AltL3 { int a3[]; };
            layout(std430, binding = 6) writeonly buffer Flow { ivec2 flow[]; };
            uniform ivec2 l1; uniform ivec2 l2; uniform ivec2 l3; uniform ivec2 tiles; uniform ivec3 tileSizes; uniform int range3;
            shared int costs[128]; shared ivec2 pick3; shared ivec2 pick2; shared ivec2 pick1;
            ${sadFn("sad1", "r1", "a1")}
            ${sadFn("sad2", "r2", "a2")}
            ${sadFn("sad3", "r3", "a3")}
            ivec2 cheapest(int count, int columns, int originX, int originY) {
                int best = 0x7FFFFFFF; int at = 0;
                for (int k = 0; k < count; k++) if (costs[k] < best) { best = costs[k]; at = k; }
                return ivec2(originX + at % columns, originY + at / columns);
            }
            void main() {
                ivec2 tile = ivec2(gl_WorkGroupID.xy);
                int id = int(gl_LocalInvocationIndex);
                int t = tileSizes.x; int t2 = tileSizes.y; int t3 = tileSizes.z; int i = tile.x; int j = tile.y;
                int n = 2 * range3 + 1;
                if (id < n * n) {
                    int sx = id % n - range3; int sy = id / n - range3;
                    costs[id] = sad3(l3.x, l3.y, i * t3, j * t3, i * t3 + sx, j * t3 + sy, t3) + (abs(sx) + abs(sy)) * t3 * 4;
                }
                barrier();
                if (id == 0) pick3 = cheapest(n * n, n, -range3, -range3);
                barrier();
                ivec2 m = pick3;
                if (id < 9) {
                    int tx2 = m.x * 2 + id % 3 - 1; int ty2 = m.y * 2 + id / 3 - 1;
                    costs[id] = sad2(l2.x, l2.y, i * t2, j * t2, i * t2 + tx2, j * t2 + ty2, t2) + (abs(tx2) + abs(ty2)) * t2 * 2;
                }
                barrier();
                if (id == 0) pick2 = cheapest(9, 3, m.x * 2 - 1, m.y * 2 - 1);
                barrier();
                ivec2 b = pick2;
                if (id < 9) {
                    int tx1 = b.x * 2 + id % 3 - 1; int ty1 = b.y * 2 + id / 3 - 1;
                    costs[id] = sad1(l1.x, l1.y, i * t, j * t, i * t + tx1, j * t + ty1, t) + (abs(tx1) + abs(ty1)) * t * 2;
                }
                barrier();
                if (id == 0) { pick1 = cheapest(9, 3, b.x * 2 - 1, b.y * 2 - 1); flow[j * tiles.x + i] = pick1; }
            }
        """

        /** Folds one alternate frame into the running weighted sum, with IxBurst.merge's noise-aware weight. */
        val ACCUMULATE_SHADER = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 0) readonly buffer RefRaw { uint raw[]; };
            layout(std430, binding = 1) readonly buffer AltRaw { uint alt[]; };
            layout(std430, binding = 2) readonly buffer Flow { ivec2 flow[]; };
            layout(std430, binding = 3) buffer Acc { vec4 acc[]; };
            layout(std430, binding = 4) buffer Weights { float wsum[]; };
            layout(std430, binding = 5) buffer Counter { uint contributors; };
            uniform ivec2 cells; uniform int width; uniform ivec4 black; uniform int tilesX; uniform int tile; uniform int first;
            uniform float shot; uniform float read; uniform float fullTrust; uniform float noTrust;
            shared uint localCount;
            $RAW
            uint altAt(int i) { uint wd = alt[i >> 1]; return (i & 1) == 0 ? (wd & 0xFFFFu) : (wd >> 16); }
            void main() {
                if (gl_LocalInvocationIndex == 0u) localCount = 0u;
                barrier();
                ivec2 c = ivec2(gl_GlobalInvocationID.xy);
                if (c.x < cells.x && c.y < cells.y) {
                    int x = c.x * 2; int y = c.y * 2; int o = c.y * cells.x + c.x;
                    ivec4 r = ivec4(int(rawAt(y * width + x)), int(rawAt(y * width + x + 1)), int(rawAt((y + 1) * width + x)), int(rawAt((y + 1) * width + x + 1)));
                    vec4 a = first == 1 ? vec4(r) : acc[o];
                    float ws = first == 1 ? 1.0 : wsum[o];
                    int refSum = (r.x - black.x) + (r.y - black.y) + (r.z - black.z) + (r.w - black.w);
                    float varDiff = 2.0 * (shot * float(max(refSum, 0)) + 4.0 * read * read);
                    ivec2 d = flow[(c.y / tile) * tilesX + c.x / tile];
                    ivec2 p = c + d;
                    if (p.x >= 0 && p.x < cells.x && p.y >= 0 && p.y < cells.y) {
                        int px = p.x * 2; int py = p.y * 2;
                        ivec4 b = ivec4(int(altAt(py * width + px)), int(altAt(py * width + px + 1)), int(altAt((py + 1) * width + px)), int(altAt((py + 1) * width + px + 1)));
                        float diff = float((b.x - black.x) + (b.y - black.y) + (b.z - black.z) + (b.w - black.w) - refSum);
                        float z = abs(diff) / sqrt(max(varDiff, 1e-6));
                        float tt = clamp((noTrust - z) / (noTrust - fullTrust), 0.0, 1.0);
                        float wt = tt * tt * (3.0 - 2.0 * tt);
                        if (wt > 0.0) { a += wt * vec4(b); ws += wt; atomicAdd(localCount, 1u); }
                    }
                    acc[o] = a; wsum[o] = ws;
                }
                barrier();
                if (gl_LocalInvocationIndex == 0u && localCount > 0u) atomicAdd(contributors, localCount);
            }
        """

        /** Spatial denoise of the merged mosaic, one thread per 2x2 cell: IxDenoise.mosaic on the GPU (same weights, same tolerance). */
        val DENOISE_SHADER = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 0) readonly buffer Src { uint src[]; };
            layout(std430, binding = 1) writeonly buffer Dst { uint dst[]; };
            uniform ivec2 cells; uniform int halfWidth; uniform ivec4 black; uniform float shot; uniform float readVar; uniform float neff; uniform float strength;
            vec4 cellAt(ivec2 c) {
                c = clamp(c, ivec2(0), cells - 1);
                uint a = src[(2 * c.y) * halfWidth + c.x]; uint b = src[(2 * c.y + 1) * halfWidth + c.x];
                return vec4(float(a & 0xFFFFu), float(a >> 16), float(b & 0xFFFFu), float(b >> 16));
            }
            void main() {
                ivec2 p = ivec2(gl_GlobalInvocationID.xy);
                if (p.x >= cells.x || p.y >= cells.y) return;
                vec4 c0 = cellAt(p);
                float g0 = dot(c0 - vec4(black), vec4(1.0));
                float variance = (shot * max(g0, 0.0) + 4.0 * readVar) / max(neff, 1.0);
                float denom = 4.0 * strength * strength * variance + 1e-9;
                vec4 acc = vec4(0.0); float ws = 0.0;
                for (int j = -2; j <= 2; j++) for (int i = -2; i <= 2; i++) {
                    vec4 q = cellAt(p + ivec2(i, j));
                    float d = dot(q - vec4(black), vec4(1.0)) - g0;
                    float w = exp(-(d * d) / denom - float(i * i + j * j) / 4.5);
                    acc += w * q; ws += w;
                }
                uvec4 v = uvec4(acc / ws + 0.5);
                dst[(2 * p.y) * halfWidth + p.x] = (v.x & 0xFFFFu) | ((v.y & 0xFFFFu) << 16);
                dst[(2 * p.y + 1) * halfWidth + p.x] = (v.z & 0xFFFFu) | ((v.w & 0xFFFFu) << 16);
            }
        """

        /** Divides the sums by the weights and packs the four samples of each cell back into the mosaic. */
        val FINISH_SHADER = """#version 310 es
            layout(local_size_x = 16, local_size_y = 16) in;
            layout(std430, binding = 0) readonly buffer RefRaw { uint raw[]; };
            layout(std430, binding = 1) readonly buffer Acc { vec4 acc[]; };
            layout(std430, binding = 2) readonly buffer Weights { float wsum[]; };
            layout(std430, binding = 3) writeonly buffer Out { uint outRaw[]; };
            uniform ivec2 cells; uniform int width; uniform int hasOthers;
            $RAW
            void main() {
                ivec2 c = ivec2(gl_GlobalInvocationID.xy);
                if (c.x >= cells.x || c.y >= cells.y) return;
                int x = c.x * 2; int y = c.y * 2; int o = c.y * cells.x + c.x;
                uvec4 v;
                if (hasOthers == 1) v = uvec4(acc[o] / wsum[o] + 0.5);
                else v = uvec4(rawAt(y * width + x), rawAt(y * width + x + 1), rawAt((y + 1) * width + x), rawAt((y + 1) * width + x + 1));
                outRaw[(y * width + x) >> 1] = (v.x & 0xFFFFu) | ((v.y & 0xFFFFu) << 16);
                outRaw[((y + 1) * width + x) >> 1] = (v.z & 0xFFFFu) | ((v.w & 0xFFFFu) << 16);
            }
        """
    }
}
