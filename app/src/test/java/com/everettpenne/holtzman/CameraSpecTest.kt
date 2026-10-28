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
}
