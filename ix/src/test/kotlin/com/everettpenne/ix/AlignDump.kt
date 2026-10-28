package com.everettpenne.ix

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Puts every frame of each handheld burst into the reference frame's geometry (the sharpest frame; the same tile search the merge uses), and writes
 * aligned_N.raw (tight little-endian 16-bit) next to the frames, with reference.txt naming the reference. Frames that are then independent noisy views
 * of the same picture are what a learned denoiser is trained on (`tools/denoiser/`): `./gradlew :ix:alignDump -Pdata=/path/to/scenes`.
 */
fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: error("pass the folder holding the scene folders"))
    for (dir in root.listFiles { f -> File(f, "params.json").exists() && File(f, "frame_0.raw").exists() && !File(f, "aligned_0.raw").exists() && f.name.endsWith("_handheld") }!!.sortedBy { it.name }) {
        val o = org.json.JSONObject(File(dir, "params.json").readText())
        val w = o.getInt("width"); val h = o.getInt("height"); val n = o.getInt("frames")
        val bl = o.getJSONArray("black"); val black = IntArray(4) { Math.round(bl.getDouble(it)).toInt() }
        val frames = (0 until n).map { i -> ShortArray(w * h).also { ByteBuffer.wrap(File(dir, "frame_$i.raw").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) } }
        val planes = frames.map { IxPlane(java.nio.ShortBuffer.wrap(it), w, h) }
        val ref = IxBurst.sharpest(planes)
        val params = mergeParamsForIso(o.getInt("iso"))
        val cw = w / 2; val ch = h / 2
        for (i in 0 until n) {
            val out = ShortArray(w * h)
            if (i == ref) System.arraycopy(frames[i], 0, out, 0, out.size) else {
                val flow = IxBurst.align(planes[ref], planes[i], black, params)
                val t = flow.tileCells
                for (cy in 0 until ch) for (cx in 0 until cw) {
                    val k = (cy / t) * flow.tilesX + cx / t
                    var ax = cx + flow.dx[k]; var ay = cy + flow.dy[k]
                    if (ax !in 0 until cw || ay !in 0 until ch) { ax = cx; ay = cy }   // off the edge: the cell as it is (rare, near the border)
                    val s = frames[i]; val x = cx * 2; val y = cy * 2; val sx = ax * 2; val sy = ay * 2
                    out[y * w + x] = s[sy * w + sx]; out[y * w + x + 1] = s[sy * w + sx + 1]; out[(y + 1) * w + x] = s[(sy + 1) * w + sx]; out[(y + 1) * w + x + 1] = s[(sy + 1) * w + sx + 1]
                }
            }
            val bb = ByteBuffer.allocate(out.size * 2).order(ByteOrder.LITTLE_ENDIAN); bb.asShortBuffer().put(out)
            File(dir, "aligned_$i.raw").writeBytes(bb.array())
        }
        File(dir, "reference.txt").writeText(ref.toString())
        println("${dir.name}: aligned $n frames to frame $ref")
    }
}
