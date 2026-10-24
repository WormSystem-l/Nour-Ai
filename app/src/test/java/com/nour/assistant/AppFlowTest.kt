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

    @Test fun settings_opensAndSwitchesTheme() {
        val c = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        assertFalse(c.get().isFinishing)
        Prefs(ctx).themeMode = "color"
        c.pause().resume()           // يعيد بناء الصفحة بالثيم الجديد من غير ما يقع
        assertFalse(c.get().isFinishing)
        assertEquals("color", Prefs(ctx).themeMode)
    }

    @Test fun providers_roundTrip_max5_andLegacyMigration() {
        val p = Prefs(ctx)
        p.baseUrl = "https://api.groq.com/openai/v1"; p.apiKey = "gsk_legacy"
        p.migrateLegacyProvider()
        val list = p.providers()
        assertEquals(1, list.size)
        assertEquals("gsk_legacy", list[0].apiKey)          // المفتاح بيرجع سليم بعد التشفير/فك التشفير
        assertEquals("https://api.groq.com/openai/v1", list[0].baseUrl)
        assertEquals(list[0].id, p.activeProvider()?.id)
        // الحد الأقصى 5
        val store = ProviderStore(ctx)
        store.save((1..8).map { Provider("id$it", "P$it", "https://h$it.example/v1", "k$it") })
        assertEquals(5, store.all().size)
        // اختيار موديل بيتحفظ مع المزوّد
        p.selectModel("id2", "llama-x")
        assertEquals("id2", p.lastProviderId); assertEquals("llama-x", p.model)
        assertEquals("id2", p.activeProvider()?.id)
        assertEquals("https://h2.example/v1", p.toConfig().baseUrl)
        assertEquals("k2", p.toConfig().apiKey)
    }

    @Test fun newChat_andHistory_persist() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ChatStore.startNew()
        ChatStore.messages += Msg("user", "سؤال تجريبي")
        ChatStore.messages += Msg("assistant", "رد تجريبي")
        val id = ChatStore.currentId
        ConversationStore.repo(ctx).save(Conversation(id, ConversationRepo.titleFrom("سؤال تجريبي"), System.currentTimeMillis(), ChatStore.history().toMutableList()))
        assertTrue(ConversationStore.repo(ctx).list().any { it.id == id })
        ChatStore.startNew()
        assertTrue(ChatStore.messages.isEmpty())
        assertTrue(ChatStore.open(ctx, id))
        assertEquals(2, ChatStore.messages.size)
        ConversationStore.repo(ctx).delete(id)
        assertNull(ConversationStore.repo(ctx).load(id))
        assertNotNull(a)
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
