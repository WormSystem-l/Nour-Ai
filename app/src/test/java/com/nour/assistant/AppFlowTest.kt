package com.nour.assistant

import android.content.Context
import android.content.Intent
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppFlowTest {
    private lateinit var srv: FakeAiServer
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Before fun up() {
        srv = FakeAiServer()
        Prefs(ctx).apply { baseUrl = srv.baseUrl; apiKey = "sk-test"; model = "m1" }
        ChatStore.clear()
    }
    @After fun down() { srv.stop() }

    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) {
            ShadowLooper.idleMainLooper(40, java.util.concurrent.TimeUnit.MILLISECONDS); Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
        assertTrue("timeout", cond())
    }

    @Test fun chatFlow_endToEnd_withFakeServer() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.findViewById<EditText>(R.id.input).setText("  ازيك؟  ")
        a.findViewById<android.view.View>(R.id.btnSend).performClick()
        assertTrue(a.busy)
        assertEquals("…", a.messages.last().content)
        waitUntil { !a.busy }
        assertEquals(listOf("ازيك؟", "أهلاً! أنا Nour."), a.messages.map { it.content })
        assertEquals("", a.findViewById<EditText>(R.id.input).text.toString())
        assertEquals(2, a.findViewById<RecyclerView>(R.id.list).adapter!!.itemCount)
        // رسالة تانية: لازم التاريخ يتبعت
        srv.body = FakeAiServer.okBody("تمام")
        a.send("وانت؟"); waitUntil { !a.busy }
        val msgs = org.json.JSONObject(srv.requests.last().second).getJSONArray("messages")
        assertEquals(4, msgs.length()) // system + user + assistant + user
        assertEquals("تمام", a.messages.last().content)
    }

    @Test fun doubleSend_whileBusy_isIgnored() {
        srv.delayMs = 500
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.send("1"); a.send("2")
        waitUntil { !a.busy }
        assertEquals(2, a.messages.size)
        assertEquals(1, srv.requests.size)
    }

    @Test fun serverError_showsErrorBubble_andAppKeepsWorking() {
        srv.status = 401; srv.body = """{"error":{"message":"bad key"}}"""
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.send("x"); waitUntil { !a.busy }
        assertTrue(a.messages.last().isError)
        srv.status = 200; srv.body = FakeAiServer.okBody("ok")
        a.send("y"); waitUntil { !a.busy }
        assertEquals("ok", a.messages.last().content)
        // الرسالة الغلط متتبعتش للـ API
        val sent = org.json.JSONObject(srv.requests.last().second).getJSONArray("messages").toString()
        assertFalse(sent.contains("bad key"))
    }

    @Test fun assistIntent_doesNotOpenGoogleVoice() {
        val a = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_ASSIST)).setup().get()
        assertNull(shadowOf(a).nextStartedActivity)
    }

    @Test fun normalLaunch_doesNotStartVoice() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNull(shadowOf(a).nextStartedActivity)
    }

    @Test fun settings_saveAndValidate() {
        val s = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        s.findViewById<EditText>(R.id.baseUrl).setText("api.groq.com")
        s.findViewById<android.view.View>(R.id.save).performClick()
        assertFalse(s.isFinishing)
        assertNotNull(s.findViewById<EditText>(R.id.baseUrl).error)
        s.findViewById<EditText>(R.id.baseUrl).setText("https://api.groq.com/openai/v1/")
        s.findViewById<EditText>(R.id.model).setText(" llama-3.1-8b-instant ")
        s.findViewById<EditText>(R.id.apiKey).setText(" gsk_123 ")
        s.findViewById<android.view.View>(R.id.save).performClick()
        assertTrue(s.isFinishing)
        val p = Prefs(ctx)
        assertEquals("https://api.groq.com/openai/v1", p.baseUrl)
        assertEquals("llama-3.1-8b-instant", p.model)
        assertEquals("gsk_123", p.apiKey)
    }

    @Test fun rotation_keepsState_noCrash() {
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        c.configurationChange(android.content.res.Configuration().apply {
            orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE })
        assertFalse(c.get().isFinishing)
    }

    @Test fun manifest_declaresAssistantComponents() {
        val pm = ctx.packageManager
        val assist = pm.queryIntentActivities(Intent(Intent.ACTION_ASSIST).setPackage(ctx.packageName), 0)
        assertEquals(1, assist.size)
        val vis = pm.queryIntentServices(Intent("android.service.voice.VoiceInteractionService").setPackage(ctx.packageName), 0)
        assertEquals(1, vis.size)
        assertEquals("android.permission.BIND_VOICE_INTERACTION", vis[0].serviceInfo.permission)
        val rec = pm.queryIntentServices(Intent("android.speech.RecognitionService").setPackage(ctx.packageName), 0)
        assertEquals(1, rec.size)
    }

    @Test fun voiceInteractionXml_isValid() {
        val parser = ctx.resources.getXml(R.xml.interaction_service)
        var attrs = mapOf<String, String>()
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "voice-interaction-service")
                attrs = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
        }
        assertEquals("com.nour.assistant.assist.NourSessionService", attrs["sessionService"])
        assertEquals("com.nour.assistant.assist.NourRecognitionService", attrs["recognitionService"])
        assertEquals("true", attrs["supportsAssist"])
        // الكلاسات موجودة فعلاً
        Class.forName(attrs["sessionService"]!!); Class.forName(attrs["recognitionService"]!!)
        Class.forName(attrs["settingsActivity"]!!)
    }
}
