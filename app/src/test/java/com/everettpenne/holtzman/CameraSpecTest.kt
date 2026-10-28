package com.everettpenne.holtzman

import com.everettpenne.holtzman.camera.*
import org.junit.Assert.*
import org.junit.Test

class CameraSpecTest {
    private val sizes = listOf(Dim(4080, 3072), Dim(4000, 3000), Dim(4080, 2296), Dim(3024, 3024), Dim(1920, 1080), Dim(1600, 1200), Dim(1280, 960), Dim(640, 480))

    @Test fun picksTheLargestStillOfTheRightShape() {
        assertEquals(Dim(4080, 3072), pickStillSize(sizes))                   // 4:3 (1.328) is within tolerance of 4:3
        assertEquals(Dim(4080, 2296), pickStillSize(sizes, 16f / 9f))
        assertEquals(Dim(3024, 3024), pickStillSize(sizes, 1f))
        assertNull(pickStillSize(listOf(Dim(1000, 100)), 4f / 3f))
    }

    @Test fun previewIsAsLargeAsAScreenNeedsAndTheShapeOfTheStill() {
        assertEquals(Dim(1600, 1200), pickPreviewSize(sizes, 4f / 3f))
        assertEquals(Dim(1920, 1080), pickPreviewSize(sizes, 16f / 9f))
        assertEquals("nothing small enough keeps the smallest of the shape", Dim(4000, 3000), pickPreviewSize(listOf(Dim(4000, 3000), Dim(5000, 3750)), 4f / 3f))
        assertNull(pickPreviewSize(emptyList(), 4f / 3f))
    }

    @Test fun jpegOrientationFollowsTheTiltAndTheLens() {
        assertEquals(90, jpegOrientation(90, 0, false))
        assertEquals(180, jpegOrientation(90, 90, false))
        assertEquals(0, jpegOrientation(90, 270, false))
        assertEquals("front turns the other way", 0, jpegOrientation(270, 270, true))
        assertEquals(270, jpegOrientation(270, 0, true))
        assertEquals(180, jpegOrientation(270, 90, true))
        assertEquals("angles snap to the nearest quarter", 180, jpegOrientation(90, 100, false))
        assertEquals(90, jpegOrientation(90, 359, false))
    }

    @Test fun deviceAnglesSnapAndKeepTheLastOneWhenFlat() {
        assertEquals(0, snapDeviceDegrees(10, 90)); assertEquals(90, snapDeviceDegrees(80, 0)); assertEquals(0, snapDeviceDegrees(350, 90))
        assertEquals(270, snapDeviceDegrees(-1, 270))
    }

    @Test fun namesSortByTimeAndNeverCollideWithinAMillisecond() {
        val a = captureBaseName(1_793_110_503_042L, 0)
        assertEquals("HOLTZ_20261027_141503_042", a)
        assertTrue(captureBaseName(1_793_110_503_043L, 0) > a)
        assertEquals("a timezone shifts the clock", "HOLTZ_20261027_091503_042", captureBaseName(1_793_110_503_042L, -5 * 3_600_000L))
        assertEquals("HOLTZ_19700101_000000_000", captureBaseName(0, 0))
        assertEquals("leap day", "HOLTZ_20240229_120000_000", captureBaseName(1_709_208_000_000L, 0))
    }

