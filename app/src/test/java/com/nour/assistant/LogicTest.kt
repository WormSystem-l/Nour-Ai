package com.nour.assistant

import android.content.ComponentName
import com.nour.assistant.ui.Typewriter
import com.nour.assistant.voice.RecognizerPicker
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class LogicTest {
    @Test fun typewriter_revealsWordByWord() {
        val s = "أهلاً يا صاحبي إزيك"
        val steps = mutableListOf<String>()
        var i = 0
        while (i < s.length) { i = Typewriter.nextIndex(s, i); steps += s.substring(0, i) }
        assertEquals(listOf("أهلاً", "أهلاً يا", "أهلاً يا صاحبي", "أهلاً يا صاحبي إزيك"), steps)
    }

    @Test fun typewriter_speedsUpWithBacklog() {
        val long = (1..200).joinToString(" ") { "w$it" }
        val first = Typewriter.nextIndex(long, 0)
        assertTrue(long.substring(0, first).split(" ").size >= 3)
    }

    @Test fun typewriter_edgeCases() {
        assertEquals(0, Typewriter.nextIndex("", 0))
        assertEquals(3, Typewriter.nextIndex("abc", 3))
        assertEquals(5, Typewriter.nextIndex("   ab", 0))
    }

    @Test fun picker_skipsOwnService_prefersGoogle() {
        val own = ComponentName("com.nour.assistant", "X")
        val sam = ComponentName("com.samsung.android.bixby.agent", "S")
        val g = ComponentName("com.google.android.googlequicksearchbox", "G")
        assertEquals(g, RecognizerPicker.choose(listOf(own, sam, g), "com.nour.assistant"))
        assertEquals(sam, RecognizerPicker.choose(listOf(own, sam), "com.nour.assistant"))
        assertNull(RecognizerPicker.choose(listOf(own), "com.nour.assistant"))
        val other = ComponentName("com.vendor.asr", "V")
        assertEquals(other, RecognizerPicker.choose(listOf(own, other), "com.nour.assistant"))
    }
}
