package com.everettpenne.ix

import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A RAW frame as the camera delivers it: 16-bit samples of a Bayer mosaic, [rowStride] samples per row. The buffer is read in place (absolute gets),
 * never copied: a burst of 12-megapixel frames would not fit in the app's heap as arrays.
 */
public class IxPlane(public val samples: ShortBuffer, public val width: Int, public val height: Int, public val rowStride: Int = width) {
    init {
        require(width >= 2 && height >= 2 && width % 2 == 0 && height % 2 == 0) { "a Bayer frame needs even dimensions, was ${width}x$height" }
        require(rowStride >= width) { "rowStride $rowStride is narrower than width $width" }
        require(samples.capacity() >= rowStride * (height - 1) + width) { "buffer holds ${samples.capacity()} samples, needs ${rowStride * (height - 1) + width}" }
    }

    private val view: ShortBuffer = samples.duplicate()

    /**
     * Copies rows [y0] until [y0] + [count] into [dst] (tight rows, [width] samples each). A bulk copy is orders of magnitude faster than reading a
     * camera buffer sample by sample on ART (a 12 MP frame took 20 s to scan that way), so everything reads in bands. Not thread-safe.
     */
    public fun readRows(y0: Int, count: Int, dst: ShortArray) {
        for (r in 0 until count) { view.position((y0 + r) * rowStride); view.get(dst, r * width, width) }
    }
}

/** A band of rows of a frame held in an array, for fast random access inside the band. */
internal class IxBand(val width: Int, maxRows: Int) {
    val data = ShortArray(width * maxRows)
    var y0 = 0
    var rows = 0
    fun load(p: IxPlane, from: Int, to: Int) { y0 = from.coerceAtLeast(0); rows = (to.coerceAtMost(p.height) - y0).coerceAtLeast(0); p.readRows(y0, rows, data) }
    fun at(x: Int, y: Int): Int = data[(y - y0) * width + x].toInt() and 0xFFFF
}

/** How far each tile of an alternate frame moved relative to the reference, in 2x2 Bayer cells (so always a whole, parity-preserving 2 sensor pixels). */
public class IxFlow(public val tilesX: Int, public val tilesY: Int, public val tileCells: Int, public val dx: IntArray, public val dy: IntArray)

public class IxMergeParams(
    /** Noise model of the RAW values: variance = [shot] * signal + [read]^2, in squared sensor counts. The defaults suit about ISO 860; [forIso] gives the Pixel 10a's back camera at any ISO. */
    public val shot: Float = 1.5f,
    public val read: Float = 3f,
    /**
     * How a frame's weight in a cell falls with how far it differs from the reference, in noise standard deviations: full weight up to [fullTrust] (so
     * ordinary noise never costs a frame its say), none beyond [noTrust] (something moved), smoothly between. A weight that already dropped at one
     * deviation (the earlier rule) cost a full dB of the merge's noise reduction on a still scene; checked on a host: this rule matches plain
     * averaging on still frames and keeps the rejection of a moving patch.
     */
    public val fullTrust: Float = 2.5f,
    public val noTrust: Float = 4.5f,
    public val tileCells: Int = 16,
    /** Search distance in cells at half resolution (the coarse pass looks a quarter of this far at quarter resolution). */
    public val searchCells: Int = 8,
    /** Progress and timing lines (the caller routes them to a log; the engine has no Android dependency). */
    public val log: (String) -> Unit = {},
)

/**
 * The noise model of the Pixel 10a back camera at [iso], from two-frame photon-transfer measurements at ISO 99 to 3194 (docs/ix-pipeline.md): shot-noise
 * gain 0.0017 per ISO unit (measured 0.00165 to 0.00185 across the range) and read variance about 1 + 5.2e-6 * iso^2 (measured 0.7 to 53).
 * Another sensor needs its own measurement.
 */
public fun mergeParamsForIso(iso: Int, log: (String) -> Unit = {}): IxMergeParams =
    IxMergeParams(shot = 0.0017f * iso, read = kotlin.math.sqrt(1f + 5.2e-6f * iso * iso), log = log)

