package com.everettpenne.holtzman.ml

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.NpuCompatibilityChecker
import com.google.ai.edge.litert.Environment

/** One processor's result: whether the model would load and run on it, how long, and what went wrong if not. */
data class SpikeResult(val accelerator: String, val model: String, val ok: Boolean, val createMs: Long, val firstRunMs: Long, val steadyMs: Long, val note: String)

/**
 * Stage 4 of the pipeline plan: which of the Tensor G4's processors will actually run a RAW-denoiser-sized convolutional network for a third-party
 * app, and how fast. Runs the bundled test model (random weights: speed and support, not quality) on the CPU, the GPU and the NPU through LiteRT's
 * CompiledModel API, and reports each outcome honestly, including a failure and its reason.
 */
object AcceleratorSpike {
    private val MODELS = listOf("spike_conv.tflite" to "fp16", "spike_conv_int8.tflite" to "int8")
    private const val RUNS = 5

    fun run(context: Context): List<SpikeResult> = MODELS.flatMap { (file, label) -> listOf(Accelerator.CPU, Accelerator.GPU, Accelerator.NPU).map { runOn(context, it, file, label) } }

    private fun runOn(context: Context, acc: Accelerator, file: String, label: String): SpikeResult {
        val name = "${acc.name} $label"
        var model: CompiledModel? = null
        return try {
            val t0 = System.nanoTime()
            // The NPU needs its dispatch library found: the provider points LiteRT at the libraries the Google Tensor runtime ships.
            val env = if (acc == Accelerator.NPU) Environment.create(context, BuiltinNpuAcceleratorProvider(context, NpuCompatibilityChecker.GoogleTensor))
                else Environment.create(context)
            val offered = env.getAvailableAccelerators().map { it.name }
            model = CompiledModel.create(context.assets, file, CompiledModel.Options(acc), env)
            val inputs = model.createInputBuffers(); val outputs = model.createOutputBuffers()
            inputs[0].writeFloat(FloatArray(512 * 512 * 4) { (it % 251) / 251f })
            val t1 = System.nanoTime()
            model.run(inputs, outputs)
            val t2 = System.nanoTime()
            val check = outputs[0].readFloat()
            val t3 = System.nanoTime()
            // the GPU and NPU answer run() before they finish; reading the result is what waits for them, so it is inside the timing
            for (i in 0 until RUNS) { model.run(inputs, outputs); outputs[0].readFloat() }
            val t4 = System.nanoTime()
            val finite = check.all { it.isFinite() }
            var sum = 0.0; var nonzero = 0; for (v in check) { sum += v; if (v != 0f) nonzero++ }
            val digest = "output sum %.3f, %d of %d values non-zero, offered %s".format(java.util.Locale.ROOT, sum, nonzero, check.size, offered)
            SpikeResult(name, label, finite, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t4 - t3) / 1_000_000 / RUNS, if (!finite) "ran, but the output was not finite" else if (acc != Accelerator.CPU && acc.name !in offered) "ran, but ${acc.name} is not offered (so it fell back to the CPU); offered: $offered" else "ran; $digest")
        } catch (t: Throwable) {
            SpikeResult(name, label, false, 0, 0, 0, "${t.javaClass.simpleName}: ${t.message?.take(160)}")
        } finally { runCatching { model?.close() } }
    }

    fun text(results: List<SpikeResult>): String = results.joinToString("\n") { r ->
        if (r.ok && r.note.startsWith("ran;")) "${r.accelerator}: ${r.steadyMs} ms per 1024x1024 pass (first run ${r.firstRunMs} ms, setup ${r.createMs} ms); ${r.note.removePrefix("ran; ")}"
        else if (r.ok) "${r.accelerator}: NOT USED. ${r.note} (${r.steadyMs} ms was the CPU)" else "${r.accelerator}: unavailable (${r.note})"
    }
}
