package com.everettpenne.ix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IxSubjectTest {
    private fun map(w: Int, h: Int, vararg rects: IntArray): ByteArray {   // each rect: class, x0, y0, x1, y1 (exclusive)
        val m = ByteArray(w * h)
        for (r in rects) for (y in r[2] until r[4]) for (x in r[1] until r[3]) m[y * w + x] = r[0].toByte()
        return m
    }

    @Test fun emptySceneHasNoSubject() = assertNull(IxSubject.pick(ByteArray(100 * 100), 100, 100))

    @Test fun aPersonBeatsALargerChairNearTheEdge() {
        val s = IxSubject.pick(map(100, 100, intArrayOf(9, 0, 60, 30, 100), intArrayOf(15, 45, 30, 60, 80)), 100, 100)
        assertNotNull(s); assertEquals("person", s!!.name)
        assertEquals(0.45f, s.left, 1e-6f); assertEquals(0.30f, s.top, 1e-6f); assertEquals(0.60f, s.right, 1e-6f); assertEquals(0.80f, s.bottom, 1e-6f)
    }

    @Test fun tinySpecksAndRoomFillingPatchesAreNotSubjects() {
        assertNull(IxSubject.pick(map(100, 100, intArrayOf(5, 10, 10, 13, 13)), 100, 100))       // 9 pixels: 0.09 %
        assertNull(IxSubject.pick(map(100, 100, intArrayOf(18, 0, 0, 100, 90)), 100, 100))       // a sofa over 90 % of the frame
    }

    @Test fun separatePatchesOfOneClassAreSeparateCandidates() {
        // two cats: the one in the middle wins although the other is a little larger
        val s = IxSubject.pick(map(100, 100, intArrayOf(8, 40, 40, 60, 60), intArrayOf(8, 0, 0, 22, 22)), 100, 100)!!
        assertEquals(0.4f, s.left, 1e-6f)
    }

    @Test fun aPersonIsFocusedHighInTheBox() {
        val s = IxSubject.Subject(15, 0.4f, 0.2f, 0.6f, 0.9f, 0.1f)
        val f = s.focusBox(); assertEquals(0.2f, f[1], 1e-6f); assertTrue(f[3] < 0.5f)
        val cup = IxSubject.Subject(5, 0.4f, 0.2f, 0.6f, 0.9f, 0.1f)
        assertEquals(0.9f, cup.focusBox()[3], 1e-6f)
    }

    @Test fun steadyNeedsTwoSightingsToSwitchAndEasesTheSameSubject() {
        val a = IxSubject.Subject(15, 0.4f, 0.2f, 0.6f, 0.9f, 0.1f)
        val b = IxSubject.Subject(8, 0.0f, 0.0f, 0.2f, 0.2f, 0.04f)
        assertEquals(a, IxSubject.steady(a, b, streak = 1))                 // one frame of a cat does not take focus from the person
        assertEquals(b, IxSubject.steady(a, b, streak = 2))                 // two do
        val moved = IxSubject.Subject(15, 0.44f, 0.2f, 0.64f, 0.9f, 0.1f)
        assertEquals(0.42f, IxSubject.steady(a, moved, streak = 1)!!.left, 1e-6f)
        assertEquals(a, IxSubject.steady(a, null, streak = 1))              // one empty frame keeps the subject
        assertNull(IxSubject.steady(a, null, streak = 2))
    }
}