public class IxMerged(
    public val samples: ShortArray,
    public val width: Int,
    public val height: Int,
    /** Mean number of frames contributing per cell, including the reference (1 = nothing merged, [frames] = everything). */
    public val meanFrames: Float,
)

public object IxBurst {
    /** Half-resolution brightness of a frame: one value per 2x2 Bayer cell, the black-subtracted sum of its four samples. */
    internal fun cellGray(p: IxPlane, black: IntArray): IntArray {
        val cw = p.width / 2; val ch = p.height / 2
        val out = IntArray(cw * ch)
        val rows = ShortArray(p.width * 2)
        val w = p.width
        for (cy in 0 until ch) {
            p.readRows(cy * 2, 2, rows)
            for (cx in 0 until cw) {
                val x = cx * 2
                out[cy * cw + cx] = ((rows[x].toInt() and 0xFFFF) - black[0]) + ((rows[x + 1].toInt() and 0xFFFF) - black[1]) +
                    ((rows[w + x].toInt() and 0xFFFF) - black[2]) + ((rows[w + x + 1].toInt() and 0xFFFF) - black[3])
            }
        }
        return out
    }

    private fun halve(src: IntArray, w: Int, h: Int): IntArray {
        val nw = w / 2; val nh = h / 2
        return IntArray(nw * nh) { i -> val x = (i % nw) * 2; val y = (i / nw) * 2; (src[y * w + x] + src[y * w + x + 1] + src[(y + 1) * w + x] + src[(y + 1) * w + x + 1]) / 4 }
    }

    private fun sad(a: IntArray, b: IntArray, w: Int, h: Int, ax: Int, ay: Int, bx: Int, by: Int, size: Int, stopAbove: Long): Long {
        var sum = 0L
        for (j in 0 until size) {
            val ya = ay + j; val yb = by + j
            if (ya !in 0 until h || yb !in 0 until h) { sum += size.toLong() * 4096; continue }
            for (i in 0 until size) {
                val xa = ax + i; val xb = bx + i
                sum += if (xa in 0 until w && xb in 0 until w) abs(a[ya * w + xa] - b[yb * w + xb]).toLong() else 4096
            }
            if (sum > stopAbove) return sum
        }
        return sum
    }

    /** Half-, quarter- and eighth-resolution brightness of a frame (one value per 2x2, 4x4 and 8x8 sensor pixels), built once and reused. */
    internal class Pyramid(val l1: IntArray, val l2: IntArray, val l3: IntArray, val w1: Int, val h1: Int)

    internal fun pyramid(p: IxPlane, black: IntArray): Pyramid {
        val cw = p.width / 2; val ch = p.height / 2
        val l1 = cellGray(p, black)
        val l2 = halve(l1, cw, ch)
        val l3 = halve(l2, cw / 2, ch / 2)
        return Pyramid(l1, l2, l3, cw, ch)
    }

    /**
     * Finds, for each tile of the reference, where the same picture is in [alt]: a three-level search (eighth, quarter, half resolution), each level
     * refining the one above by a cell either way. A small penalty on distance prefers "did not move" when the evidence is a tie (flat areas), which is
     * where a wrong guess would smear. [refPyramid] can be passed when aligning several frames to one reference.
     */
    public fun align(reference: IxPlane, alt: IxPlane, black: IntArray, params: IxMergeParams = IxMergeParams()): IxFlow =
        align(pyramid(reference, black), alt, black, params)

