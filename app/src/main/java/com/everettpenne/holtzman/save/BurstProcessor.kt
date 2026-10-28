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

/**
 * What a burst produced: the merged picture as a JPEG (always), as a DNG too when asked, and, for judging the merge, the unmerged reference frame
 * rendered the same way, as a JPEG and a DNG.
 */
class BurstOutcome(val jpeg: Uri, val dng: Uri?, val referenceJpeg: Uri?, val referenceDng: Uri?, val millis: Long, val meanFrames: Float, val frames: Int)

/**
 * Stage 1 and 2 of the pipeline, end to end: take the burst's RAW frames, pick the sharpest as the reference, align and merge the rest in the RAW
 * domain (Ix), and write the merged frame as a DNG with the reference frame's metadata (so any RAW editor can develop it), next to the reference
 * frame on its own.
 */
class BurstProcessor(private val saver: PhotoSaver) {
    fun process(frames: List<BurstFrame>, characteristics: CameraCharacteristics, orientation: Int, timeMillis: Long, saveDng: Boolean = false, saveReference: Boolean = false, ultraHdr: Boolean = false): BurstOutcome {
        val started = System.nanoTime()
        run {
            val planes = frames.map { f ->
                f.pixels.rewind()
                IxPlane(f.pixels.order(ByteOrder.nativeOrder()).asShortBuffer(), f.width, f.height)
            }
            val ref = IxBurst.sharpest(planes)
            val black = blackLevels(frames[ref].result, characteristics)
            Log.i("HoltzmanBurst", "reference $ref, black ${black.toList()}, iso ${frames[ref].result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)}")
            val baseParams = com.everettpenne.ix.mergeParamsForIso(frames[ref].result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 860) { Log.i("HoltzmanBurst", it) }
            val params = if (DebugFlags.on("oldweights")) com.everettpenne.ix.IxMergeParams(baseParams.shot, baseParams.read, 0f, 3f, log = baseParams.log) else baseParams
            if (DebugFlags.raw.isNotEmpty()) Log.i("HoltzmanBurst", "debug switches: ${DebugFlags.raw}")
            // one GL context for the merge and the render; if the device has none, both stages run on the CPU
            val gl = runCatching { GlContext() }.onFailure { Log.w("HoltzmanBurst", "no GPU context, using the CPU: $it") }.getOrNull()
            try {
            val merged: IxMerged = mergeBest(gl, frames, planes, ref, black, params)
            Log.i("HoltzmanBurst", "merge done in ${(System.nanoTime() - started) / 1_000_000} ms")
            val base = captureBaseName(timeMillis, TimeZone.getDefault().getOffset(timeMillis).toLong())
            val mergedDng = if (saveDng) saver.writeDng("$base.dng", timeMillis) { out -> writeMerged(out, characteristics, frames[ref], merged, orientation) } else null
            val refDng = if (saveReference) saver.writeDng("${base}_ref.dng", timeMillis) { out ->
                frames[ref].pixels.rewind()
                DngCreator(characteristics, frames[ref].result).use {
                    it.setOrientation(PhotoSaver.exifOrientation(orientation))
                    it.writeByteBuffer(out, Size(frames[ref].width, frames[ref].height), frames[ref].pixels, 0)
                }
            } else null
            // stage 3: render the merged picture (and, when asked, the unmerged reference frame the same way, so the two can be compared fairly)
            val settings = BurstRender.settings(frames[ref].result, characteristics, black)
            val exposure = com.everettpenne.ix.IxRender.autoExposure(merged.samples, merged.width, merged.height, settings, BurstRender.EXPOSURE_TARGET)
            val refRaw = if (saveReference) ShortArray(merged.width * merged.height).also { frames[ref].pixels.rewind(); frames[ref].pixels.order(ByteOrder.nativeOrder()).asShortBuffer().get(it) } else null
            val tRender = System.nanoTime()
            val face = faceInRaw(frames[ref].result, characteristics, merged.width, merged.height)
            val (mergedBitmap, refBitmap) = renderBoth(gl, merged.samples, refRaw, merged.width, merged.height, settings, exposure, face)
            val hdrAttached = ultraHdr && UltraHdr.attachGainmap(mergedBitmap, merged.samples, merged.width, merged.height, frames[ref].result, characteristics)
            val mergedJpeg = saver.writeJpeg("$base.jpg", timeMillis, mergedBitmap, 95, orientation); mergedBitmap.recycle()
            if (ultraHdr) Log.i("HoltzmanBurst", "Ultra HDR gain map ${if (hdrAttached) "attached" else "not attached"}; the saved file ${if (saver.hasGainmap(mergedJpeg)) "carries it" else "does not carry it"}")
            val refJpeg = refBitmap?.let { bmp -> saver.writeJpeg("${base}_ref.jpg", timeMillis, bmp, 95, orientation).also { bmp.recycle() } }
            Log.i("HoltzmanBurst", "rendered ${if (refRaw != null) "two pictures" else "the picture"} in ${(System.nanoTime() - tRender) / 1_000_000} ms, exposure x$exposure, wb ${settings.wb.toList()}, shading ${settings.shading != null}")
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.i("HoltzmanBurst", "merged ${frames.size} frames in $ms ms, mean frames per cell ${merged.meanFrames}, reference $ref")
            return BurstOutcome(mergedJpeg, mergedDng, refJpeg, refDng, ms, merged.meanFrames, frames.size)
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
                val gpu = GpuMerge(gl).merge(frames.map { it.pixels }, frames[ref].width, frames[ref].height, ref, black, params, denoise = if (VERIFY_GPU || DebugFlags.on("noraw")) 0f else RAW_DENOISE)
                if (VERIFY_GPU) {
                    val t = System.nanoTime()
                    val cpu = IxBurst.merge(planes, ref, black, com.everettpenne.ix.IxMergeParams(params.shot, params.read, params.fullTrust, params.noTrust, params.tileCells, params.searchCells))
                    var worst = 0; var off = 0; var sum = 0L
                    for (i in cpu.samples.indices) { val d = Math.abs(cpu.samples[i].toInt() - gpu.samples[i].toInt()); if (d > worst) worst = d; if (d > 1) off++; sum += d }
                    Log.i("HoltzmanBurst", "GPU merge vs CPU merge (CPU took ${(System.nanoTime() - t) / 1_000_000} ms): largest difference $worst, $off of ${cpu.samples.size} samples differ by more than 1, mean difference ${"%.5f".format(java.util.Locale.ROOT, sum.toDouble() / cpu.samples.size)}, mean frames GPU ${gpu.meanFrames} CPU ${cpu.meanFrames}")
                }
                return gpu
            } catch (t: Throwable) { Log.w("HoltzmanBurst", "GPU merge unavailable, using the CPU: $t") }
        }
        val merged = IxBurst.merge(planes, ref, black, params)
        // the GPU did the spatial denoise inside its merge; on the CPU path it is done here, on the merged mosaic
        if (DebugFlags.on("noraw")) return merged
        val smooth = com.everettpenne.ix.IxDenoise.mosaic(merged.samples, merged.width, merged.height, black, params.shot, params.read * params.read, merged.meanFrames, RAW_DENOISE)
        return IxMerged(smooth, merged.width, merged.height, merged.meanFrames)
    }

