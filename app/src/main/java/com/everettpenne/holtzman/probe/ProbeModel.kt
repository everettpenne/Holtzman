package com.everettpenne.holtzman.probe

/*
 * What one camera says about itself, as plain data, and how it is written down. The point of the probe is a fact-finding run on the real phone:
 * what a third-party app is offered by the camera HAL (RAW, Ultra HDR, extensions, reprocessing, burst depth), so the engine is built on what is
 * there and not on what is hoped. Collecting uses Android types (ProbeCollector); everything here is pure and unit-tested.
 */
data class ProbeCamera(
    val id: String,
    val facing: String,
    val hardwareLevel: String,
    val capabilities: List<String>,
    /** Physical cameras behind a logical one (a phone's wide, ultrawide and tele), empty for a plain camera. */
    val physicalIds: List<String>,
    val sensorMm: Pair<Float, Float>?,
    val pixelArray: String?,
    val focalLengthsMm: List<Float>,
    val apertures: List<Float>,
    val isoRange: String?,
    val exposureRange: String?,
    val jpegSizes: List<String>,
    val rawSizes: List<String>,
    val ultraHdrSizes: List<String>,
    val yuvMax: String?,
    val extensions: List<String>,
    val tenBitProfiles: List<String>,
    val maxZoomRatio: String?,
    val pipelineMaxDepth: Int?,
    val maxRawOutputs: Int?,
    val reprocessing: List<String>,
    val noiseReductionModes: List<String>,
    val edgeModes: List<String>,
    val tonemapModes: List<String>,
    val sensorOrientation: Int?,
)

data class ProbeReport(val device: String, val android: String, val cameras: List<ProbeCamera>)

object ProbeFormat {
    /** A human-readable report: one block per camera, with the answers the engine plan depends on at the top of each block. */
    fun text(report: ProbeReport): String = buildString {
        appendLine("Holtzman camera probe")
        appendLine("Device: ${report.device}")
        appendLine("Android: ${report.android}")
        appendLine()
        report.cameras.forEach { c ->
            appendLine("== Camera ${c.id} (${c.facing}) ==")
            appendLine("Hardware level: ${c.hardwareLevel}")
            appendLine("Verdicts: ${verdicts(c).joinToString("; ")}")
            appendLine("Capabilities: ${c.capabilities.joinToString(", ").ifEmpty { "none reported" }}")
            c.sensorOrientation?.let { appendLine("Sensor orientation: $it") }
            if (c.physicalIds.isNotEmpty()) appendLine("Logical camera over: ${c.physicalIds.joinToString(", ")}")
            c.sensorMm?.let { appendLine("Sensor: %.2f x %.2f mm, %s px".format(java.util.Locale.ROOT, it.first, it.second, c.pixelArray ?: "?")) }
            if (c.focalLengthsMm.isNotEmpty()) appendLine("Focal lengths (mm): ${c.focalLengthsMm.joinToString { "%.2f".format(java.util.Locale.ROOT, it) }}")
            if (c.apertures.isNotEmpty()) appendLine("Apertures: ${c.apertures.joinToString { "f/%.2f".format(java.util.Locale.ROOT, it) }}")
            appendLine("ISO: ${c.isoRange ?: "not reported"}   Exposure: ${c.exposureRange ?: "not reported"}   Max zoom ratio: ${c.maxZoomRatio ?: "not reported"}")
            appendLine("JPEG (largest): ${c.jpegSizes.take(3).joinToString().ifEmpty { "none" }}")
            appendLine("RAW: ${c.rawSizes.take(3).joinToString().ifEmpty { "none" }}   YUV max: ${c.yuvMax ?: "none"}")
            appendLine("Ultra HDR (JPEG_R): ${c.ultraHdrSizes.take(3).joinToString().ifEmpty { "none" }}")
            appendLine("Camera extensions: ${c.extensions.joinToString().ifEmpty { "none" }}")
            appendLine("10-bit dynamic range profiles: ${c.tenBitProfiles.joinToString().ifEmpty { "none" }}")
            appendLine("Reprocessing: ${c.reprocessing.joinToString().ifEmpty { "none" }}   Pipeline depth: ${c.pipelineMaxDepth ?: "?"}   Max RAW streams: ${c.maxRawOutputs ?: "?"}")
            appendLine("Processing controls: noise reduction ${c.noiseReductionModes.joinToString("/")}; edge ${c.edgeModes.joinToString("/")}; tone map ${c.tonemapModes.joinToString("/")}")
            appendLine()
        }
    }

    /** Short answers to the questions the plan turns on, from one camera's facts. */
    fun verdicts(c: ProbeCamera): List<String> = listOf(
        if (c.rawSizes.isNotEmpty() && "RAW" in c.capabilities) "RAW: yes (${c.rawSizes.first()})" else "RAW: no",
        if (c.ultraHdrSizes.isNotEmpty()) "Ultra HDR output: yes" else "Ultra HDR output: no (the app would build it itself)",
        if (c.extensions.isEmpty()) "extensions: none (no vendor Night/HDR)" else "extensions: ${c.extensions.joinToString("/")}",
        if (c.reprocessing.isEmpty()) "reprocessing: none" else "reprocessing: ${c.reprocessing.joinToString("/")}",
        if ((c.pipelineMaxDepth ?: 0) >= 4) "burst depth ${c.pipelineMaxDepth}: enough for a merge" else "burst depth ${c.pipelineMaxDepth ?: "?"}: short for a merge",
        if ("MANUAL_SENSOR" in c.capabilities) "manual exposure: yes" else "manual exposure: no",
        if (c.physicalIds.isNotEmpty()) "logical camera: ${c.physicalIds.size} lenses" else "single lens",
    )
}