    internal fun align(ref: Pyramid, alt: IxPlane, black: IntArray, params: IxMergeParams): IxFlow {
        require(black.size == 4) { "need 4 black levels" }
        val a = pyramid(alt, black)
        val cw = ref.w1; val ch = ref.h1
        val t = params.tileCells
        val tx = (cw + t - 1) / t; val ty = (ch + t - 1) / t
        val dx = IntArray(tx * ty); val dy = IntArray(tx * ty)
        val w2 = cw / 2; val h2 = ch / 2; val w3 = w2 / 2; val h3 = h2 / 2
        val t2 = max(1, t / 2); val t3 = max(1, t / 4)
        val range3 = max(1, params.searchCells / 2)          // at eighth resolution: this many cells is 4x as many sensor pixels each
        for (j in 0 until ty) for (i in 0 until tx) {
            var best = Long.MAX_VALUE; var mx = 0; var my = 0
            for (sy in -range3..range3) for (sx in -range3..range3) {
                val c = sad(ref.l3, a.l3, w3, h3, i * t3, j * t3, i * t3 + sx, j * t3 + sy, t3, best) + (abs(sx) + abs(sy)) * t3 * 4L
                if (c < best) { best = c; mx = sx; my = sy }
            }
            // quarter resolution: twice the coarse answer, plus or minus one
            var bx = mx * 2; var by = my * 2; best = Long.MAX_VALUE
            for (sy in -1..1) for (sx in -1..1) {
                val tx2 = mx * 2 + sx; val ty2 = my * 2 + sy
                val c = sad(ref.l2, a.l2, w2, h2, i * t2, j * t2, i * t2 + tx2, j * t2 + ty2, t2, best) + (abs(tx2) + abs(ty2)) * t2 * 2L
                if (c < best) { best = c; bx = tx2; by = ty2 }
            }
            // half resolution
            var fx = bx * 2; var fy = by * 2; best = Long.MAX_VALUE
            for (sy in -1..1) for (sx in -1..1) {
                val tx1 = bx * 2 + sx; val ty1 = by * 2 + sy
                val c = sad(ref.l1, a.l1, cw, ch, i * t, j * t, i * t + tx1, j * t + ty1, t, best) + (abs(tx1) + abs(ty1)) * t * 2L
                if (c < best) { best = c; fx = tx1; fy = ty1 }
            }
            dx[j * tx + i] = fx; dy[j * tx + i] = fy
        }
        return IxFlow(tx, ty, t, dx, dy)
    }

