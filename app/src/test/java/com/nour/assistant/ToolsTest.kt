package com.nour.assistant

import com.nour.assistant.tools.AppResolver
import com.nour.assistant.tools.ToolCallParser
import com.nour.assistant.voice.MixedText
import com.nour.assistant.wake.WakeMatcher
import org.junit.Assert.*
import org.junit.Test

class ToolsTest {
    private val names = setOf("open_app")

    @Test fun parser_findsToolTagJson_andCleansText() {
        val (clean, calls) = ToolCallParser.extract(
            "الكابشن: البحر هدوء\n<tool>{\"name\":\"open_app\",\"arguments\":{\"app\":\"Instagram\"}}</tool>", names)
        assertEquals(1, calls.size)
        assertEquals("open_app", calls[0].name)
        assertEquals("Instagram", calls[0].args.getString("app"))
        assertEquals("الكابشن: البحر هدوء", clean)
    }

    @Test fun parser_acceptsOtherShapes() {
        val (_, a) = ToolCallParser.extract("{\"tool\":\"open_app\",\"args\":{\"app\":\"x\"}}", names)
        assertEquals(1, a.size)
        val (_, b) = ToolCallParser.extract("{\"function\":{\"name\":\"open_app\",\"arguments\":\"{\\\"app\\\":\\\"y\\\"}\"}}", names)
        assertEquals("y", b.single().args.getString("app"))
    }

    @Test fun parser_ignoresUnknownTools_andPlainText() {
        assertTrue(ToolCallParser.extract("{\"name\":\"delete_all\",\"arguments\":{}}", names).second.isEmpty())
        assertEquals("hello {world}", ToolCallParser.extract("hello {world}", names).first)
    }

    private val apps = listOf(AllowedApp("Instagram", "com.instagram.android"), AllowedApp("WhatsApp", "com.whatsapp"))

    @Test fun resolver_matchesArabicEnglishAndPackage() {
        assertEquals("com.instagram.android", AppResolver.resolve("انستجرام", apps)?.pkg)
        assertEquals("com.instagram.android", AppResolver.resolve("instagram", apps)?.pkg)
        assertEquals("com.whatsapp", AppResolver.resolve("واتساب", apps)?.pkg)
        assertEquals("com.whatsapp", AppResolver.resolve("com.whatsapp", apps)?.pkg)
    }

    @Test fun resolver_refusesAppsNotAllowed() {
        assertNull(AppResolver.resolve("tiktok", apps))
        assertNull(AppResolver.resolve("", apps))
    }

    @Test fun wakeMatcher_ignoresNamesAndHalfPhrases() {
        assertFalse(WakeMatcher.matches("يا"))
        assertFalse(WakeMatcher.matches("يا محمد"))
        assertFalse(WakeMatcher.matches("hey mom"))
        assertFalse(WakeMatcher.matches("يا نور تعالى هنا دلوقتي"))
    }

    @Test fun resolver_unlimitedApps_andAmbiguity() {
        val many = (1..30).map { AllowedApp("App$it", "com.app$it") } +
            listOf(AllowedApp("Camera", "com.cam.a"), AllowedApp("Camera Pro", "com.cam.b"))
        assertEquals(2, AppResolver.candidates("cam", many).size)
        assertEquals("com.cam.b", AppResolver.candidates("Camera Pro", many).single().pkg)
        assertTrue(AppResolver.candidates("tiktok", many).isEmpty())
    }

    @Test fun voiceLanguages_arabicPrimaryEvenOnEnglishPhone() {
        val (p, extra) = com.nour.assistant.voice.VoiceInput.languagesFor("auto", "en-US")
        assertEquals("ar-EG", p)
        assertTrue(extra.contains("en-US"))
    }

    @Test fun wakeMatcher_hearsAllThreePhrases() {
        assertTrue(WakeMatcher.matches("يا نور"))
        assertTrue(WakeMatcher.matches("يانور"))
        assertTrue(WakeMatcher.matches("hey nour"))
        assertTrue(WakeMatcher.matches("Hi Noor"))
        assertTrue(WakeMatcher.matches("هاي نور"))
        assertFalse(WakeMatcher.matches("نور"))
        assertFalse(WakeMatcher.matches("hello world"))
        assertFalse(WakeMatcher.matches(""))
    }

    @Test fun mixedText_prefersResultWithBothScripts() {
        assertEquals("افتح Instagram", MixedText.pickBest(listOf("افتح انستجرام", "افتح Instagram")))
        assertEquals("اول", MixedText.pickBest(listOf("اول", "تاني")))
        assertEquals("", MixedText.pickBest(emptyList()))
    }

    @Test fun sse_collectsToolCallsAcrossChunks() {
        val lines = sequenceOf(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Caption \"}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"open_app\",\"arguments\":\"{\\\"app\\\"\"}}]}}]}",
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\":\\\"Instagram\\\"}\"}}]}}]}",
            "data: [DONE]"
        )
        val p = AiClient.readSseFull(lines) {}
        assertEquals("Caption", p.text)
        assertEquals("open_app", p.calls.single().name)
        assertEquals("c1", p.calls.single().id)
        assertEquals("Instagram", p.calls.single().args.getString("app"))
    }

    @Test fun parseFull_readsNativeToolCalls() {
        val json = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"a","type":"function","function":{"name":"open_app","arguments":"{\"app\":\"WhatsApp\"}"}}]}}]}"""
        val p = AiClient.parseFull(json)
        assertNull(p.error)
        assertEquals("WhatsApp", p.calls.single().args.getString("app"))
    }
}
