package com.everettpenne.ix

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The foreground subject from a relative depth map (MiDaS: larger = nearer), for autofocus when the segmentation sees none of its classes. The 20 PASCAL
 * classes left a water bottle, a circuit board, a flag and a device on a desk unrecognised, so focus went to the middle of the frame.
 *
 * Nearest alone is not a subject: the floor or the desk at the bottom of the frame is nearer than anything on it. What marks an object is that it stands out
 * from its surroundings in depth ("pop-out": depth minus a wide blur of depth), so that is scored, weighted toward nearer and toward the middle. Among the
 * patches above a share of the best score, the one with the most score wins, unless it is a floor (it runs across the bottom and gets steadily nearer toward
 * it) or a sliver cut by the frame's side. A scene with no clear pop-out (an open landscape) has no subject, and focus is left to the middle of the frame.
 * Prototyped on dataset scenes in Python (2026-11-02): the bottle, the flag, the device and the circuit board are found; the open outdoor scenes give none.
 */
public object IxDepthSubject {
    private const val GRID = 64
    /** Below this best score nothing stands out enough to be a subject. */
    public const val MIN_SCORE: Float = 0.12f

    /** The subject of depth map [depth] ([w] x [h], row by row, any scale; larger = nearer), as a subject labelled 255 ("object"), or null. */
    public fun find(depth: FloatArray, w: Int, h: Int): IxSubject.Subject? {
        // to a 64 x 64 grid by area averaging, normalised to 0..1
        val d = FloatArray(GRID * GRID); val n = IntArray(GRID * GRID)
        for (y in 0 until h) for (x in 0 until w) { val i = (y * GRID / h) * GRID + x * GRID / w; d[i] += depth[y * w + x]; n[i]++ }
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (i in d.indices) { if (n[i] > 0) d[i] /= n[i]; lo = min(lo, d[i]); hi = max(hi, d[i]) }
        if (hi - lo < 1e-6f) return null
        for (i in d.indices) d[i] = (d[i] - lo) / (hi - lo)
        val wide = blur(d, 10f)
        val s = FloatArray(GRID * GRID); var top = 0f
        for (y in 0 until GRID) for (x in 0 until GRID) {
            val i = y * GRID + x
            val dx = (x + 0.5f) / GRID - 0.5f; val dy = (y + 0.5f) / GRID - 0.5f
            s[i] = max(d[i] - wide[i], 0f) * (0.4f + 0.6f * d[i]) * (1f - 1.6f * (dx * dx + dy * dy))
            top = max(top, s[i])
        }
        if (top < MIN_SCORE) return null
        val seen = BooleanArray(GRID * GRID); val stack = IntArray(GRID * GRID)
        var best: IxSubject.Subject? = null; var bestScore = 0f
        for (start in 0 until GRID * GRID) {
            if (seen[start] || s[start] < 0.3f * top) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var cnt = 0; var sum = 0f; var x0 = GRID; var y0 = GRID; var x1 = -1; var y1 = -1
            var sy = 0f; var sd = 0f; var syy = 0f; var syd = 0f   // for depth against height: is it a floor?
            while (sp > 0) {
                val i = stack[--sp]; val x = i % GRID; val y = i / GRID
                cnt++; sum += s[i]; x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                val fy = y.toFloat() / GRID; sy += fy; sd += d[i]; syy += fy * fy; syd += fy * d[i]
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < GRID - 1) i + 1 else -1, if (y > 0) i - GRID else -1, if (y < GRID - 1) i + GRID else -1))
                    if (j >= 0 && !seen[j] && s[j] >= 0.3f * top) { seen[j] = true; stack[sp++] = j }
            }
            val bw = x1 - x0 + 1; val bh = y1 - y0 + 1
            if (cnt < 0.01f * GRID * GRID) continue
            if (y1 == GRID - 1 && bw > 0.4f * GRID) {
                val varY = syy / cnt - (sy / cnt) * (sy / cnt)
                val slope = if (varY > 1e-6f) (syd / cnt - sy / cnt * sd / cnt) / varY else 0f
                if (slope * bh / GRID > 0.2f) continue   // a floor or a desk: it gets steadily nearer toward the bottom
            }
            if ((x0 == 0 || x1 == GRID - 1) && bw < 0.15f * GRID) continue   // a sliver cut by the frame's side
            if (sum > bestScore) { bestScore = sum; best = IxSubject.Subject(255, x0.toFloat() / GRID, y0.toFloat() / GRID, (x1 + 1f) / GRID, (y1 + 1f) / GRID, cnt.toFloat() / (GRID * GRID)) }
        }
        return best
    }

    /** A Gaussian blur of a [GRID] x [GRID] map with standard deviation [sigma] cells, edges clamped (separable). */
    private fun blur(v: FloatArray, sigma: Float): FloatArray {
        val r = (3 * sigma).toInt(); val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }; val ks = k.sum()
        val tmp = FloatArray(v.size); val out = FloatArray(v.size)
        for (y in 0 until GRID) for (x in 0 until GRID) { var a = 0f; for (t in -r..r) a += k[t + r] * v[y * GRID + (x + t).coerceIn(0, GRID - 1)]; tmp[y * GRID + x] = a / ks }
        for (y in 0 until GRID) for (x in 0 until GRID) { var a = 0f; for (t in -r..r) a += k[t + r] * tmp[(y + t).coerceIn(0, GRID - 1) * GRID + x]; out[y * GRID + x] = a / ks }
        return out
    }
}
