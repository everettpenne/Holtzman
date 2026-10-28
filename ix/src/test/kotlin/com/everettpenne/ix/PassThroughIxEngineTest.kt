package com.everettpenne.ix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassThroughIxEngineTest {
    private val engine = PassThroughIxEngine()

    private fun request(
        sourceFormat: IxSourceFormat,
        output: IxOutput,
        style: IxStyle? = null,
    ) = IxRequest(
        source = IxSource(byteArrayOf(1, 2, 3), sourceFormat, width = 4, height = 3),
        capture = IxCaptureInfo(timestampMillis = 0L, orientationDegrees = 90),
        output = output,
        style = style,
        baseName = "IMG_test",
    )

    @Test
    fun aJpegIsWrittenThroughUnchanged() {
        val result = engine.render(request(IxSourceFormat.JPEG, IxOutput.JPEG))
        assertEquals(1, result.files.size)
        val file = result.files.single()
        assertEquals("IMG_test.jpg", file.name)
        assertEquals("image/jpeg", file.mimeType)
        assertEquals(IxFileKind.JPEG, file.kind)
        assertTrue(file.primary)
        assertTrue(result.warnings.isEmpty())
        assertEquals(IX_VERSION, result.engineVersion)
    }

    @Test
    fun anUnsupportedJpegRequestFallsBackToJpeg() {
        val result = engine.render(request(IxSourceFormat.JPEG, IxOutput.RAW))
        assertEquals(IxFileKind.JPEG, result.files.single().kind)
        val warning = result.warnings.single()
        assertTrue(warning is IxWarning.OutputFallback)
        assertEquals(IxOutput.RAW, (warning as IxWarning.OutputFallback).requested)
        assertEquals(IxOutput.JPEG, warning.used)
    }

    @Test
    fun anUltraHdrSourceCanStayUltraHdr() {
        val result = engine.render(request(IxSourceFormat.ULTRA_HDR, IxOutput.ULTRA_HDR))
        assertEquals(IxFileKind.ULTRA_HDR, result.files.single().kind)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun aRawSourceIsWrittenAsDng() {
        val result = engine.render(request(IxSourceFormat.RAW, IxOutput.RAW))
        val file = result.files.single()
        assertEquals("IMG_test.dng", file.name)
        assertEquals("image/x-adobe-dng", file.mimeType)
        assertEquals(IxFileKind.DNG, file.kind)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun rawPlusJpegReportsThatOnlyTheRawWasWritten() {
        val result = engine.render(request(IxSourceFormat.RAW, IxOutput.RAW_JPEG))
        assertEquals(IxFileKind.DNG, result.files.single().kind)
        val warning = result.warnings.single() as IxWarning.OutputFallback
        assertEquals(IxOutput.RAW_JPEG, warning.requested)
        assertEquals(IxOutput.RAW, warning.used)
    }

    @Test
    fun aRawSourceCannotBecomeAJpegYet() {
        val result = engine.render(request(IxSourceFormat.RAW, IxOutput.JPEG))
        assertTrue(result.files.isEmpty())
        assertTrue(result.warnings.single() is IxWarning.SourceUnsupported)
    }

    @Test
    fun aStyleIsReportedAsSkippedRatherThanSilentlyDropped() {
        val result = engine.render(
            request(IxSourceFormat.JPEG, IxOutput.JPEG, style = IxStyle(IxStylePreset.WARM, strength = 0.6f)),
        )
        assertEquals(1, result.files.size)
        val warning = result.warnings.single()
        assertTrue(warning is IxWarning.StyleSkipped)
        assertTrue(warning.message.contains("Style skipped"))
    }

    @Test
    fun yuvIsUnsupportedInTheSkeleton() {
        val result = engine.render(request(IxSourceFormat.YUV, IxOutput.JPEG))
        assertTrue(result.files.isEmpty())
        assertTrue(result.warnings.single() is IxWarning.SourceUnsupported)
    }
}
