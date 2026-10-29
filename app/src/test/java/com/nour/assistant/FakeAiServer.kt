package com.nour.assistant

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** سيرفر وهمي بيقلد OpenAI Chat Completions عشان نختبر من غير إنترنت */
class FakeAiServer(
    @Volatile var status: Int = 200,
    @Volatile var body: String = okBody("أهلاً! أنا Nour."),
    @Volatile var delayMs: Long = 0,
    @Volatile var contentType: String = "application/json"
) {
    val requests = CopyOnWriteArrayList<Pair<Map<String, String>, String>>()
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/v1/chat/completions") return MockResponse().setResponseCode(404)
                val headers = request.headers.toMultimap().mapValues { it.value.first() }.mapKeys { it.key.lowercase() }
                requests += headers to request.body.readUtf8()
                return MockResponse().setResponseCode(status)
                    .setHeader("Content-Type", contentType)
                    .setBody(body)
                    .setBodyDelay(delayMs, TimeUnit.MILLISECONDS)
            }
        }
        start()
    }
    val baseUrl: String = server.url("/v1").toString().trimEnd('/')
    fun stop() = server.shutdown()

    companion object {
        fun okBody(text: String) =
            """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":${org.json.JSONObject.quote(text)}}}]}"""
    }
}
