package com.everettpenne.holtzman.save

import android.util.Log
import com.everettpenne.ix.IxBurst
import com.everettpenne.ix.IxPlane
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The part of a burst's processing that does not depend on which frame becomes the reference, done while the burst is still being captured: the GL
 * context and shaders, each frame's upload to the GPU, and each frame's sharpness score. The merge then starts with all of it ready, and the reference
 * is picked by the same rule as before (the sharpest, the first of equals), so the picture is the same.
 *
 * Every method runs on the one burst-processing thread, in order: [frame] for each frame as it arrives, then [BurstProcessor.process], which takes over
 * the context (and closes it); [close] if the capture fails first. A failure here only means the processing does its own setup, as it used to.
 */
class BurstPrep : AutoCloseable {
    var gl: GlContext? = null; private set
    var engine: GpuMerge? = null; private set
    val uploaded = ArrayList<Int>()
    private val scores = ArrayList<Long>()
    private var failed = false

    /** Whether every frame so far is uploaded and scored, so the processing can use them. */
    fun usable(expectedFrames: Int): Boolean = !failed && gl != null && uploaded.size == expectedFrames && scores.size == expectedFrames

    /** The reference frame: the same choice [IxBurst.sharpest] would make from the whole burst. */
    fun reference(): Int = IxBurst.sharpestOf(scores)

    fun frame(pixels: ByteBuffer, width: Int, height: Int) {
        if (failed) return
        try {
            val t = System.nanoTime()
            if (gl == null) gl = GlContext().also { engine = GpuMerge(it) }
            uploaded += gl!!.buffer(width * height * 2, pixels)
            scores += IxBurst.sharpness(IxPlane(pixels.order(ByteOrder.nativeOrder()).asShortBuffer(), width, height))
            Log.i("HoltzmanTiming", "frame ${uploaded.size} prepared in ${(System.nanoTime() - t) / 1_000_000} ms")
        } catch (t: Throwable) {
            Log.w("HoltzmanBurst", "preparing the burst while it was captured failed, the processing will do it: $t")
            failed = true; close()
        }
    }

    override fun close() {
        val g = gl ?: return
        runCatching { if (uploaded.isNotEmpty()) g.delete(*uploaded.toIntArray()) }
        g.close(); gl = null; engine = null
    }
}
