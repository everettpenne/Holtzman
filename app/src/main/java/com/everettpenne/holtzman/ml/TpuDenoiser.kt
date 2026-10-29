package com.everettpenne.holtzman.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The learned raw denoiser (tools/denoiser), run on the Tensor G4's TPU through NNAPI. It takes the merged mosaic, cuts it into overlapping tiles of
 * [TILE] x [TILE] cells (a cell is a 2x2 group of sensor samples, so four planes), feeds each tile with a noise-level channel worked out from the
 * sensor's measured noise model and the number of frames the merge combined, and keeps each tile's centre. Returns null from [create] when the model or
 * the TPU driver is not there, so the caller falls back to the classical denoise.
 */
class TpuDenoiser private constructor(private val interpreter: Interpreter, private val delegate: NnApiDelegate) : AutoCloseable {
    private val input = ByteBuffer.allocateDirect(TILE * TILE * CHANNELS * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(TILE * TILE * 4 * 4).order(ByteOrder.nativeOrder())
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(WORKERS)

    /** One tile's work: where it is, which part of its result is kept, and its input and output as plain arrays (so they move to and from the TPU's buffers in bulk). */
    private class Buf { val features = FloatArray(TILE * TILE * CHANNELS); val result = FloatArray(TILE * TILE * 4) }
    private class Job(val y0: Int, val x0: Int, val keepTop: Int, val keepLeft: Int, val keepBottom: Int, val keepRight: Int) {
        lateinit var buf: Buf
        val features get() = buf.features
        val result get() = buf.result
    }
    /** The tiles' working arrays, a few reused for the whole picture (a tile owning its own would be 63 x 3.5 MB on the heap for every shot). */
    private val buffers = java.util.concurrent.ArrayBlockingQueue<Buf>(BUFFERS).also { q -> repeat(BUFFERS) { q.add(Buf()) } }
    private val writers = java.util.concurrent.Executors.newFixedThreadPool(WRITERS)

    private class Scratch {
        val gathered = Array(4) { FloatArray((TILE + 4) * (TILE + 4)) }
        val rowSum = FloatArray((TILE + 4) * TILE)
        val blur = Array(4) { FloatArray(TILE * TILE) }
    }
    private val scratch = ThreadLocal.withInitial { Scratch() }

    /**
     * [mosaic] is the merged [width] x [height] mosaic in sensor counts (black level still in it, plane order as the sensor's 2x2 pattern: index
     * (column and 1) + 2 * (row and 1)); [shot] and [readVariance] are the sensor's noise model per sample and [effectiveFrames] the frames the merge
     * averaged. Returns the denoised mosaic in the same units.
     *
     * What the network sees (the same as tools/denoiser/data.py `features`), per cell and plane: the plane's fine detail in units of the local noise (the
     * plane minus its 5x5 mean, over the noise level, clipped to +-8), the plane itself, and 100 x the noise level. It answers with a correction in units of
     * the noise level, which is added to the plane after scaling by it. The tiles are prepared and written back on [WORKERS] threads; the TPU takes them one at a time.
     */
    @Synchronized fun denoise(mosaic: ShortArray, width: Int, height: Int, black: IntArray, white: Int, shot: Float, readVariance: Float, effectiveFrames: Float): ShortArray {
        val cw = width / 2; val ch = height / 2
        val t0 = System.nanoTime()
        val range = FloatArray(4) { (white - black[it]).toFloat() }
        val mean = range.average().toFloat()
        val planes = Array(4) { FloatArray(cw * ch) }
        val sigma = FloatArray(cw * ch)
        // the planes and the noise level, one band of rows per thread
        runAll((0 until WORKERS).map { w -> {
            val rows = (ch + WORKERS - 1) / WORKERS
            for (cy in w * rows until min(ch, (w + 1) * rows)) for (cx in 0 until cw) {
                var s = 0f
                for (k in 0 until 4) {
                    val counts = (mosaic[(cy * 2 + (k shr 1)) * width + cx * 2 + (k and 1)].toInt() and 0xFFFF) - black[k]
                    planes[k][cy * cw + cx] = counts / range[k]
                    s += sqrt((shot * max(counts, 0) + readVariance) / effectiveFrames) / mean
                }
                sigma[cy * cw + cx] = s / 4f
            }
        } })
        val t1 = System.nanoTime()
        val result = mosaic.copyOf()
        val step = TILE - 2 * PAD
        val jobs = ArrayList<Job>()
        var ty = 0
        while (ty < ch) {
            var tx = 0
            while (tx < cw) {
                // a tile starts at (ty, tx) in cells, pulled back so it never runs off the picture; its centre is kept except at the picture's own edge
                val y0 = min(ty, max(ch - TILE, 0)); val x0 = min(tx, max(cw - TILE, 0))
                // the last tile of a row or column is pulled back, so it would also cover cells its neighbour keeps; it keeps only from where the neighbour's
                // share ends (ty + PAD), so no two tiles write the same cell and the order they finish in cannot change the picture
                jobs += Job(y0, x0, if (y0 == 0) 0 else ty + PAD - y0, if (x0 == 0) 0 else tx + PAD - x0, if (y0 + TILE >= ch) TILE else TILE - PAD, if (x0 + TILE >= cw) TILE else TILE - PAD)
                tx += step
            }
            ty += step
        }
        // A pipeline: [WORKERS] threads build tiles' inputs, this thread feeds the TPU as they arrive (it takes them one at a time), and [WRITERS]
        // threads write the answers back, so the TPU is never waiting for the CPU nor the CPU for the TPU. Each tile borrows one of [BUFFERS] arrays
        // from build to write-back, which also bounds how far the builders run ahead.
        val ready = java.util.concurrent.LinkedBlockingQueue<Job>()
        val failed = Job(0, 0, 0, 0, 0, 0)   // put on the queue by a builder that threw (a queue cannot hold null)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val builders = jobs.map { j -> pool.submit {
            try { j.buf = buffers.take(); buildFeatures(j, planes, sigma, cw, ch); ready.put(j) }
            catch (t: InterruptedException) { }
            catch (t: Throwable) { failure.compareAndSet(null, t); ready.put(failed) }
        } }
        val writes = ArrayList<java.util.concurrent.Future<*>>(jobs.size)
        var tRun = 0L
        try {
            repeat(jobs.size) {
                val j = ready.poll(20, java.util.concurrent.TimeUnit.SECONDS) ?: throw java.io.IOException("the denoiser stalled")
                if (j === failed) throw (failure.get() ?: java.io.IOException("a tile failed"))
                val a = System.nanoTime()
                // fresh views each time: the runtime moves the buffers' positions, and a view made from a buffer left at its end would be empty
                input.rewind(); input.asFloatBuffer().put(j.features); input.rewind(); output.rewind()
                interpreter.run(input, output)
                output.rewind(); output.asFloatBuffer().get(j.result)
                tRun += System.nanoTime() - a
                writes += writers.submit { try { writeBack(j, planes, sigma, result, range, black, width, cw, ch) } finally { buffers.put(j.buf) } }
            }
            writes.forEach { it.get() }
        } catch (t: Throwable) {
            builders.forEach { it.cancel(true) }; writes.forEach { it.cancel(true) }
            // tiles that were in flight still hold buffers; start the next picture with a full set
            buffers.clear(); repeat(BUFFERS) { buffers.add(Buf()) }
            throw t
        }
        if (com.everettpenne.holtzman.save.DebugFlags.on("checkdenoise")) checkAgainstSerial(jobs, planes, sigma, cw, ch, result, range, black, width)
        android.util.Log.i("HoltzmanTiming", "denoise: planes ${(t1 - t0) / 1_000_000} ms, TPU busy ${tRun / 1_000_000} ms, whole ${(System.nanoTime() - t0) / 1_000_000} ms, ${jobs.size} tiles")
        return result
    }

    /**
     * Debug (switch `checkdenoise`): redoes every tile one at a time with one buffer, twice, and logs how the pipelined answer differs from the first
     * serial one and how the two serial ones differ from each other (the TPU computes in half precision, so identical input may not give identical output).
     */
    private fun checkAgainstSerial(jobs: List<Job>, planes: Array<FloatArray>, sigma: FloatArray, cw: Int, ch: Int, got: ShortArray, range: FloatArray, black: IntArray, width: Int) {
        val buf = Buf()
        fun serial(): ShortArray {
            val out = got.copyOf()
            for (j in jobs) {
                j.buf = buf; buildFeatures(j, planes, sigma, cw, ch)
                input.rewind(); input.asFloatBuffer().put(j.features); input.rewind(); output.rewind()
                interpreter.run(input, output)
                output.rewind(); output.asFloatBuffer().get(j.result)
                writeBack(j, planes, sigma, out, range, black, width, cw, ch)
            }
            return out
        }
        fun compare(a: ShortArray, b: ShortArray): String {
            var worst = 0; var differ = 0
            for (i in a.indices) { val d = kotlin.math.abs((a[i].toInt() and 0xFFFF) - (b[i].toInt() and 0xFFFF)); if (d != 0) { differ++; if (d > worst) worst = d } }
            return "$differ of ${a.size} samples differ, largest difference $worst"
        }
        val one = serial(); val two = serial()
        Log.i("HoltzmanTiming", "denoise check: pipelined vs serial: ${compare(got, one)}; serial vs serial again: ${compare(one, two)}")
    }

    private fun runAll(tasks: List<() -> Unit>) { pool.invokeAll(tasks.map { t -> java.util.concurrent.Callable { t() } }).forEach { it.get() } }

    private fun buildFeatures(j: Job, planes: Array<FloatArray>, sigma: FloatArray, cw: Int, ch: Int) {
        val sc = scratch.get()!!
        val ext = TILE + 4
        // the tile and 2 cells of context all round, edges replicated
        for (k in 0 until 4) { val g = sc.gathered[k]; val p = planes[k]
            for (jj in 0 until ext) { val yy = (j.y0 + jj - 2).coerceIn(0, ch - 1); val rowBase = yy * cw
                for (i in 0 until ext) g[jj * ext + i] = p[rowBase + (j.x0 + i - 2).coerceIn(0, cw - 1)] } }
        // the 5x5 mean of each plane over the tile: a running sum along rows, then along columns
        for (k in 0 until 4) { val g = sc.gathered[k]; val bl = sc.blur[k]; val rs = sc.rowSum
            for (jj in 0 until ext) { var acc = g[jj * ext] + g[jj * ext + 1] + g[jj * ext + 2] + g[jj * ext + 3] + g[jj * ext + 4]
                for (i in 0 until TILE) { rs[jj * TILE + i] = acc; if (i + 5 < ext) acc += g[jj * ext + i + 5] - g[jj * ext + i] } }
            for (i in 0 until TILE) { var acc = rs[i] + rs[TILE + i] + rs[2 * TILE + i] + rs[3 * TILE + i] + rs[4 * TILE + i]
                for (jj in 0 until TILE) { bl[jj * TILE + i] = acc / 25f; if (jj + 5 < ext) acc += rs[(jj + 5) * TILE + i] - rs[jj * TILE + i] } } }
        val f = j.features
        for (jj in 0 until TILE) { val yy = min(j.y0 + jj, ch - 1)
            for (i in 0 until TILE) { val xx = min(j.x0 + i, cw - 1); val c = yy * cw + xx; val sg = sigma[c] + 1e-6f; val o = (jj * TILE + i) * CHANNELS
                for (k in 0 until 4) { f[o + k] = ((sc.gathered[k][(jj + 2) * ext + i + 2] - sc.blur[k][jj * TILE + i]) / sg).coerceIn(-8f, 8f); f[o + 4 + k] = planes[k][c] }
                f[o + 8] = 100f * sigma[c] } }
    }

    private fun writeBack(j: Job, planes: Array<FloatArray>, sigma: FloatArray, result: ShortArray, range: FloatArray, black: IntArray, width: Int, cw: Int, ch: Int) {
        for (jj in j.keepTop until j.keepBottom) { val yy = j.y0 + jj; if (yy >= ch) break
            for (i in j.keepLeft until j.keepRight) { val xx = j.x0 + i; if (xx >= cw) break
                val o = (jj * TILE + i) * 4; val c = yy * cw + xx
                for (k in 0 until 4) {
                    val v = (planes[k][c] + sigma[c] * j.result[o + k]) * range[k] + black[k] + 0.5f
                    result[(yy * 2 + (k shr 1)) * width + xx * 2 + (k and 1)] = v.toInt().coerceIn(0, 65535).toShort()
                } } }
    }

    override fun close() { runCatching { pool.shutdownNow() }; runCatching { writers.shutdownNow() }; runCatching { interpreter.close() }; runCatching { delegate.close() } }

    companion object {
        private var shared: TpuDenoiser? = null
        private var tried = false

        /**
         * One denoiser kept loaded for the life of the app (building the TPU's compiled model takes about half a second, too much to pay on every shot);
         * null if there is none on this phone. Call it early, off the main thread, to warm it up.
         */
        @Synchronized fun shared(context: Context): TpuDenoiser? {
            if (!tried) { tried = true; shared = create(context.applicationContext) }
            return shared
        }

        const val TILE = 256
        const val CHANNELS = 9
        private const val WORKERS = 4
        private const val WRITERS = 2
        private const val BUFFERS = 10
        const val PAD = 16
        private const val MODEL = "denoiser_int8.tflite"
        private const val TAG = "HoltzmanTpu"

        /** The denoiser on the TPU, or null (no model in the app, no TPU driver, the delegate refused): then the classical denoise runs. */
        fun create(context: Context): TpuDenoiser? = try {
            val bytes = context.assets.open(MODEL).readBytes()
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
            val delegate = NnApiDelegate(NnApiDelegate.Options().setAcceleratorName("google-edgetpu").setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED).setAllowFp16(true).setUseNnapiCpu(false))
            val interpreter = Interpreter(model, Interpreter.Options().addDelegate(delegate))
            TpuDenoiser(interpreter, delegate)
        } catch (t: Throwable) { Log.i(TAG, "no TPU denoiser: ${t.javaClass.simpleName} ${t.message?.take(120)}"); null }
    }
}
