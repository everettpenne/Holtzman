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
class BurstOutcome(val merged: Uri, val reference: Uri, val millis: Long, val meanFrames: Float, val frames: Int)

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
            Log.i("HoltzmanBurst", "reference $ref, black ${black.toList()}")
            val merged: IxMerged = IxBurst.merge(planes, ref, black, com.everettpenne.ix.IxMergeParams(log = { Log.i("HoltzmanBurst", it) }))
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
            val ms = (System.nanoTime() - started) / 1_000_000
            Log.i("HoltzmanBurst", "merged ${frames.size} frames in $ms ms, mean frames per cell ${merged.meanFrames}, reference $ref")
            return BurstOutcome(mergedUri, refUri, ms, merged.meanFrames, frames.size)
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
