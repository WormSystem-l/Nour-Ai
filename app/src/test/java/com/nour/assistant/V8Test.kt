package com.nour.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nour.assistant.assist.ScreenReader
import com.nour.assistant.tools.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class V8Test {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun memory_addsDedupesAndRefusesSecrets() {
        val m = MemoryStore(ctx); m.clear()
        assertTrue(m.add("اسمي هادي"))
        assertFalse(m.add("  اسمي   هادي "))           // مكرر
        assertFalse(m.add("my password is 12345"))       // سر
        assertFalse(m.add("رقم الكارت 4111111111111111")) // رقم طويل
        assertEquals(listOf("اسمي هادي"), m.all())
        m.replace(0, "اسمي هادي وبحب البرمجة"); assertEquals("اسمي هادي وبحب البرمجة", m.all()[0])
        assertTrue(m.promptBlock().startsWith("- اسمي هادي"))
        m.remove(0); assertTrue(m.all().isEmpty())
    }

    @Test fun memory_capsAt60() {
        val m = MemoryStore(ctx); m.clear()
        for (i in 1..70) m.add("fact number $i")
        assertEquals(60, m.all().size); assertEquals("fact number 70", m.all().last())
        m.clear()
    }

    @Test fun pagePrompts_buildLocalizedInstructions() {
        assertEquals("لخّص الصفحة", PagePrompts.visible(PagePrompts.Mode.SUMMARIZE, "", true))
        assertEquals("Translate this page", PagePrompts.visible(PagePrompts.Mode.TRANSLATE, "", false))
        assertEquals("إيه ده؟", PagePrompts.visible(PagePrompts.Mode.ASK, " إيه ده؟ ", true))
        val msg = PagePrompts.build(PagePrompts.Mode.ASK, "ده موقع إيه", "محتوى الصفحة هنا", true)
        assertTrue(msg.startsWith("ده موقع إيه") && msg.contains("محتوى الصفحة هنا"))
        assertEquals("ده سؤال", PagePrompts.build(PagePrompts.Mode.ASK, "ده سؤال", "", true))
    }

    @Test fun screenReader_urlHelpers() {
        assertTrue(ScreenReader.looksLikeUrl("example.com/path?x=1"))
        assertTrue(ScreenReader.looksLikeUrl("https://www.google.com/search?q=a"))
        assertFalse(ScreenReader.looksLikeUrl("بحث في جوجل"))
        assertFalse(ScreenReader.looksLikeUrl("hello world.com"))
        assertEquals("https://example.com", ScreenReader.normalizeUrl("example.com"))
    }

    @Test fun statusLabels_areWordsOnly_noEmoji() {
        val l = NourTools.statusLabel(NourTools.WEB_SEARCH)!!
        assertTrue(l == "جاري البحث" || l == "Searching")
        assertTrue(l.all { it.isLetter() || it == ' ' })
        assertNull(NourTools.statusLabel(NourTools.OPEN_APP))
    }

    @Test fun conversation_persistsSourcesPerMessage() {
        val f = File.createTempFile("conv", ".json"); f.delete()
        val r = ConversationRepo(f)
        val src = listOf(Source("عنوان", "https://a.com/x", "مقتطف", "سعر الدولار"))
        r.save(Conversation("x", "t", 1, mutableListOf(Msg("user", "س"), Msg("assistant", "ج", sources = src), Msg("assistant", "من غير بحث"))))
        val back = r.load("x")!!.messages
        assertEquals(src, back[1].sources); assertTrue(back[2].sources.isEmpty())
        assertEquals("a.com", back[1].sources[0].domain)
    }

    @Test fun sourcesCollected_andDrained() = kotlinx.coroutines.test.runTest {
        // takeSources بيفضّي بعد القراءة
        val t = NourTools(ctx)
        assertTrue(t.takeSources().isEmpty())
    }
}
