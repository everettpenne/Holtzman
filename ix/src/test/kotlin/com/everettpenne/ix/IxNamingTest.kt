package com.everettpenne.ix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.ZoneOffset

class IxNamingTest {
    @Test
    fun baseNameUsesTheCaptureTimeInTheGivenZone() {
        assertEquals("IMG_19700101_000000", IxNaming.baseName(0L, ZoneOffset.UTC))
    }

    @Test
    fun suffixesDistinguishJpegAndDng() {
        assertEquals("IMG_test.jpg", IxNaming.jpeg("IMG_test"))
        assertEquals("IMG_test.dng", IxNaming.dng("IMG_test"))
    }

    @Test
    fun blankBaseNamesAndNegativeTimestampsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { IxNaming.jpeg(" ") }
        assertThrows(IllegalArgumentException::class.java) { IxNaming.baseName(-1L, ZoneOffset.UTC) }
    }
}
