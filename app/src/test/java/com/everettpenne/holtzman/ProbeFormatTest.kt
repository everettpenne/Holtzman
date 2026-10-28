package com.everettpenne.holtzman

import com.everettpenne.holtzman.probe.*
import org.junit.Assert.*
import org.junit.Test

class ProbeFormatTest {
    private fun cam(
        raw: List<String> = listOf("4080x3072"), caps: List<String> = listOf("BACKWARD_COMPATIBLE", "RAW", "MANUAL_SENSOR"),
        uhdr: List<String> = listOf("4080x3072"), ext: List<String> = listOf("NIGHT"), depth: Int? = 8, repro: List<String> = listOf("yuv"), physical: List<String> = listOf("2", "3"),
    ) = ProbeCamera("0", "back", "FULL", caps, physical, 6.4f to 4.8f, "4080x3072", listOf(6.8f), listOf(1.7f), "50-6400", "20000 ns - 1000000000 ns",
        listOf("4080x3072", "4000x3000"), raw, uhdr, "4080x3072", ext, listOf("STANDARD", "HLG10"), "8.0", depth, 3, repro,
        listOf("OFF", "FAST", "HQ"), listOf("OFF", "FAST", "HQ"), listOf("FAST", "HQ"), 90)

    @Test fun verdictsAnswerTheQuestionsThePlanTurnsOn() {
        val v = ProbeFormat.verdicts(cam())
        assertTrue(v.any { it.startsWith("RAW: yes") }); assertTrue(v.contains("Ultra HDR output: yes")); assertTrue(v.contains("extensions: NIGHT"))
        assertTrue(v.contains("reprocessing: yes".replace("yes", "yuv"))); assertTrue(v.any { it.startsWith("burst depth 8") && "enough" in it }); assertTrue(v.contains("logical camera: 2 lenses"))
    }

    @Test fun missingFeaturesAreSaidPlainly() {
        val v = ProbeFormat.verdicts(cam(raw = emptyList(), caps = listOf("BACKWARD_COMPATIBLE"), uhdr = emptyList(), ext = emptyList(), depth = 2, repro = emptyList(), physical = emptyList()))
        assertTrue(v.contains("RAW: no")); assertTrue(v.any { it.startsWith("Ultra HDR output: no") }); assertTrue(v.any { it.startsWith("extensions: none") })
        assertTrue(v.contains("reprocessing: none")); assertTrue(v.any { "short" in it }); assertTrue(v.contains("manual exposure: no")); assertTrue(v.contains("single lens"))
        val unknownDepth = ProbeFormat.verdicts(cam(depth = null)); assertTrue(unknownDepth.any { it.startsWith("burst depth ?") })
    }

    @Test fun rawSizesWithoutTheCapabilityDoNotCount() {
        assertTrue(ProbeFormat.verdicts(cam(caps = listOf("BACKWARD_COMPATIBLE"))).contains("RAW: no"))
    }

    @Test fun theReportNamesTheDeviceAndEveryCamera() {
        val text = ProbeFormat.text(ProbeReport("Google Pixel 10a", "16 (API 36)", listOf(cam(), cam().copy(id = "1", facing = "front"))))
        assertTrue("Google Pixel 10a" in text); assertTrue("== Camera 0 (back) ==" in text); assertTrue("== Camera 1 (front) ==" in text)
        assertTrue("f/1.70" in text); assertTrue("6.40 x 4.80 mm" in text); assertTrue("Logical camera over: 2, 3" in text)
    }
}
