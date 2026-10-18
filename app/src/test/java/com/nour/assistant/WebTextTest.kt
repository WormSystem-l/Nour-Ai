package com.nour.assistant

import com.nour.assistant.tools.WebText
import com.nour.assistant.wake.*
import org.junit.Assert.*
import org.junit.Test

class WebTextTest {
    @Test fun ddgDecode_andSsrfGuard() {
        assertEquals("https://example.com/a?b=1", WebText.decodeDdg("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1&rut=x"))
        assertFalse(WebText.isSafeUrl("http://127.0.0.1/"))
        assertFalse(WebText.isSafeUrl("http://192.168.1.2/"))
        assertFalse(WebText.isSafeUrl("file:///etc/passwd"))
        assertTrue(WebText.isSafeUrl("https://www.google.com"))
    }

    @Test fun condense_prefersRelevantParagraphs_withinBudget() {
        val p = listOf("Intro about cats and many many words here", "Pricing: the plan costs 20 dollars per month", "Contact us at the address below")
        val out = WebText.condense(p, "plan pricing", 60)
        assertTrue(out.contains("Pricing") && out.length <= 65)
    }

    @Test fun wake_acceptsArabicAndEnglishCalls_rejectsNames() {
        for (t in listOf("يا نور", "hey nour", "ya nour", "yeah nor", "هاي نور")) assertTrue(t, WakeMatcher.matches(t))
        for (t in listOf("يا", "يا محمد", "hey mom", "yeah man", "you know")) assertFalse(t, WakeMatcher.matches(t))
    }

    @Test fun vad_detectsFarSpeech_andIgnoresSteadyNoise() {
        val seg = Segmenter(Vad(1.9f)); var now = 0L
        repeat(10) { seg.step(100f, now); now += 166 }
        assertEquals(Segmenter.Ev.START, seg.step(260f, now))
        val s2 = Segmenter(Vad(2.4f)); var fired = false; var t = 0L
        repeat(200) { if (s2.step(180f + (it % 5) * 6f, t) == Segmenter.Ev.START) fired = true; t += 166 }
        assertFalse(fired)
    }

    @Test fun agc_boostsQuietSpeech_andClips() {
        val agc = Agc(); var g = 1f; repeat(5) { g = agc.gain(260f, true) }
        assertTrue(g in 5f..10f)
        val dst = ShortArray(2); Agc.apply(shortArrayOf(30000, -30000), 2, 5f, dst)
        assertEquals(32767, dst[0].toInt()); assertEquals(-32767, dst[1].toInt())
    }
}
