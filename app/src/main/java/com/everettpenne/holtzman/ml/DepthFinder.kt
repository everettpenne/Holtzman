package com.everettpenne.holtzman.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.everettpenne.ix.IxDepthSubject
import com.everettpenne.ix.IxSubject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Relative depth of the viewfinder (MiDaS v2.1 small, isl-org/MiDaS, MIT: https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite), for
 * finding the foreground subject when the segmentation sees none of its classes. The release is 66 MB of float32 weights; the copy in the app has its
 * convolution weights stored as float16 with DEQUANTIZE ops in front (tools/models/shrink_tflite.py --to fp16), 33 MB, with depth maps the same as the
 * original's to 0.03 %. An int8 copy (17 MB, correlation 0.9997) was tried first: the Pixel 10a's TPU refused it (its compiler wants every convolution's
 * weights to be constants, and NNAPI folds only float16 DEQUANTIZEs into constants), so it ran on the CPU at about 280 ms a frame; float16 runs on the TPU
 * at about 25 ms including preparing the frame (2026-11-02).
 */
class DepthFinder private constructor(private val interpreter: Interpreter, private val delegate: NnApiDelegate?, val where: String) : AutoCloseable {
    private val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
    private val px = IntArray(SIZE * SIZE)
    private val depth = FloatArray(SIZE * SIZE)

    /** The foreground subject of [bmp] (an upright [SIZE] x [SIZE] copy of the preview), its box in fractions of the picture, or null. */
    @Synchronized fun find(bmp: Bitmap): IxSubject.Subject? {
        require(bmp.width == SIZE && bmp.height == SIZE)
        bmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        for (p in px) {   // ImageNet mean and deviation, as MiDaS was trained
            input.putFloat((((p shr 16) and 255) / 255f - 0.485f) / 0.229f); input.putFloat((((p shr 8) and 255) / 255f - 0.456f) / 0.224f); input.putFloat(((p and 255) / 255f - 0.406f) / 0.225f)
        }
        input.rewind(); output.rewind()
        interpreter.run(input, output)
        output.rewind(); output.asFloatBuffer().get(depth)
        return IxDepthSubject.find(depth, SIZE, SIZE)
    }

    override fun close() { runCatching { interpreter.close() }; runCatching { delegate?.close() } }

    companion object {
        const val SIZE = 256
        private const val ASSET = "midas_small_fp16.tflite"
        private const val TAG = "HoltzmanDepth"
        private var shared: DepthFinder? = null
        private var tried = false

        @Synchronized fun shared(context: Context): DepthFinder? {
            if (!tried) { tried = true; shared = create(context.applicationContext) }
            return shared
        }

        private fun create(context: Context): DepthFinder? {
            val bytes = runCatching { context.assets.open(ASSET).use { it.readBytes() } }.getOrElse { Log.w(TAG, "no depth model", it); return null }
            val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
            val t = System.nanoTime()
            runCatching {
                // the TPU's compilation of this network takes about 8 s; NNAPI keeps it in the app's cache, so it is paid once per install (the token changes with the model)
                val d = NnApiDelegate(NnApiDelegate.Options().setAcceleratorName("google-edgetpu").setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED).setAllowFp16(true).setUseNnapiCpu(false)
                    .setCacheDir(context.codeCacheDir.absolutePath).setModelToken("midas-small-fp16-${bytes.size}"))
                try { DepthFinder(Interpreter(model, Interpreter.Options().addDelegate(d)), d, "TPU") } catch (e: Throwable) { d.close(); throw e }
            }.onSuccess { Log.i(TAG, "depth on the TPU, ready in ${(System.nanoTime() - t) / 1_000_000} ms"); return it }
                .onFailure { Log.i(TAG, "depth: TPU unavailable ($it), using the CPU") }
            return runCatching { DepthFinder(Interpreter(model, Interpreter.Options().setNumThreads(4)), null, "CPU") }
                .onFailure { Log.w(TAG, "depth unavailable", it) }.getOrNull()
        }
    }
}
