package com.nour.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nour.assistant.tools.NourTools
import com.nour.assistant.tools.Source
import com.nour.assistant.tools.ToolEvent
import com.nour.assistant.ui.ChatDrawer
import com.nour.assistant.ui.NourOrbView
import com.nour.assistant.ui.NourTheme
import com.nour.assistant.ui.SplashView
import com.nour.assistant.ui.Typewriter
import com.nour.assistant.voice.VoiceInput
import com.nour.assistant.wake.WakeController
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : AppCompatActivity() {
    /** المحادثة مشتركة مع النافذة الصغيرة */
    @VisibleForTesting val messages get() = ChatStore.messages
    private lateinit var adapter: ChatAdapter
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnMic: ImageButton
    private lateinit var btnWeb: ImageButton
    private lateinit var btnModel: TextView
    private lateinit var btnDefault: TextView
    private lateinit var home: View
    private lateinit var homeOrb: NourOrbView
    private lateinit var greeting: TextView
    private lateinit var subtitle: TextView
    private lateinit var topBar: View
    private lateinit var composer: View
    private lateinit var rootFrame: FrameLayout
    private lateinit var drawer: ChatDrawer
    private var splash: SplashView? = null
    private lateinit var ai: AiClient
    private lateinit var tools: NourTools
    private lateinit var orb: NourOrbView
    private lateinit var prefs: Prefs
    private lateinit var voice: VoiceInput
    private var appliedTheme = ""
    private var askedSetup = false
    private var shownCount = 0
    private var forceSearch = false
    @VisibleForTesting var busy = false
        private set

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Toast.makeText(this, if (ok) R.string.mic_granted else R.string.mic_permission, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        appliedTheme = prefs.themeMode
        tools = NourTools(this)
        ai = AiClient(tools) { prefs.toConfig() }
        rootFrame = findViewById(R.id.root)
        orb = findViewById(R.id.orb)
        list = findViewById(R.id.list)
        input = findViewById(R.id.input)
        btnSend = findViewById(R.id.btnSend)
        btnMic = findViewById(R.id.btnMic)
        btnWeb = findViewById(R.id.btnWeb)
        btnModel = findViewById(R.id.btnModel)
        btnDefault = findViewById(R.id.btnDefault)
        home = findViewById(R.id.home)
        homeOrb = findViewById(R.id.homeOrb)
        greeting = findViewById(R.id.greeting)
        subtitle = findViewById(R.id.subtitle)
        topBar = findViewById(R.id.topBar)
        composer = findViewById(R.id.composer)

        applyTheme()

        adapter = ChatAdapter(messages) { src -> com.nour.assistant.ui.SourcesSheet.show(this, src) }
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter
        shownCount = messages.size

        // القايمة الجانبية (المحادثات القديمة)
        drawer = ChatDrawer(this).apply {
            currentId = { ChatStore.currentId }
            onNewChat = { newChat() }
            onOpenChat = { id -> openChat(id) }
            onDeleteChat = { id -> deleteChat(id) }
            onSettings = { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        rootFrame.addView(drawer, FrameLayout.LayoutParams(-1, -1))
        findViewById<View>(R.id.btnMenu).setOnClickListener { drawer.open() }
        findViewById<View>(R.id.btnNew).setOnClickListener { newChat() }

        voice = VoiceInput(this, object : VoiceInput.Listener {
            override fun onPartial(text: String) { input.setText(text); input.setSelection(text.length) }
            override fun onFinal(text: String) { input.setText(""); send(text) }
            override fun onStopped() { btnMic.alpha = 1f }
            override fun onError(message: String, needsPermission: Boolean) {
                if (needsPermission) requestMic()
                else Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
        })

        btnSend.setOnClickListener { submitInput() }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submitInput(); true } else false }
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(e: Editable?) { updateSendMic() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        btnMic.setOnClickListener { toggleVoice() }
        btnWeb.setOnClickListener { forceSearch = !forceSearch; styleWebButton() }
        btnModel.setOnClickListener { pickModel() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnDefault.setOnClickListener { openDefaultAssistantSettings() }
        styleWebButton(); updateSendMic()

        if (savedInstanceState == null) handleIntent(intent)
        if (savedInstanceState == null) startSplashIfAllowed()
    }

    // ------------------------------------------------------------------ الشكل والثيم

    private fun applyTheme() {
        val pal = NourTheme.palette(this)
        NourTheme.applyScreen(this, rootFrame)
        findViewById<TextView>(R.id.title).setTextColor(pal.text)
        for (id in intArrayOf(R.id.btnMenu, R.id.btnNew, R.id.btnSettings)) findViewById<ImageButton>(id).setColorFilter(pal.text)
        composer.background = NourTheme.glass(this, 30f, pal, strong = true)
        input.setTextColor(pal.text); input.setHintTextColor(pal.textFaint)
        btnModel.background = NourTheme.glass(this, 18f, pal); btnModel.setTextColor(pal.text)
        btnMic.background = NourTheme.circleGlass(this, pal); btnMic.setColorFilter(pal.text)
        btnSend.background = NourTheme.accentFill(this, 24f, pal, oval = true); btnSend.setColorFilter(0xFFFFFFFF.toInt())
        btnDefault.background = NourTheme.glass(this, 16f, pal); btnDefault.setTextColor(pal.text)
        greeting.setTextColor(pal.text); subtitle.setTextColor(pal.textDim)
        // لو الجهاز أبطأ، الكرة الكبيرة بتتنفس بس (مفيش رسوم زيادة)
        homeOrb.mode = NourOrbView.Mode.IDLE
    }

    private fun styleWebButton() {
        val pal = NourTheme.palette(this)
        btnWeb.background = if (forceSearch) NourTheme.accentFill(this, 18f, pal, oval = true) else NourTheme.circleGlass(this, pal)
        btnWeb.setColorFilter(if (forceSearch) 0xFFFFFFFF.toInt() else pal.text)
        btnWeb.alpha = if (forceSearch) 1f else 0.85f
        btnWeb.contentDescription = if (forceSearch) L.t("البحث في الويب: شغال", "Web search: on") else L.t("البحث في الويب", "Web search")
    }

    private fun updateSendMic() {
        val has = input.text.isNotBlank()
        btnSend.visibility = if (has) View.VISIBLE else View.GONE
        btnMic.visibility = if (has) View.GONE else View.VISIBLE
    }

    private fun refreshHome() {
        val empty = messages.isEmpty()
        if (empty) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val ar = java.util.Locale.getDefault().language == "ar"
            greeting.text = Greeting.forHour(h, ar)
            subtitle.text = if (!prefs.hasProvider()) L.t("أضف مزوّد API من الإعدادات عشان نبدأ", "Add an AI provider in Settings to start") else Greeting.subtitle(ar)
            if (home.visibility != View.VISIBLE) { home.alpha = 0f; home.visibility = View.VISIBLE; home.animate().alpha(1f).setDuration(260).start() }
        } else if (home.visibility == View.VISIBLE) {
            home.animate().alpha(0f).setDuration(180).withEndAction { home.visibility = View.GONE }.start()
        }
        list.visibility = if (empty) View.INVISIBLE else View.VISIBLE
    }

    // ------------------------------------------------------------------ انيميشن الفتح

    private fun startSplashIfAllowed() {
        val scale = try { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (_: Exception) { 1f }
        if (scale == 0f || Build.FINGERPRINT.contains("robolectric", true)) return
        val s = SplashView(this)
        splash = s
        // الواجهة مخفية لحد ما القطرة تطلع لمكانها
        val parts = listOf(topBar, composer, greeting, subtitle)
        parts.forEach { it.alpha = 0f }
        homeOrb.alpha = 0f; orb.alpha = 0f
        val d = resources.displayMetrics.density
        s.onEnter = { p ->
            val a = ((p - 0.15f) / 0.85f).coerceIn(0f, 1f)
            topBar.alpha = a; topBar.translationY = -18f * d * (1f - a)
            composer.alpha = a; composer.translationY = 26f * d * (1f - a)
            val b = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
            greeting.alpha = b; subtitle.alpha = b
            greeting.translationY = 12f * d * (1f - b); subtitle.translationY = 16f * d * (1f - b)
            orb.alpha = b
        }
        s.onFinished = {
            parts.forEach { it.alpha = 1f; it.translationY = 0f }
            homeOrb.alpha = 1f; orb.alpha = 1f
            rootFrame.removeView(s); splash = null
        }
        rootFrame.addView(s, FrameLayout.LayoutParams(-1, -1))
        // مكان الكرة الكبيرة بعد ما الواجهة تتقاس
        rootFrame.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (homeOrb.width == 0) return
                rootFrame.viewTreeObserver.removeOnGlobalLayoutListener(this)
                val loc = IntArray(2); homeOrb.getLocationInWindow(loc)
                val rl = IntArray(2); rootFrame.getLocationInWindow(rl)
                s.setTarget(loc[0] - rl[0] + homeOrb.width / 2f, loc[1] - rl[1] + homeOrb.height / 2f, homeOrb.width.toFloat())
            }
        })
    }

    // ------------------------------------------------------------------ دورة الحياة

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // اتغيّر الثيم من الإعدادات: نعيد بناء الشاشة بالألوان الجديدة
        if (prefs.themeMode != appliedTheme) { recreate(); return }
        btnDefault.visibility = if (isDefaultAssistant()) View.GONE else View.VISIBLE
        // بعد الريستارت أندرويد 14 مبيسمحش بتشغيل المايك من الخلفية: فتح التطبيق مرة بيشغّل النداء
        WakeController.ensureService(this)
        com.nour.assistant.gesture.GestureService.ensure(this)
        refreshModelLabel()
        // لو اتكلمت في النافذة الصغيرة، المحادثة تظهر هنا
        if (messages.size != shownCount) { adapter.notifyDataSetChanged(); shownCount = messages.size }
        if (messages.isNotEmpty()) list.scrollToPosition(messages.size - 1)
        refreshHome()
        // أول مرة: لو مفيش مزوّد نفتح إضافة API، ولو فيه ومفيش موديل نفتح قايمة الموديلات
        if (!askedSetup && splash == null) {
            if (!prefs.hasProvider()) { askedSetup = true; btnModel.post { ProvidersDialog.show(this) { onProvidersChanged() } } }
            else if (prefs.model.isBlank()) { askedSetup = true; btnModel.post { pickModel() } }
        }
    }

    override fun onPause() { voice.cancel(); super.onPause() }
    override fun onDestroy() { voice.release(); super.onDestroy() }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() { if (drawer.isOpen) drawer.close() else super.onBackPressed() }

    /** زر المساعد بيفتح التطبيق من غير ما يشغّل المايك. المايك بس لما تدوس عليه. */
    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) requestMic()
        // النداء فتح التطبيق مباشرة (لو Nour مش المساعد الافتراضي)
        if (i?.getBooleanExtra(WakeController.EXTRA_AUTO_MIC, false) == true) {
            i.removeExtra(WakeController.EXTRA_AUTO_MIC)
            btnMic.post { toggleVoice() }
        }
    }

    // ------------------------------------------------------------------ المحادثات

    private fun newChat() {
        if (busy) return
        voice.cancel()
        ChatStore.startNew()
        adapter.notifyDataSetChanged(); shownCount = 0
        input.setText(""); forceSearch = false; styleWebButton()
        refreshHome()
    }

    private fun openChat(id: String) {
        if (busy) return
        if (ChatStore.open(this, id)) {
            adapter.notifyDataSetChanged(); shownCount = messages.size
            if (messages.isNotEmpty()) list.scrollToPosition(messages.size - 1)
            refreshHome()
        }
    }

    private fun deleteChat(id: String) {
        Thread { try { ConversationStore.repo(this).delete(id) } catch (_: Exception) {} }.start()
        if (id == ChatStore.currentId) newChat()
    }

    // ------------------------------------------------------------------ الموديل والمزوّدين

    private fun refreshModelLabel() {
        val ap = prefs.activeProvider()
        val m = prefs.model
        btnModel.text = when {
            !prefs.hasProvider() -> "＋ " + L.t("أضف مزوّد", "Add provider")
            m.isBlank() -> L.t("اختار موديل", "Pick a model") + " ▾"
            else -> m + " ▾"
        }
        btnModel.contentDescription = ap?.name ?: ""
    }

    private fun pickModel() {
        if (!prefs.hasProvider()) { ProvidersDialog.show(this) { onProvidersChanged() }; return }
        ModelPicker.show(this) { _, _ -> refreshModelLabel() }
    }

    private fun onProvidersChanged() {
        refreshModelLabel(); refreshHome()
        if (prefs.hasProvider() && prefs.model.isBlank()) btnModel.post { pickModel() }
    }

    // ------------------------------------------------------------------ الصوت والإرسال

    private fun requestMic() = micPermission.launch(Manifest.permission.RECORD_AUDIO)

    private fun toggleVoice() {
        if (voice.isListening) { voice.stop(); return }
        if (busy) return
        if (!voice.hasPermission()) { requestMic(); return }
        btnMic.alpha = 0.5f
        voice.start()
    }

    private fun submitInput() {
        val t = input.text.toString().trim()
        if (t.isEmpty() || busy) return
        input.setText("")
        send(t)
    }

    @VisibleForTesting
    fun send(text: String) {
        if (busy) return
        busy = true
        btnSend.isEnabled = false
        // البحث إجباري لو داس 🌐 أو السؤال بيتغير مع الوقت (أسعار/أخبار/نتايج/طقس...)
        tools.forceSearch = forceSearch || com.nour.assistant.tools.SearchIntent.needsWeb(text)
        val history = ChatStore.history() + Msg("user", text)
        messages += Msg("user", text)
        messages += Msg("assistant", "…")
        val placeholder = messages.size - 1
        shownCount = messages.size
        refreshHome()
        adapter.notifyItemRangeInserted(messages.size - 2, 2)
        list.scrollToPosition(placeholder)

        var curStatus: String? = null
        var finalSources: List<Source> = emptyList()
        fun update(m: Msg) {
            if (placeholder < messages.size) {
                messages[placeholder] = m.copy(status = curStatus)
                adapter.notifyItemChanged(placeholder)
                list.scrollToPosition(placeholder)
            }
        }
        fun done() {
            busy = false; btnSend.isEnabled = true; orb.mode = NourOrbView.Mode.IDLE
            tools.forceSearch = false
            curStatus = null
            if (placeholder < messages.size) { messages[placeholder] = messages[placeholder].copy(status = null, sources = finalSources); adapter.notifyItemChanged(placeholder) }
            ChatStore.persist(this)
            val pending = tools.drainPending()
            if (pending.isNotEmpty()) list.postDelayed({
                pending.forEach { NourTools.safeStart({ i -> startActivity(i) }, it) }
            }, 1100)
        }
        // "جاري البحث" أثناء البحث (كلمة بس، من غير إيموجي)
        val onEvent: (ToolEvent) -> Unit = { ev ->
            runOnUiThread {
                curStatus = when (ev) { is ToolEvent.Start -> NourTools.statusLabel(ev.name); is ToolEvent.End -> null }
                if (placeholder < messages.size) update(messages[placeholder])
            }
        }
        orb.mode = NourOrbView.Mode.THINKING

        val tw = Typewriter(onText = { update(Msg("assistant", it)) }, onDone = { update(Msg("assistant", it.trim())); done() })
        lifecycleScope.launch {
            when (val r = ai.chatStream(history, onEvent = onEvent) { tw.feed(it) }) {
                is AiResult.Ok -> { finalSources = r.sources; tw.finish(r.text) }
                is AiResult.Err -> { tw.cancel(); update(Msg("assistant", r.message, isError = true)); done() }
            }
        }
    }

    // ------------------------------------------------------------------ المساعد الافتراضي

    private fun isDefaultAssistant(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT))
                return rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }
        val cur = Settings.Secure.getString(contentResolver, "assistant") ?: return false
        return cur.startsWith("$packageName/")
    }

    private fun openDefaultAssistantSettings() {
        val tries = listOf(
            Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (t in tries) {
            try {
                startActivity(t)
                Toast.makeText(this, R.string.choose_hint, Toast.LENGTH_LONG).show()
                return
            } catch (_: Exception) { }
        }
    }

    companion object {
        const val EXTRA_REQUEST_MIC = "request_mic"
    }
}
