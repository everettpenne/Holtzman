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
class BurstProcessor(private val saver: PhotoSaver, private val context: android.content.Context? = null) {
    fun process(frames: List<BurstFrame>, characteristics: CameraCharacteristics, orientation: Int, timeMillis: Long, saveDng: Boolean = false, saveReference: Boolean = false, ultraHdr: Boolean = false, prep: BurstPrep? = null, tuning: BurstRender.LookTuning = BurstRender.LookTuning(), cropLongOverShort: Float? = null): BurstOutcome {
        val started = System.nanoTime()
        run {
            val planes = frames.map { f ->
                f.pixels.rewind()
                IxPlane(f.pixels.order(ByteOrder.nativeOrder()).asShortBuffer(), f.width, f.height)
            }
            val tS = System.nanoTime()
            // frames already on the GPU and scored while the burst was captured (BurstPrep), when that all worked
            val ready = prep?.takeIf { it.usable(frames.size) }
            if (prep != null && ready == null) prep.close()
            val ref = ready?.reference() ?: IxBurst.sharpest(planes)
            Log.i("HoltzmanTiming", "choosing the reference frame took ${(System.nanoTime() - tS) / 1_000_000} ms")
            val black = blackLevels(frames[ref].result, characteristics)
            Log.i("HoltzmanBurst", "reference $ref, black ${black.toList()}, iso ${frames[ref].result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)}")
            val baseParams = com.everettpenne.ix.mergeParamsForIso(frames[ref].result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 860) { Log.i("HoltzmanBurst", it) }
            val params = if (DebugFlags.on("oldweights")) com.everettpenne.ix.IxMergeParams(baseParams.shot, baseParams.read, 0f, 3f, log = baseParams.log) else baseParams
            if (DebugFlags.raw.isNotEmpty()) Log.i("HoltzmanBurst", "debug switches: ${DebugFlags.raw}")
            // one GL context for the merge and the render; if the device has none, both stages run on the CPU
            val tG = System.nanoTime()
            val gl = ready?.gl ?: runCatching { GlContext() }.onFailure { Log.w("HoltzmanBurst", "no GPU context, using the CPU: $it") }.getOrNull()
            Log.i("HoltzmanTiming", "GL context took ${(System.nanoTime() - tG) / 1_000_000} ms")
            try {
            // the learned denoiser on the TPU, when the app carries a model and the phone has the driver; otherwise the classical denoise runs inside the merge
            val tpu = if (DebugFlags.on("notpu")) null else context?.let { com.everettpenne.holtzman.ml.TpuDenoiser.shared(it) }
            val mergedRaw: IxMerged = mergeBest(gl, frames, planes, ref, black, params, classicalDenoise = tpu == null, ready = ready)
            val merged: IxMerged = if (tpu == null) mergedRaw else try {
                val t = System.nanoTime()
                val white = frames[ref].result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
                val out = tpu.denoise(mergedRaw.samples, mergedRaw.width, mergedRaw.height, black, white, params.shot, params.read * params.read, mergedRaw.meanFrames)
                Log.i("HoltzmanBurst", "learned denoise on the TPU in ${(System.nanoTime() - t) / 1_000_000} ms")
                IxMerged(out, mergedRaw.width, mergedRaw.height, mergedRaw.meanFrames)
            } catch (t: Throwable) {
                Log.w("HoltzmanBurst", "TPU denoise failed, using the classical one: $t")
                IxMerged(com.everettpenne.ix.IxDenoise.mosaic(mergedRaw.samples, mergedRaw.width, mergedRaw.height, black, params.shot, params.read * params.read, mergedRaw.meanFrames, RAW_DENOISE), mergedRaw.width, mergedRaw.height, mergedRaw.meanFrames)
            }
            Log.i("HoltzmanBurst", "merge done in ${(System.nanoTime() - started) / 1_000_000} ms")
            val base = captureBaseName(timeMillis, TimeZone.getDefault().getOffset(timeMillis).toLong())
            val tDng = System.nanoTime()
            val mergedDng = if (saveDng) saver.writeDng("$base.dng", timeMillis) { out -> writeMerged(out, characteristics, frames[ref], merged, orientation) } else null
            val refDng = if (saveReference) saver.writeDng("${base}_ref.dng", timeMillis) { out ->
                frames[ref].pixels.rewind()
                DngCreator(characteristics, frames[ref].result).use {
                    it.setOrientation(PhotoSaver.exifOrientation(orientation))
                    it.writeByteBuffer(out, Size(frames[ref].width, frames[ref].height), frames[ref].pixels, 0)
                }
            } else null
            if (saveDng) Log.i("HoltzmanTiming", "merged DNG written in ${(System.nanoTime() - tDng) / 1_000_000} ms")
            // stage 3: render the merged picture (and, when asked, the unmerged reference frame the same way, so the two can be compared fairly)
            val settings = BurstRender.settings(frames[ref].result, characteristics, black, tuning)
            // the exposure compensation moves the brightness the picture is brought to, in linear light, as well as the tone targets: the tone curve alone cannot lift dark areas far
            val exposure = if (tuning.manualExposure) BurstRender.MANUAL_EXPOSURE_GAIN else com.everettpenne.ix.IxRender.autoExposure(merged.samples, merged.width, merged.height, settings, BurstRender.EXPOSURE_TARGET * Math.pow(2.0, tuning.exposureEv.toDouble()).toFloat())
            val refRaw = if (saveReference) ShortArray(merged.width * merged.height).also { frames[ref].pixels.rewind(); frames[ref].pixels.order(ByteOrder.nativeOrder()).asShortBuffer().get(it) } else null
            val tRender = System.nanoTime()
            val face = faceInRaw(frames[ref].result, characteristics, merged.width, merged.height)
            val (renderedBitmap, renderedRef) = renderBoth(gl, merged.samples, refRaw, merged.width, merged.height, settings, exposure, face)
            // a crop (the camera's Ratio setting) is cut from the rendered picture, centred; the Ultra HDR gain map is built from the whole mosaic, so a cropped picture is saved without one
            val mergedBitmap = cropLongOverShort?.let { cropped(renderedBitmap, it) } ?: renderedBitmap
            val refBitmap = if (cropLongOverShort != null) renderedRef?.let { cropped(it, cropLongOverShort) } else renderedRef
            val hdrAttached = ultraHdr && cropLongOverShort == null && UltraHdr.attachGainmap(mergedBitmap, merged.samples, merged.width, merged.height, frames[ref].result, characteristics)
            Log.i("HoltzmanTiming", "rendered in ${(System.nanoTime() - tRender) / 1_000_000} ms (merge started ${(tRender - started) / 1_000_000} ms before)")
            val tJpeg = System.nanoTime()
            val mergedJpeg = saver.writeJpeg("$base.jpg", timeMillis, mergedBitmap, 95, orientation); mergedBitmap.recycle()
            Log.i("HoltzmanTiming", "JPEG written in ${(System.nanoTime() - tJpeg) / 1_000_000} ms")
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
    private fun mergeBest(gl: GlContext?, frames: List<BurstFrame>, planes: List<IxPlane>, ref: Int, black: IntArray, params: com.everettpenne.ix.IxMergeParams, classicalDenoise: Boolean, ready: BurstPrep? = null): IxMerged {
        if (gl != null) {
            try {
                val tP = System.nanoTime(); val engine = ready?.engine ?: GpuMerge(gl)
                Log.i("HoltzmanTiming", "merge shaders took ${(System.nanoTime() - tP) / 1_000_000} ms")
                val gpu = engine.merge(frames.map { it.pixels }, frames[ref].width, frames[ref].height, ref, black, params, denoise = if (VERIFY_GPU || DebugFlags.on("noraw") || !classicalDenoise) 0f else RAW_DENOISE, uploaded = ready?.uploaded)
                if (ready != null && DebugFlags.on("checkprep")) {
                    // debug: the same merge again with the frames uploaded the ordinary way; it must come out sample for sample the same
                    val plain = GpuMerge(gl).merge(frames.map { it.pixels }, frames[ref].width, frames[ref].height, ref, black, params, denoise = if (DebugFlags.on("noraw") || !classicalDenoise) 0f else RAW_DENOISE)
                    var differ = 0; var worst = 0
                    for (i in plain.samples.indices) { val d = Math.abs((plain.samples[i].toInt() and 0xFFFF) - (gpu.samples[i].toInt() and 0xFFFF)); if (d != 0) { differ++; if (d > worst) worst = d } }
                    Log.i("HoltzmanTiming", "prep check: merge from frames uploaded during capture vs uploaded afterwards: $differ of ${plain.samples.size} samples differ, largest $worst, same reference ${plain.meanFrames == gpu.meanFrames}")
                }
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
        if (DebugFlags.on("noraw") || !classicalDenoise) return merged
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

    /** [bitmap] cut to [longOverShort] (long side over short side) about its centre, on even pixel offsets; the original is recycled. */
    private fun cropped(bitmap: android.graphics.Bitmap, longOverShort: Float): android.graphics.Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val landscape = w >= h; val long = if (landscape) w else h; val short = if (landscape) h else w
        val full = long.toFloat() / short
        var newLong = long; var newShort = short
        if (longOverShort < full) newLong = ((short * longOverShort).toInt() and 1.inv()) else if (longOverShort > full) newShort = ((long / longOverShort).toInt() and 1.inv())
        if (newLong == long && newShort == short) return bitmap
        val cw = if (landscape) newLong else newShort; val ch = if (landscape) newShort else newLong
        val x0 = ((w - cw) / 2) and 1.inv(); val y0 = ((h - ch) / 2) and 1.inv()
        return android.graphics.Bitmap.createBitmap(bitmap, x0, y0, cw, ch).also { if (it !== bitmap) bitmap.recycle() }
    }

    private companion object { const val VERIFY_GPU = false; /** Tolerance of the spatial denoise of the merged mosaic, in noise deviations. */ const val RAW_DENOISE = 1.6f }

    /**
     * Renders the merged and reference mosaics on the GPU, and checks the first against the CPU renderer on one band of rows (the log says how far
     * apart they are); if the GPU cannot do it on this device, both come from the CPU renderer.
     */
    private fun renderBoth(gl: GlContext?, merged: ShortArray, ref: ShortArray?, w: Int, h: Int, s: com.everettpenne.ix.IxRenderSettings, exposure: Float, face: IntArray?): Pair<android.graphics.Bitmap, android.graphics.Bitmap?> {
        try {
            val tR = System.nanoTime()
            GpuRender(gl ?: error("no GPU context"), ownsContext = false).use { gpu ->
                Log.i("HoltzmanTiming", "render shaders took ${(System.nanoTime() - tR) / 1_000_000} ms")
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
