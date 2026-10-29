package com.nour.assistant

import com.nour.assistant.tools.*
import org.junit.Assert.*
import org.junit.Test

class SearchChainTest {
    private class Fake(override val id: String, var fail: Boolean = false) : SearchEngine {
        var calls = 0
        override fun search(query: String, max: Int): SearchOut {
            calls++; if (fail) throw RuntimeException("boom")
            return SearchOut("نتيجة:$query", listOf(Source("t", "https://x.com/$id", "", query)))
        }
    }

    @Test fun fallsBack_cachesAndBreaksCircuit() {
        var t = 0L
        val a = Fake("A"); val b = Fake("B")
        val chain = SearchChain(listOf(a, b), now = { t })
        assertEquals("A", chain.search("q").out!!.engine)
        chain.search("q"); assertEquals(1, a.calls)                    // كاش
        t += 11 * 60_000; a.fail = true
        assertEquals("B", chain.search("q2").out!!.engine)              // احتياطي
        chain.search("q3")                                              // A فشل مرتين → اتحجب
        val c = a.calls; assertEquals("B", chain.search("q4").out!!.engine); assertEquals(c, a.calls)
        t += 11 * 60_000; a.fail = false
        assertEquals("A", chain.search("q5").out!!.engine)              // رجع بعد المدة
    }

    @Test fun allFail_returnsNullWithReasons() {
        val chain = SearchChain(listOf(Fake("A", true), Fake("B", true)))
        val r = chain.search("x")
        assertNull(r.out); assertEquals(2, r.errors.size)
    }

    @Test fun searchIntent_forcesWebForTimeSensitive() {
        for (q in listOf("سعر الدولار النهارده", "latest iPhone", "نتيجة ماتش الأهلي", "weather in Cairo today")) assertTrue(q, SearchIntent.needsWeb(q))
        for (q in listOf("اشرحلي الجاذبية", "write a poem", "hi")) assertFalse(q, SearchIntent.needsWeb(q))
    }

    @Test fun parallelMcp_parsesSseJsonAndMarkdown() {
        val sse = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}\n\n"
        assertNotNull(ParallelMcpEngine.parseRpcBody(sse, "text/event-stream"))
        assertNull(ParallelMcpEngine.parseRpcBody("garbage", "application/json"))
        val json = "{\"results\":[{\"url\":\"https://www.example.com/a\",\"title\":\"Dollar\",\"excerpts\":[\"EGP 48.6\"]}]}"
        val o = ParallelMcpEngine.parseToolText(json, "dollar", 5)
        assertEquals(1, o.sources.size); assertTrue(o.text.contains("EGP 48.6"))
        val md = ParallelMcpEngine.parseToolText("## Dollar today\nhttps://www.example.com/a\nEGP 48.6 per USD " + "x".repeat(80), "dollar", 5)
        assertEquals("Dollar today", md.sources[0].title)
    }
}
