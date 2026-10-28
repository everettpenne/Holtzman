package com.everettpenne.ix

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IxModelTest {
    @Test
    fun aSourceNeedsBytesAndDimensions() {
        assertThrows(IllegalArgumentException::class.java) {
            IxSource(ByteArray(0), IxSourceFormat.JPEG, 4, 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            IxSource(byteArrayOf(1), IxSourceFormat.JPEG, 0, 3)
        }
    }

    @Test
    fun aCaptureInfoRejectsAnImpossibleOrientation() {
        assertThrows(IllegalArgumentException::class.java) {
            IxCaptureInfo(timestampMillis = 0L, orientationDegrees = 360)
        }
    }

    @Test
    fun anEmptyResultMustCarryAWarning() {
        assertThrows(IllegalArgumentException::class.java) { IxResult(files = emptyList()) }
        val explained = IxResult(files = emptyList(), warnings = listOf(IxWarning.SourceUnsupported(IxSourceFormat.YUV)))
        assertTrue(explained.files.isEmpty())
        assertTrue(explained.warnings.isNotEmpty())
    }

    @Test
    fun contentEqualityComparesTheBytes() {
        val a = IxSource(byteArrayOf(1, 2, 3), IxSourceFormat.JPEG, 4, 3)
        val b = IxSource(byteArrayOf(1, 2, 3), IxSourceFormat.JPEG, 4, 3)
        val c = IxSource(byteArrayOf(1, 2, 4), IxSourceFormat.JPEG, 4, 3)
        assertTrue(a.contentEquals(b))
        assertTrue(!a.contentEquals(c))
    }
}
