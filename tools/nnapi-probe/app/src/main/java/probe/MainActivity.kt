package probe

import android.app.Activity
import android.os.Bundle
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Does the NNAPI route reach the Tensor TPU for an ordinary app? For each test model, run it on the CPU (the reference), then through the NNAPI
 * delegate pinned to the phone's TPU driver, strictly (no CPU fallback allowed), then through the NNAPI delegate with its defaults, and log what
 * happened: whether the model built, how long a pass took, and a digest of the output. An output identical to the CPU's with a CPU-like time means
 * it did not really run on the TPU.
 */
class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Thread { runAll() }.start()
    }

    private fun model(name: String): ByteBuffer {
        val bytes = assets.open(name).readBytes()
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    }

    private fun runAll() {
        for (m in listOf("spike_conv_int8.tflite", "spike_conv.tflite")) {
            run(m, "CPU", null, false)
            run(m, "NNAPI google-edgetpu strict", "google-edgetpu", true)
            run(m, "NNAPI google-edgetpu", "google-edgetpu", false)
            run(m, "NNAPI default", "", false)
        }
        Log.i("NNAPIPROBE", "done")
    }

    private fun run(file: String, label: String, accelerator: String?, strict: Boolean) {
        val tag = "$file / $label"
        var delegate: NnApiDelegate? = null
        var interp: Interpreter? = null
        try {
            val opts = Interpreter.Options()
            if (accelerator == null) opts.setNumThreads(4)
            else {
                val o = NnApiDelegate.Options().setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED).setAllowFp16(true)
                if (accelerator.isNotEmpty()) o.setAcceleratorName(accelerator)
                if (strict) o.setUseNnapiCpu(false)
                delegate = NnApiDelegate(o); opts.addDelegate(delegate)
            }
            val t0 = System.nanoTime()
            interp = Interpreter(model(file), opts)
            val tBuild = (System.nanoTime() - t0) / 1_000_000
            val inT = interp.getInputTensor(0); val outT = interp.getOutputTensor(0)
            val input = ByteBuffer.allocateDirect(inT.numBytes()).order(ByteOrder.nativeOrder())
            if (inT.dataType().name == "FLOAT32") { val f = input.asFloatBuffer(); for (i in 0 until f.capacity()) f.put((i % 251) / 251f) } else { for (i in 0 until input.capacity()) input.put(((i * 7) % 255 - 128).toByte()) }
            input.rewind()
            val out = ByteBuffer.allocateDirect(outT.numBytes()).order(ByteOrder.nativeOrder())
            val t1 = System.nanoTime(); interp.run(input, out.also { it.rewind() }); val tFirst = (System.nanoTime() - t1) / 1_000_000
            var best = Long.MAX_VALUE
            for (i in 0 until 5) { val t = System.nanoTime(); interp.run(input, out.also { it.rewind() }); best = minOf(best, (System.nanoTime() - t) / 1_000_000) }
            out.rewind()
            var sum = 0.0; var nz = 0; var n = 0
            if (outT.dataType().name == "FLOAT32") { val f = out.asFloatBuffer(); n = f.capacity(); for (i in 0 until n) { val v = f.get(i); sum += v; if (v != 0f) nz++ } }
            else { n = out.capacity(); for (i in 0 until n) { val v = out.get(i).toInt(); sum += v; if (v != 0) nz++ } }
            Log.i("NNAPIPROBE", "$tag: built in $tBuild ms, first run $tFirst ms, best of 5 $best ms; input ${inT.dataType()} output ${outT.dataType()} digest sum %.3f, %d of %d non-zero".format(java.util.Locale.ROOT, sum, nz, n))
        } catch (t: Throwable) {
            Log.i("NNAPIPROBE", "$tag: FAILED ${t.javaClass.simpleName}: ${t.message?.take(200)}")
        } finally { runCatching { interp?.close() }; runCatching { delegate?.close() } }
    }
}
