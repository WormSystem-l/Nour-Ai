package com.nour.assistant

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AiClientTest {
    private lateinit var srv: FakeAiServer
    private fun client(key: String = "sk-test", base: String = srv.baseUrl, model: String = "m1", sys: String = "SYS") =
        AiClient { AiConfig(base, model, key, sys) }

    @Before fun up() { srv = FakeAiServer() }
    @After fun down() { srv.stop() }

    @Test fun success_returnsReply_andSendsCorrectRequest() = runBlocking {
        val r = client().chat(listOf(Msg("user", "مرحبا")))
        assertEquals(AiResult.Ok("أهلاً! أنا Nour."), r)
        val (headers, body) = srv.requests.single()
        assertEquals("Bearer sk-test", headers["authorization"])
        val json = JSONObject(body)
        assertEquals("m1", json.getString("model"))
        val msgs = json.getJSONArray("messages")
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("SYS", msgs.getJSONObject(0).getString("content"))
        assertEquals("مرحبا", msgs.getJSONObject(1).getString("content"))
    }

    @Test fun trailingSlashInBaseUrl_isHandled() = runBlocking {
        assertTrue(client(base = srv.baseUrl + "/").chat(listOf(Msg("user", "hi"))) is AiResult.Ok)
    }

    @Test fun errorMessages_areNotSentToApi_andHistoryCappedAt20() = runBlocking {
        val hist = (1..30).map { Msg("user", "q$it") } + Msg("assistant", "خطأ", isError = true) + Msg("user", "last")
        client().chat(hist)
        val msgs = JSONObject(srv.requests.single().second).getJSONArray("messages")
        assertEquals(21, msgs.length()) // system + 20
        for (i in 0 until msgs.length()) assertNotEquals("خطأ", msgs.getJSONObject(i).getString("content"))
        assertEquals("last", msgs.getJSONObject(20).getString("content"))
    }

    @Test fun missingKey_doesNotCallServer() = runBlocking {
        assertTrue(client(key = "").chat(listOf(Msg("user", "x"))) is AiResult.Err)
        assertTrue(srv.requests.isEmpty())
    }

    @Test fun badUrl_isRejected() = runBlocking {
        assertTrue(client(base = "api.openai.com").chat(listOf(Msg("user", "x"))) is AiResult.Err)
    }

    @Test fun http401_givesFriendlyMessage() = runBlocking {
        srv.status = 401; srv.body = """{"error":{"message":"Invalid API key"}}"""
        val r = client().chat(listOf(Msg("user", "x"))) as AiResult.Err
        assertTrue(r.message.contains("API Key")); assertTrue(r.message.contains("Invalid API key"))
    }

    @Test fun http429_and500() = runBlocking {
        srv.status = 429; srv.body = "rate"
        assertTrue((client().chat(listOf(Msg("user", "x"))) as AiResult.Err).message.contains("429"))
        srv.status = 503; srv.body = "<html>down</html>"
        assertTrue((client().chat(listOf(Msg("user", "x"))) as AiResult.Err).message.contains("503"))
    }

    @Test fun malformedOrEmptyResponse_doesNotCrash() = runBlocking {
        srv.body = "not json"
        assertTrue(client().chat(listOf(Msg("user", "x"))) is AiResult.Err)
        srv.body = """{"choices":[{"message":{"role":"assistant","content":""}}]}"""
        assertTrue(client().chat(listOf(Msg("user", "x"))) is AiResult.Err)
        srv.body = """{"choices":[]}"""
        assertTrue(client().chat(listOf(Msg("user", "x"))) is AiResult.Err)
    }

    @Test fun serverDown_doesNotCrash() = runBlocking {
        val base = srv.baseUrl; srv.stop()
        assertTrue(client(base = base).chat(listOf(Msg("user", "x"))) is AiResult.Err)
    }
}

class AiClientStreamTest {
    private lateinit var srv: FakeAiServer
    @Before fun up() { srv = FakeAiServer() }
    @After fun down() { srv.stop() }
    private val client get() = AiClient { AiConfig(srv.baseUrl, "m", "k", "s") }

    @Test fun sse_streamsPiecesInOrder() = runBlocking {
        srv.contentType = "text/event-stream"
        srv.body = listOf("أهلاً", " يا", " صاحبي", "!").joinToString("") {
            "data: {\"choices\":[{\"delta\":{\"content\":${JSONObject.quote(it)}}}]}\n\n"
        } + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        val pieces = mutableListOf<String>()
        val r = client.chatStream(listOf(Msg("user", "hi"))) { pieces += it }
        assertEquals(AiResult.Ok("أهلاً يا صاحبي!"), r)
        assertEquals(listOf("أهلاً", " يا", " صاحبي", "!"), pieces)
        assertTrue(JSONObject(srv.requests.single().second).getBoolean("stream"))
    }

    @Test fun sse_withoutCorrectContentType_stillWorks() = runBlocking {
        srv.contentType = "application/json"
        srv.body = "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n"
        assertEquals(AiResult.Ok("ok"), client.chatStream(listOf(Msg("user", "x"))) {})
    }

    @Test fun nonStreamingServer_fallsBackToFullJson() = runBlocking {
        val pieces = mutableListOf<String>()
        val r = client.chatStream(listOf(Msg("user", "x"))) { pieces += it }
        assertEquals(AiResult.Ok("أهلاً! أنا Nour."), r)
        assertEquals(listOf("أهلاً! أنا Nour."), pieces)
    }

    @Test fun sse_errorEvent_isReported() = runBlocking {
        srv.contentType = "text/event-stream"
        srv.body = "data: {\"error\":{\"message\":\"quota\"}}\n\n"
        val r = client.chatStream(listOf(Msg("user", "x"))) {}
        assertTrue(r is AiResult.Err && r.message.contains("quota"))
    }

    @Test fun sse_garbageLines_areIgnored() = runBlocking {
        srv.contentType = "text/event-stream"
        srv.body = ": keepalive\n\nevent: x\ndata: notjson\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}\n\ndata: [DONE]\n"
        assertEquals(AiResult.Ok("A"), client.chatStream(listOf(Msg("user", "x"))) {})
    }
}
