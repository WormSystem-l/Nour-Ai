package com.nour.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.nour.assistant.overlay.OverlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ar-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayTest {
    private lateinit var srv: FakeAiServer
    private val app get() = ApplicationProvider.getApplicationContext<Context>()
    private var dismissed = 0; private var openedFull = 0; private var micAsked = 0

    @Before fun up() {
        srv = FakeAiServer()
        Prefs(app).apply { baseUrl = srv.baseUrl; apiKey = "k"; model = "m" }
        ChatStore.clear()
    }
    @After fun down() { srv.stop() }

    private fun build(): Pair<OverlayController, android.app.Activity> {
        val host = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val c = OverlayController(ContextThemeWrapper(host, R.style.Theme_NourAI),
            AiClient { Prefs(app).toConfig() }, object : OverlayController.Callbacks {
                override fun onDismiss() { dismissed++ }
                override fun onOpenFull() { openedFull++ }
                override fun onNeedMicPermission() { micAsked++ }
            })
        host.setContentView(c.root)
        idle(50)
        return c to host
    }

    private fun idle(ms: Long) { ShadowLooper.idleMainLooper(ms, TimeUnit.MILLISECONDS) }
    private fun waitUntil(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < end) { idle(40); Thread.sleep(10) }
        assertTrue("timeout", cond())
    }

    @Test fun opensCompact_micDoesNotAutoStart() {
        val (c, _) = build()
        c.onShow(); idle(800)
        assertEquals(OverlayController.State.COMPACT, c.state)
        assertFalse(c.expanded)
        assertEquals(View.GONE, c.root.findViewById<View>(R.id.responseScroll).visibility)
        assertEquals(0, micAsked)
    }

    @Test fun micTap_withoutPermission_asksForPermission() {
        val (c, _) = build()
        c.root.findViewById<View>(R.id.btnMic).performClick()
        assertEquals(1, micAsked)
    }

    @Test fun ask_expandsAndStreamsAnswer() {
        srv.contentType = "text/event-stream"
        srv.body = listOf("دي", " إجابة", " تجريبية").joinToString("") {
            "data: {\"choices\":[{\"delta\":{\"content\":${org.json.JSONObject.quote(it)}}}]}\n\n" } + "data: [DONE]\n\n"
        val (c, _) = build()
        c.onShow(); idle(600)
        val h0 = c.card.height
        c.ask("سؤال")
        assertTrue(c.expanded)
        waitUntil { c.state == OverlayController.State.DONE }
        idle(800)
        assertEquals("دي إجابة تجريبية", c.root.findViewById<TextView>(R.id.answer).text.toString())
        assertEquals("سؤال", c.root.findViewById<TextView>(R.id.question).text.toString())
        assertTrue("card should grow", c.card.height > h0)
        assertEquals(listOf("سؤال", "دي إجابة تجريبية"), ChatStore.history().map { it.content })
    }

    @Test fun error_showsMessage_andRecovers() {
        srv.status = 500; srv.body = "{}"
        val (c, _) = build()
        c.ask("x"); waitUntil { c.state == OverlayController.State.DONE }
        assertTrue(c.root.findViewById<TextView>(R.id.answer).text.contains("500"))
        srv.status = 200; srv.body = FakeAiServer.okBody("تمام")
        c.ask("y"); waitUntil { c.state == OverlayController.State.DONE }; idle(500)
        assertEquals("تمام", c.root.findViewById<TextView>(R.id.answer).text.toString())
    }

    @Test fun scrimTap_dismisses_and_expandBtn_opensFull() {
        val (c, _) = build()
        c.root.findViewById<View>(R.id.btnExpand).performClick()
        assertEquals(1, openedFull)
        c.root.findViewById<View>(R.id.scrim).performClick(); idle(600)
        assertEquals(1, dismissed)
    }

    @Test fun reset_returnsToCompact() {
        val (c, _) = build()
        c.ask("x"); waitUntil { c.state == OverlayController.State.DONE }
        c.onShow(); idle(100)
        assertFalse(c.expanded)
        assertEquals(OverlayController.State.COMPACT, c.state)
    }

    // ---------- صور للمعاينة ----------
    private fun snapshot(c: OverlayController, name: String) {
        val root = c.root
        val w = root.width; val h = root.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        // خلفية "تطبيق" ملونة وراه عشان نشوف تأثير الزجاج
        val p = Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFFF5F0E8.toInt(), 0xFFE2E8F0.toInt(), 0xFFCBD5E1.toInt()), null, Shader.TileMode.CLAMP) }
        cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1E293B.toInt(); textSize = 54f }
        val blocks = Paint().apply { color = 0xFF94A3B8.toInt() }
        cv.drawText("Some app behind", 60f, 200f, t)
        for (i in 0 until 9) cv.drawRoundRect(60f, 280f + i * 150f, w - 60f - (i % 3) * 120f, 380f + i * 150f, 24f, 24f, blocks)
        root.draw(cv)
        val out = File(System.getProperty("user.dir"), "build/overlay-$name.png")
        out.parentFile?.mkdirs(); out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SNAPSHOT ${out.absolutePath}")
    }

    @Test @Config(qualifiers = "en-w411dp-h891dp-xxhdpi")
    fun renderSnapshotsEnglish() = renders("en", "What is the capital of Egypt?",
        "Cairo is the capital of Egypt and its largest city. Founded in the 10th century, it is home to Islamic Cairo, the Grand Egyptian Museum, and is a short drive from the Giza pyramids.")

    @Test fun renderSnapshots() = renders("ar", "إيه هي عاصمة مصر؟",
        "القاهرة هي عاصمة مصر وأكبر مدنها، وبتعتبر من أكبر المدن في أفريقيا والشرق الأوسط. اتأسست في القرن العاشر الميلادي وفيها معالم كتير زي الأهرامات في الجيزة القريبة، والقاهرة الإسلامية، والمتحف المصري الكبير.")

    private fun renders(lang: String, q: String, a: String) {
        val (c, _) = build()
        c.onShow(); idle(1200)
        snapshot(c, "$lang-1-compact")
        c.root.findViewById<View>(R.id.wave).visibility = View.VISIBLE
        c.card.mode = com.nour.assistant.ui.GlassCardView.Mode.LISTENING
        c.root.findViewById<TextView>(R.id.status).setText(R.string.st_listening)
        idle(700)
        snapshot(c, "$lang-2-listening")
        c.root.findViewById<View>(R.id.wave).visibility = View.GONE
        srv.body = FakeAiServer.okBody(a)
        c.ask(q); waitUntil { c.state == OverlayController.State.DONE }; idle(1500)
        snapshot(c, "$lang-3-expanded")
    }
}
