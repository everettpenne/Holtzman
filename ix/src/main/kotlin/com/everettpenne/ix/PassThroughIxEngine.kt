package com.everettpenne.ix

/**
 * A skeleton engine that moves bytes without decoding them.
 *
 * It exists so the contract, naming, warnings and UI wiring can be built and tested before the real
 * encoders land. It is **not** Ix 1: it does not render styles, it cannot turn RAW into JPEG, and it
 * reports every compromise in the result. Do not ship it as the production engine.
 */
public class PassThroughIxEngine(override val version: String = IX_VERSION) : IxEngine {

    override fun render(request: IxRequest): IxResult {
        val source = request.source
        val styleWarning = request.style?.let {
            IxWarning.StyleSkipped("the skeleton engine does not render styles yet")
        }

        val (files, warnings) = when (source.format) {
            IxSourceFormat.YUV ->
                emptyList<IxFile>() to listOf(IxWarning.SourceUnsupported(source.format))

            IxSourceFormat.JPEG -> when (request.output) {
                IxOutput.JPEG -> listOf(file(request, IxFileKind.JPEG)) to emptyList()
                IxOutput.ULTRA_HDR, IxOutput.RAW, IxOutput.RAW_JPEG ->
                    listOf(file(request, IxFileKind.JPEG)) to
                        listOf(IxWarning.OutputFallback(request.output, IxOutput.JPEG))
            }

            IxSourceFormat.ULTRA_HDR -> when (request.output) {
                IxOutput.ULTRA_HDR -> listOf(file(request, IxFileKind.ULTRA_HDR)) to emptyList()
                IxOutput.JPEG -> listOf(file(request, IxFileKind.JPEG)) to emptyList()
                IxOutput.RAW, IxOutput.RAW_JPEG ->
                    listOf(file(request, IxFileKind.JPEG)) to
                        listOf(IxWarning.OutputFallback(request.output, IxOutput.JPEG))
            }

            IxSourceFormat.RAW -> when (request.output) {
                IxOutput.RAW -> listOf(file(request, IxFileKind.DNG)) to emptyList()
                IxOutput.RAW_JPEG -> listOf(file(request, IxFileKind.DNG)) to
                    listOf(IxWarning.OutputFallback(request.output, IxOutput.RAW))
                // Decoding RAW into JPEG is exactly the work the real engine has to do, not the skeleton.
                IxOutput.JPEG, IxOutput.ULTRA_HDR ->
                    emptyList<IxFile>() to listOf(IxWarning.SourceUnsupported(source.format))
            }
        }

        return IxResult(
            files = files,
            warnings = warnings + listOfNotNull(styleWarning),
            engineVersion = version,
        )
    }

    private fun file(request: IxRequest, kind: IxFileKind): IxFile = IxFile(
        name = if (kind == IxFileKind.DNG) IxNaming.dng(request.baseName) else IxNaming.jpeg(request.baseName),
        bytes = request.source.bytes,
        kind = kind,
        primary = true,
    )
}