    @Test fun paddingAfterTheEndOfAJpegIsTrimmed() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        assertArrayEquals(jpeg, trimJpeg(jpeg + ByteArray(100)))
        assertSame("already clean", jpeg, trimJpeg(jpeg))
        val noMarker = byteArrayOf(1, 2, 3, 0, 0)
        assertSame("no end marker: left alone", noMarker, trimJpeg(noMarker))
        assertArrayEquals("a zero inside the picture is kept", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0, 0xFF.toByte(), 0xD9.toByte()), trimJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0, 0xFF.toByte(), 0xD9.toByte(), 0, 0)))
        assertEquals(0, trimJpeg(ByteArray(0)).size)
    }

    @Test fun exifOrientationValues() {
        assertEquals(1, exifOrientationFor(0)); assertEquals(6, exifOrientationFor(90)); assertEquals(3, exifOrientationFor(180)); assertEquals(8, exifOrientationFor(270)); assertEquals(8, exifOrientationFor(-90))
    }

    @Test fun aTapMapsToTheSensorThroughItsMounting() {
        fun near(a: Pair<Float, Float>, x: Float, y: Float) { assertEquals(x, a.first, 0.001f); assertEquals(y, a.second, 0.001f) }
        near(sensorPoint(0.5f, 0.5f, 90, 1f), 0.5f, 0.5f)
        // back camera (sensor turned 90): the top-left of the upright view is the sensor's bottom-left... a tap at the top (v=0) is the sensor's left edge
        near(sensorPoint(0.5f, 0f, 90, 1f), 0f, 0.5f); near(sensorPoint(1f, 0.5f, 90, 1f), 0.5f, 0f)
        near(sensorPoint(0.5f, 0f, 270, 1f), 1f, 0.5f); near(sensorPoint(1f, 0.5f, 270, 1f), 0.5f, 1f)
        near(sensorPoint(0f, 0f, 0, 1f), 0f, 0f); near(sensorPoint(0f, 0f, 180, 1f), 1f, 1f)
        // zoomed in 2x the visible window is the middle half: a tap at the view's edge is a quarter in from the sensor's edge
        near(sensorPoint(0f, 0.5f, 0, 2f), 0.25f, 0.5f); near(sensorPoint(1f, 0.5f, 0, 2f), 0.75f, 0.5f)
    }

    @Test fun meteringBoxStaysInsideTheArray() {
        val r = meteringBox(0.5f to 0.5f, 4000, 3000)
        assertEquals(360, r[2] - r[0]); assertEquals(360, r[3] - r[1]); assertEquals(2000, (r[0] + r[2]) / 2)
        val edge = meteringBox(0f to 0f, 4000, 3000); assertTrue(edge[0] >= 0 && edge[1] >= 0)
        val far = meteringBox(1f to 1f, 4000, 3000); assertTrue(far[2] <= 4000 && far[3] <= 3000)
    }

    @Test fun zoomIsClampedAndStopsFollowTheRange() {
        assertEquals(0.5f, clampZoom(0.1f, 0.5f, 8f)); assertEquals(8f, clampZoom(20f, 0.5f, 8f)); assertEquals(2.5f, clampZoom(2.5f, 0.5f, 8f)); assertEquals(1f, clampZoom(Float.NaN, 1f, 8f))
        assertEquals(listOf(0.5556f, 1f, 2f), zoomStops(0.5556f, 8f)); assertEquals(listOf(1f, 2f), zoomStops(1f, 8f)); assertEquals(listOf(1f), zoomStops(1f, 1.5f)); assertEquals(listOf(1f, 2f), zoomStops(0.97f, 10f))
    }

    @Test fun flashAndTimerCycleAndFlashModesMapToAeModes() {
        assertEquals(FlashMode.AUTO, FlashMode.OFF.next()); assertEquals(FlashMode.ON, FlashMode.AUTO.next()); assertEquals(FlashMode.OFF, FlashMode.ON.next())
        assertEquals(TimerMode.THREE, TimerMode.OFF.next()); assertEquals(TimerMode.TEN, TimerMode.THREE.next()); assertEquals(TimerMode.OFF, TimerMode.TEN.next())
        assertEquals(1, aeModeFor(FlashMode.ON, hasFlash = false)); assertEquals(1, aeModeFor(FlashMode.OFF, true)); assertEquals(2, aeModeFor(FlashMode.AUTO, true)); assertEquals(3, aeModeFor(FlashMode.ON, true))
    }
}
