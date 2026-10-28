package com.everettpenne.holtzman.save

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.net.Uri
import android.util.Log
import android.util.Size
import com.everettpenne.holtzman.camera.BurstFrame
import com.everettpenne.holtzman.camera.captureBaseName
import com.everettpenne.ix.IxBurst
import com.everettpenne.ix.IxMerged
import com.everettpenne.ix.IxPlane
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TimeZone

/** What a burst produced: the merged DNG, the unmerged reference frame as a DNG (for judging the merge), and how long and how well it went. */
class BurstOutcome(val merged: Uri, val reference: Uri, val mergedJpeg: Uri, val referenceJpeg: Uri, val millis: Long, val meanFrames: Float, val frames: Int)

/**
 * Stage 1 and 2 of the pipeline, end to end: take the burst's RAW frames, pick the sharpest as the reference, align and merge the rest in the RAW
 * domain (Ix), and write the merged frame as a DNG with the reference frame's metadata (so any RAW editor can develop it), next to the reference
 * frame on its own.
 */
class BurstProcessor(private val saver: PhotoSaver) {
    fun process(frames: List<BurstFrame>, characteristics: CameraCharacteristics, orientation: Int, timeMillis: Long): BurstOutcome {
        val started = System.nanoTime()
        run {
            val planes = frames.map { f ->
                f.pixels.rewind()
                IxPlane(f.pixels.order(ByteOrder.nativeOrder()).asShortBuffer(), f.width, f.height)
            }
            val ref = IxBurst.sharpest(planes)
            val black = blackLevels(frames[ref].result, characteristics)
            Log.i("HoltzmanBurst", "reference $ref, black ${black.toList()}, iso ${frames[ref].result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)}")
            val params = com.everettpenne.ix.mergeParamsForIso(frames[ref].result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 860) { Log.i("HoltzmanBurst", it) }
            // one GL context for the merge and the render; if the device has none, both stages run on the CPU
            val gl = runCatching { GlContext() }.onFailure { Log.w("HoltzmanBurst", "no GPU context, using the CPU: $it") }.getOrNull()
            try {
            val merged: IxMerged = mergeBest(gl, frames, planes, ref, black, params)
            Log.i("HoltzmanBurst", "merge done in ${(System.nanoTime() - started) / 1_000_000} ms")
            val base = captureBaseName(timeMillis, TimeZone.getDefault().getOffset(timeMillis).toLong())
            val mergedUri = saver.writeDng("${base}_burst.dng", timeMillis) { out -> writeMerged(out, characteristics, frames[ref], merged, orientation) }
            val refUri = saver.writeDng("${base}_ref.dng", timeMillis) { out ->
                frames[ref].pixels.rewind()
                DngCreator(characteristics, frames[ref].result).use {
                    it.setOrientation(PhotoSaver.exifOrientation(orientation))
                    it.writeByteBuffer(out, Size(frames[ref].width, frames[ref].height), frames[ref].pixels, 0)
                }
            }
            // stage 3: render both to JPEG with the same settings and the same exposure, so the two can be compared fairly
            val settings = BurstRender.settings(frames[ref].result, characteristics, black)
            frames[ref].pixels.rewind()
            val refRaw = ShortArray(merged.width * merged.height).also { frames[ref].pixels.order(ByteOrder.nativeOrder()).asShortBuffer().get(it) }
            val exposure = com.everettpenne.ix.IxRender.autoExposure(refRaw, merged.width, merged.height, settings, BurstRender.EXPOSURE_TARGET)
            val tRender = System.nanoTime()
            val (mergedBitmap, refBitmap) = renderBoth(gl, merged.samples, refRaw, merged.width, merged.height, settings, exposure)
            val mergedJpeg = saver.writeJpeg("${base}_burst.jpg", timeMillis, mergedBitmap, 95, orientation); mergedBitmap.recycle()
            val refJpeg = saver.writeJpeg("${base}_ref.jpg", timeMillis, refBitmap, 95, orientation); refBitmap.recycle()
            Log.i("HoltzmanBurst", "rendered two pictures in ${(System.nanoTime() - tRender) / 1_000_000} ms, exposure x$exposure, wb ${settings.wb.toList()}, shading ${settings.shading != null}")
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.i("HoltzmanBurst", "merged ${frames.size} frames in $ms ms, mean frames per cell ${merged.meanFrames}, reference $ref")
            return BurstOutcome(mergedUri, refUri, mergedJpeg, refJpeg, ms, merged.meanFrames, frames.size)
            } finally { gl?.close() }
        }
    }

