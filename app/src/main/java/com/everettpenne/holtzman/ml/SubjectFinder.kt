package com.everettpenne.holtzman.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.everettpenne.ix.IxSubject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Finds the subject of the viewfinder for autofocus: DeepLab v3 (MobileNetV2, 257x257, the 21 PASCAL VOC classes; MediaPipe's model zoo, Apache-2.0,
 * https://storage.googleapis.com/mediapipe-models/image_segmenter/deeplab_v3/float32/latest/deeplab_v3.tflite) labels every pixel of a small copy of the
 * preview, and [IxSubject.pick] chooses the patch the picture is about. On the Pixel 10a's TPU one frame takes about 9 ms (model-lab, 2026-10-29); where
 * the TPU is not available it runs on four CPU threads (about 60 ms), which is still fine at the few frames a second autofocus needs.
 */
class SubjectFinder private constructor(private val interpreter: Interpreter, private val delegate: NnApiDelegate?, val where: String) : AutoCloseable {
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(SIZE * SIZE * CLASSES * 4).order(ByteOrder.nativeOrder())
    private val px = IntArray(SIZE * SIZE)
    private val logits = FloatArray(SIZE * SIZE * CLASSES)
    private val labels = ByteArray(SIZE * SIZE)

    /** The subject of [bmp] (an upright [SIZE] x [SIZE] copy of the preview), its box in fractions of the picture, or null. */
    @Synchronized fun find(bmp: Bitmap): IxSubject.Subject? {
        require(bmp.width == SIZE && bmp.height == SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        for (p in px) {   // the model takes colour scaled to -1..1
            input.putFloat(((p shr 16) and 255) / 127.5f - 1f); input.putFloat(((p shr 8) and 255) / 127.5f - 1f); input.putFloat((p and 255) / 127.5f - 1f)
        }
        input.rewind(); output.rewind()
        interpreter.run(input, output)
        output.rewind(); output.asFloatBuffer().get(logits)
        for (i in 0 until SIZE * SIZE) {
            var best = 0; var bv = logits[i * CLASSES]
            for (k in 1 until CLASSES) { val v = logits[i * CLASSES + k]; if (v > bv) { bv = v; best = k } }
            // A pixel counts for a class only when the network is fairly sure of it (softmax above [MIN_CONFIDENCE]); unsure pixels are background. Without
            // this, a workbench of circuit boards and cables came out as a 5% "train" now and then and focus jumped to it.
            if (best != 0) { var sum = 0f; for (k in 0 until CLASSES) sum += kotlin.math.exp(logits[i * CLASSES + k] - bv); if (1f / sum < MIN_CONFIDENCE) best = 0 }
            labels[i] = best.toByte()
        }
        if (com.everettpenne.holtzman.save.DebugFlags.on("subjectlog")) {
            val counts = IntArray(CLASSES); for (b in labels) counts[b.toInt()]++
            Log.i(TAG, "classes: " + counts.indices.filter { counts[it] > 0 }.sortedByDescending { counts[it] }.joinToString { "${IxSubject.LABELS[it]} ${"%.1f".format(java.util.Locale.ROOT, counts[it] * 100f / labels.size)}%" })
        }
        return IxSubject.pick(labels, SIZE, SIZE)
    }

    override fun close() { runCatching { interpreter.close() }; runCatching { delegate?.close() } }

    companion object {
        const val SIZE = 257
        private const val CLASSES = 21
        private const val MIN_CONFIDENCE = 0.7f
        private const val TAG = "HoltzmanSubject"
        private var shared: SubjectFinder? = null
        private var tried = false

        /** The one finder of the app, made on first use (a second or two on the TPU); null if the model cannot run here at all. */
        @Synchronized fun shared(context: Context): SubjectFinder? {
            if (!tried) { tried = true; shared = create(context.applicationContext) }
            return shared
        }

        private fun create(context: Context): SubjectFinder? {
            val bytes = runCatching { context.assets.open("deeplab_v3.tflite").use { it.readBytes() } }.getOrElse { Log.w(TAG, "no subject model", it); return null }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
            val t = System.nanoTime()
            // the TPU first; any failure there (no driver, an emulator) falls back to the CPU
            runCatching {
                val d = NnApiDelegate(NnApiDelegate.Options().setAcceleratorName("google-edgetpu").setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED).setAllowFp16(true).setUseNnapiCpu(false))
                try { SubjectFinder(Interpreter(model, Interpreter.Options().addDelegate(d)), d, "TPU") } catch (e: Throwable) { d.close(); throw e }
            }.onSuccess { Log.i(TAG, "subject finder on the TPU, ready in ${(System.nanoTime() - t) / 1_000_000} ms"); return it }
                .onFailure { Log.i(TAG, "subject finder: TPU unavailable ($it), using the CPU") }
            return runCatching { SubjectFinder(Interpreter(model, Interpreter.Options().setNumThreads(4)), null, "CPU") }
                .onFailure { Log.w(TAG, "subject finder unavailable", it) }.getOrNull()
        }
    }
}
