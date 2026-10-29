package com.everettpenne.holtzman.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RingFramesTest {
    private fun e(i: Int, ns: Long = 20_000_000L, iso: Int = 400, stepMs: Long = 33) = RingEntry(i * stepMs * 1_000_000L, ns, iso)

    @Test fun takesTheNewestSixInOrder() {
        val ring = (0 until 8).map { e(it) }
        assertEquals(listOf(2, 3, 4, 5, 6, 7), pickRingFrames(ring, 6))
    }

    @Test fun stopsAtTheFirstFrameExposedDifferently() {
        // automatic exposure was still settling: the first four frames were exposed longer
        val ring = (0 until 8).map { if (it < 4) e(it, ns = 30_000_000L) else e(it) }
        assertEquals(listOf(4, 5, 6, 7), pickRingFrames(ring, 6))
        // a 2% difference counts as the same exposure
        assertEquals(6, pickRingFrames((0 until 8).map { e(it, ns = 20_000_000L + it * 50_000L) }, 6).size)
    }

    @Test fun stopsAtAStallInTheStream() {
        val ring = (0 until 8).map { RingEntry((if (it < 5) it * 33L else it * 33L + 300L) * 1_000_000L, 20_000_000L, 400) }
        assertEquals(listOf(5, 6, 7), pickRingFrames(ring, 6))
    }

    @Test fun anEmptyOrShortRingGivesWhatItHas() {
        assertTrue(pickRingFrames(emptyList(), 6).isEmpty())
        assertEquals(listOf(0, 1), pickRingFrames(listOf(e(0), e(1)), 6))
        assertEquals(listOf(3, 4), pickRingFrames((0 until 5).map { e(it) }, 2))
    }
}