    /**
     * The merge on the GPU when there is one, else on the CPU. While the GPU path is new, [VERIFY_GPU] (off by default; it costs a second merge) also runs the CPU merge on the same frames
     * and logs how far apart the two come out.
     */
    private fun mergeBest(gl: GlContext?, frames: List<BurstFrame>, planes: List<IxPlane>, ref: Int, black: IntArray, params: com.everettpenne.ix.IxMergeParams): IxMerged {
        if (gl != null) {
            try {
                val gpu = GpuMerge(gl).merge(frames.map { it.pixels }, frames[ref].width, frames[ref].height, ref, black, params, denoise = if (VERIFY_GPU) 0f else RAW_DENOISE)
                if (VERIFY_GPU) {
                    val t = System.nanoTime()
                    val cpu = IxBurst.merge(planes, ref, black, com.everettpenne.ix.IxMergeParams(params.shot, params.read, params.tolerance, params.tileCells, params.searchCells))
                    var worst = 0; var off = 0; var sum = 0L
                    for (i in cpu.samples.indices) { val d = Math.abs(cpu.samples[i].toInt() - gpu.samples[i].toInt()); if (d > worst) worst = d; if (d > 1) off++; sum += d }
                    Log.i("HoltzmanBurst", "GPU merge vs CPU merge (CPU took ${(System.nanoTime() - t) / 1_000_000} ms): largest difference $worst, $off of ${cpu.samples.size} samples differ by more than 1, mean difference ${"%.5f".format(java.util.Locale.ROOT, sum.toDouble() / cpu.samples.size)}, mean frames GPU ${gpu.meanFrames} CPU ${cpu.meanFrames}")
                }
                return gpu
            } catch (t: Throwable) { Log.w("HoltzmanBurst", "GPU merge unavailable, using the CPU: $t") }
        }
        val merged = IxBurst.merge(planes, ref, black, params)
        // the GPU did the spatial denoise inside its merge; on the CPU path it is done here, on the merged mosaic
        val smooth = com.everettpenne.ix.IxDenoise.mosaic(merged.samples, merged.width, merged.height, black, params.shot, params.read * params.read, merged.meanFrames, RAW_DENOISE)
        return IxMerged(smooth, merged.width, merged.height, merged.meanFrames)
    }

    private companion object { const val VERIFY_GPU = false; /** Tolerance of the spatial denoise of the merged mosaic, in noise deviations. */ const val RAW_DENOISE = 1.6f }

    /**
     * Renders the merged and reference mosaics on the GPU, and checks the first against the CPU renderer on one band of rows (the log says how far
     * apart they are); if the GPU cannot do it on this device, both come from the CPU renderer.
     */
    private fun renderBoth(gl: GlContext?, merged: ShortArray, ref: ShortArray, w: Int, h: Int, s: com.everettpenne.ix.IxRenderSettings, exposure: Float): Pair<android.graphics.Bitmap, android.graphics.Bitmap> {
        try {
            GpuRender(gl ?: error("no GPU context"), ownsContext = false).use { gpu ->
                val t0 = System.nanoTime()
                val a = gpu.toBitmap(merged, w, h, s, exposure)
                val t1 = System.nanoTime()
                val b = gpu.toBitmap(ref, w, h, s, exposure)
                Log.i("HoltzmanBurst", "GPU render: merged ${(t1 - t0) / 1_000_000} ms, reference ${(System.nanoTime() - t1) / 1_000_000} ms")
                if (VERIFY_GPU) {
                    // check one band against the CPU renderer: rounding may differ by a level, anything more is a bug
                    val y0 = (h / 2) and 1.inv(); val rows = 32
                    val cpu = IntArray(rows * w); com.everettpenne.ix.IxRender.renderRows(merged, w, h, y0, y0 + rows, s, exposure, cpu)
                    val gpuPx = IntArray(rows * w); a.getPixels(gpuPx, 0, w, 0, y0, w, rows)
                    var worst = 0; var off = 0
                    for (i in cpu.indices) for (shift in intArrayOf(0, 8, 16)) { val d = Math.abs(((cpu[i] shr shift) and 255) - ((gpuPx[i] shr shift) and 255)); if (d > worst) worst = d; if (d > 1) off++ }
                    Log.i("HoltzmanBurst", "GPU vs CPU on $rows rows: largest channel difference $worst, ${off} channel values off by more than 1")
                }
                return a to b
            }
        } catch (t: Throwable) {
            Log.w("HoltzmanBurst", "GPU render unavailable, using the CPU: $t")
            return BurstRender.toBitmap(merged, w, h, s, exposure) to BurstRender.toBitmap(ref, w, h, s, exposure)
        }
    }

    private fun writeMerged(out: java.io.OutputStream, chars: CameraCharacteristics, ref: BurstFrame, merged: IxMerged, orientation: Int) {
        val bytes = ByteBuffer.allocateDirect(merged.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(merged.samples)
        bytes.rewind()
        DngCreator(chars, ref.result).use { creator ->
            creator.setOrientation(PhotoSaver.exifOrientation(orientation))
            creator.writeByteBuffer(out, Size(merged.width, merged.height), bytes, 0)
        }
    }

    /** The black levels the reference frame was read with (they drift with temperature), else the sensor's static pattern. */
    private fun blackLevels(result: android.hardware.camera2.TotalCaptureResult, chars: CameraCharacteristics): IntArray {
        val dynamic = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        return IntArray(4) { i -> dynamic?.getOrNull(i)?.toInt() ?: pattern?.getOffsetForIndex(i and 1, i shr 1) ?: 64 }
    }
}
