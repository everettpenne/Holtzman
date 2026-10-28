package com.everettpenne.ix

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Runs the merge engine on real bursts saved by the camera's dataset mode, to measure instead of guess:
 * `./gradlew :ix:harness -Pdata=/path/to/scenes`, where each scene folder holds `frame_N.raw` (tight little-endian 16-bit mosaics) and `params.json`
 * (see tools/prototypes/dng_to_raw.py). For each scene: how far the tiles had to move to align, how many frames each cell took, and the noise of
 * the merged picture against the reference frame, for several frame-weight rules, so a rule is judged on handheld frames and not only on synthetic ones.
 */
fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: error("pass the folder holding the scene folders"))
    println("%-34s %4s %6s | %-28s | %-28s | %-28s | %-28s".format("scene", "ISO", "shake", "weights 0/3 (the old rule)", "weights 2.5/4.5 (shipped)", "weights 3.5/6 (lenient)", "camera noise profile, 2.5/4.5"))
    for (dir in root.listFiles { f -> File(f, "params.json").exists() }!!.sortedBy { it.name }) {
        val p = parse(File(dir, "params.json").readText())
        val w = p.getValue("width").toInt(); val h = p.getValue("height").toInt(); val n = p.getValue("frames").toInt()
        val black = IntArray(4) { Math.round(p.getValue("black$it")).toInt() }
        val planes = (0 until n).map { i ->
            val bytes = File(dir, "frame_$i.raw").readBytes()
            IxPlane(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(), w, h)
        }
        val iso = p.getValue("iso").toInt()
        val ref = IxBurst.sharpest(planes)
        val cells = StringBuilder()
        val white = p.getValue("white").toFloat() - black.average().toFloat()
        // the camera's own noise model for this frame: variance = S * signal + O in units where the sensor's range is 1; averaged over the colour channels
        val profile = p["np"]?.let { it }
        for ((variant, fullNone) in listOf(0 to (0f to 3f), 1 to (2.5f to 4.5f), 2 to (3.5f to 6f), 3 to (2.5f to 4.5f))) {
            val (full, none) = fullNone
            val base = if (variant == 3 && profile != null) IxMergeParams(shot = (p.getValue("npS") * white).toFloat(), read = sqrt(p.getValue("npO") * white * white).toFloat()) else mergeParamsForIso(iso)
            val params = IxMergeParams(base.shot, base.read, full, none)
            val t0 = System.nanoTime()
            val merged = IxBurst.merge(planes, ref, black, params)
            val ms = (System.nanoTime() - t0) / 1_000_000
            val refNoise = noise(planes[ref], w, h); val mergedNoise = noise(IxPlane(java.nio.ShortBuffer.wrap(merged.samples), w, h), w, h)
            cells.append(" | %-28s".format("%.2f frames, noise /%.2f, %dms".format(merged.meanFrames, refNoise / mergedNoise, ms)))
        }
        // alignment: how far the tiles of each other frame had to move (in 2x2 cells)
        var moved = 0; var total = 0; var far = 0; var sum = 0.0
        for (i in 0 until n) if (i != ref) {
            val f = IxBurst.align(planes[ref], planes[i], black, IxMergeParams())
            for (k in f.dx.indices) { val d = abs(f.dx[k]) + abs(f.dy[k]); total++; if (d > 0) moved++; if (d >= 12) far++; sum += sqrt((f.dx[k] * f.dx[k] + f.dy[k] * f.dy[k]).toDouble()) }
        }
        println("%-34s %4d %6s%s | tiles moved %.0f%%, mean shift %.2f cells, far %.1f%%".format(dir.name.removePrefix("scene_"), iso, p["shake"]?.let { "%.3f".format(it) } ?: "-", cells, 100.0 * moved / total, sum / total, 100.0 * far / total))
    }
}

/** A robust noise level of a mosaic: the median absolute response of Immerkaer's filter on the green samples of one row parity. */
private fun noise(p: IxPlane, w: Int, h: Int): Double {
    val rows = ShortArray(w * 3); val vals = ArrayList<Float>()
    var y = 2
    while (y < h - 4) {
        // green samples on rows y (odd columns) of an RGGB mosaic: step by 2 rows to stay on one parity
        val r0 = ShortArray(w); val r1 = ShortArray(w); val r2 = ShortArray(w)
        p.readRows(y, 1, r0); p.readRows(y + 2, 1, r1); p.readRows(y + 4, 1, r2)
        var x = 3
        while (x < w - 4) {
            fun v(r: ShortArray, xx: Int) = (r[xx].toInt() and 0xFFFF).toFloat()
            val c = v(r0, x - 2) - 2 * v(r0, x) + v(r0, x + 2) - 2 * v(r1, x - 2) + 4 * v(r1, x) - 2 * v(r1, x + 2) + v(r2, x - 2) - 2 * v(r2, x) + v(r2, x + 2)
            if (vals.size < 600_000) vals.add(abs(c))
            x += 12
        }
        y += 8
    }
    vals.sort()
    return vals[vals.size / 2] / 4.047
}

private fun parse(json: String): Map<String, Double> {
    val out = HashMap<String, Double>()
    val o = org.json.JSONObject(json)
    for (k in listOf("width", "height", "frames", "white", "iso", "exposureNs")) out[k] = o.getDouble(k)
    val b = o.getJSONArray("black"); for (i in 0 until 4) out["black$i"] = b.getDouble(i)
    if (!o.isNull("shake")) out["shake"] = o.getDouble("shake")
    if (!o.isNull("noiseProfile")) {
        val np = o.getJSONArray("noiseProfile"); var s = 0.0; var q = 0.0
        for (i in 0 until np.length()) { s += np.getJSONArray(i).getDouble(0); q += np.getJSONArray(i).getDouble(1) }
        out["np"] = 1.0; out["npS"] = s / np.length(); out["npO"] = q / np.length()
    }
    return out
}