    /** The largest confident face the reference frame's own metadata reports, as a box in RAW pixel coordinates (null when there is none). */
    private fun faceInRaw(result: android.hardware.camera2.TotalCaptureResult, chars: CameraCharacteristics, rawW: Int, rawH: Int): IntArray? {
        val array = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val faces = result.get(CaptureResult.STATISTICS_FACES)?.map { com.everettpenne.holtzman.camera.FaceBox(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom, it.score) } ?: return null
        val face = com.everettpenne.holtzman.camera.largestFace(faces) ?: return null
        val region = com.everettpenne.holtzman.camera.faceRegion(face, array.width(), array.height(), 0f)
        Log.i("HoltzmanBurst", "face found: score ${face.score}, ${face.right - face.left} px wide in the active array")
        return com.everettpenne.holtzman.camera.scaleToRaw(region, array.width(), array.height(), rawW, rawH)
    }

    private companion object { const val VERIFY_GPU = false; /** Tolerance of the spatial denoise of the merged mosaic, in noise deviations. */ const val RAW_DENOISE = 1.6f }

    /**
     * Renders the merged and reference mosaics on the GPU, and checks the first against the CPU renderer on one band of rows (the log says how far
     * apart they are); if the GPU cannot do it on this device, both come from the CPU renderer.
     */
    private fun renderBoth(gl: GlContext?, merged: ShortArray, ref: ShortArray?, w: Int, h: Int, s: com.everettpenne.ix.IxRenderSettings, exposure: Float, face: IntArray?): Pair<android.graphics.Bitmap, android.graphics.Bitmap?> {
        try {
            GpuRender(gl ?: error("no GPU context"), ownsContext = false).use { gpu ->
                val t0 = System.nanoTime()
                val a = gpu.toBitmap(merged, w, h, s, exposure, face)
                val t1 = System.nanoTime()
                val b = ref?.let { gpu.toBitmap(it, w, h, s, exposure, face) }
                Log.i("HoltzmanBurst", "GPU render: merged ${(t1 - t0) / 1_000_000} ms${if (ref != null) ", reference ${(System.nanoTime() - t1) / 1_000_000} ms" else ""}")
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
            return BurstRender.toBitmap(merged, w, h, s, exposure) to ref?.let { BurstRender.toBitmap(it, w, h, s, exposure) }
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