    /**
     * Merges [frames] into one, around [reference] (the sharpest, or the middle one: the caller chooses). Every other frame is aligned tile by tile,
     * then each Bayer cell of the reference is replaced by the weighted mean of itself and the matching cell of every other frame. A frame's weight at
     * a cell falls to zero where it disagrees with the reference by more than the noise explains (something moved, or the alignment failed), so
     * moving objects keep the reference's single sample instead of ghosting. Values stay in the frames' own black level and scale.
     */
    public fun merge(frames: List<IxPlane>, reference: Int, black: IntArray, params: IxMergeParams = IxMergeParams()): IxMerged {
        require(frames.isNotEmpty()) { "no frames" }
        require(reference in frames.indices) { "reference $reference is not a frame" }
        val ref = frames[reference]
        val w = ref.width; val h = ref.height
        val out = ShortArray(w * h)
        val cw = w / 2; val ch = h / 2
        val tStart = System.nanoTime()
        val refPyramid = pyramid(ref, black)
        params.log("reference pyramid ${(System.nanoTime() - tStart) / 1_000_000} ms")
        val flows = frames.mapIndexed { i, f -> if (i == reference) null else align(refPyramid, f, black, params).also { params.log("aligned frame $i at ${(System.nanoTime() - tStart) / 1_000_000} ms") } }
        var contributors = 0L
        val t = params.tileCells
        val maxShift = max(1, params.searchCells / 2) * 4 + 8   // the three-level search can move a tile this many cells
        val refBand = IxBand(w, t * 2)
        val altBands = frames.map { IxBand(w, t * 2 + 4 * maxShift) }
        val tileRows = (ch + t - 1) / t
        for (tj in 0 until tileRows) {
            val cy0 = tj * t; val cy1 = minOf(cy0 + t, ch)
            if (tj % 20 == 0) params.log("merging tile row $tj of $tileRows at ${(System.nanoTime() - tStart) / 1_000_000} ms")
            refBand.load(ref, cy0 * 2, cy1 * 2)
            for ((f, flow) in flows.withIndex()) {
                if (flow == null) continue
                var lo = 0; var hi = 0
                for (ti in 0 until flow.tilesX) { val d = flow.dy[tj * flow.tilesX + ti]; if (d < lo) lo = d; if (d > hi) hi = d }
                altBands[f].load(frames[f], (cy0 + lo) * 2, (cy1 + hi) * 2)
            }
            for (cy in cy0 until cy1) for (cx in 0 until cw) {
                val x = cx * 2; val y = cy * 2
                val r0 = refBand.at(x, y); val r1 = refBand.at(x + 1, y); val r2 = refBand.at(x, y + 1); val r3 = refBand.at(x + 1, y + 1)
                val refSum = (r0 - black[0]) + (r1 - black[1]) + (r2 - black[2]) + (r3 - black[3])
                // variance of the difference of two cell sums: both frames contribute shot noise (4 samples) and read noise
                val varDiff = 2f * (params.shot * max(refSum, 0) + 4f * params.read * params.read)
                var a0 = r0.toFloat(); var a1 = r1.toFloat(); var a2 = r2.toFloat(); var a3 = r3.toFloat(); var wsum = 1f
                for (f in frames.indices) {
                    val flow = flows[f] ?: continue
                    val k = tj * flow.tilesX + (cx / t)
                    val ax = cx + flow.dx[k]; val ay = cy + flow.dy[k]
                    if (ax !in 0 until cw || ay !in 0 until ch) continue
                    val band = altBands[f]; val px = ax * 2; val py = ay * 2
                    val b0 = band.at(px, py); val b1 = band.at(px + 1, py); val b2 = band.at(px, py + 1); val b3 = band.at(px + 1, py + 1)
                    val diff = ((b0 - black[0]) + (b1 - black[1]) + (b2 - black[2]) + (b3 - black[3]) - refSum).toFloat()
                    val z = abs(diff) / sqrt(max(varDiff, 1e-6f))
                    val tt = ((params.noTrust - z) / (params.noTrust - params.fullTrust)).coerceIn(0f, 1f)
                    val wt = tt * tt * (3f - 2f * tt)
                    if (wt > 0f) { a0 += wt * b0; a1 += wt * b1; a2 += wt * b2; a3 += wt * b3; wsum += wt; contributors++ }
                }
                out[y * w + x] = (a0 / wsum + 0.5f).toInt().toShort(); out[y * w + x + 1] = (a1 / wsum + 0.5f).toInt().toShort()
                out[(y + 1) * w + x] = (a2 / wsum + 0.5f).toInt().toShort(); out[(y + 1) * w + x + 1] = (a3 / wsum + 0.5f).toInt().toShort()
            }
        }
        return IxMerged(out, w, h, 1f + contributors.toFloat() / (cw * ch))
    }

    /** Index of the frame with the most local contrast (the sharpest, least shaken): the best reference. Samples every 4th row and column. */
    public fun sharpest(frames: List<IxPlane>): Int {
        require(frames.isNotEmpty()) { "no frames" }
        var best = 0; var bestScore = -1L
        for ((i, f) in frames.withIndex()) {
            var score = 0L
            val a = ShortArray(f.width); val up = ShortArray(f.width)
            var y = 4
            while (y < f.height - 2) {
                f.readRows(y, 1, a); f.readRows(y - 2, 1, up)
                var x = 2
                while (x < f.width - 2) { val v = a[x].toInt() and 0xFFFF; score += abs(v - (a[x - 2].toInt() and 0xFFFF)) + abs(v - (up[x].toInt() and 0xFFFF)); x += 4 }
                y += 4
            }
            if (score > bestScore) { bestScore = score; best = i }
        }
        return best
    }

    /** Mean and standard deviation of a patch of a RAW frame (for tests and for reporting how much a merge helped). */
    public fun patchStats(samples: ShortArray, width: Int, x0: Int, y0: Int, size: Int): Pair<Double, Double> {
        var s = 0.0; var s2 = 0.0; val n = size * size
        for (y in y0 until y0 + size) for (x in x0 until x0 + size) { val v = (samples[y * width + x].toInt() and 0xFFFF).toDouble(); s += v; s2 += v * v }
        val m = s / n
        return m to sqrt(max(s2 / n - m * m, 0.0))
    }

    internal fun clampCells(v: Int, lo: Int, hi: Int): Int = min(max(v, lo), hi)
}
