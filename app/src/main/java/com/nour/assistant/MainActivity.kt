package com.nour.assistant

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nour.assistant.tools.NourTools
import com.nour.assistant.ui.NourOrbView
import com.nour.assistant.ui.Typewriter
import com.nour.assistant.wake.WakeController
import com.nour.assistant.voice.VoiceInput
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    /** المحادثة مشتركة مع النافذة الصغيرة */
    @VisibleForTesting val messages get() = ChatStore.messages
    private lateinit var adapter: ChatAdapter
    private lateinit var list: RecyclerView
    private lateinit var input: EditText
    private lateinit var btnSend: View
    private lateinit var btnMic: ImageButton
    private lateinit var btnDefault: View
    private lateinit var ai: AiClient
    private lateinit var tools: NourTools
    private lateinit var orb: NourOrbView
    private lateinit var prefs: Prefs
    private lateinit var btnModel: com.google.android.material.button.MaterialButton
    private var askedModel = false
    private lateinit var voice: VoiceInput
    private var shownCount = 0
    @VisibleForTesting var busy = false
        private set

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Toast.makeText(this, if (ok) R.string.mic_granted else R.string.mic_permission, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        tools = NourTools(this)
        ai = AiClient(tools) { prefs.toConfig() }
        orb = findViewById(R.id.orb)
        btnModel = findViewById(R.id.btnModel)
        btnModel.setOnClickListener { pickModel() }

        list = findViewById(R.id.list)
        input = findViewById(R.id.input)
        btnSend = findViewById(R.id.btnSend)
        btnMic = findViewById(R.id.btnMic)
        btnDefault = findViewById(R.id.btnDefault)

        adapter = ChatAdapter(messages)
        list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        list.adapter = adapter
        shownCount = messages.size

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
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { submitInput(); true } else false
        }
        btnMic.setOnClickListener { toggleVoice() }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnDefault.setOnClickListener { openDefaultAssistantSettings() }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        btnDefault.visibility = if (isDefaultAssistant()) View.GONE else View.VISIBLE
        // بعد الريستارت أندرويد 14 مبيسمحش بتشغيل المايك من الخلفية: فتح التطبيق مرة بيشغّل النداء
        WakeController.ensureService(this)
        com.nour.assistant.gesture.GestureService.ensure(this)
        refreshModelLabel()
        // أول مرة بعد ما تحط الـ API Key: نفتح قايمة الموديلات لوحدنا
        if (prefs.apiKey.isNotBlank() && prefs.model.isBlank() && !askedModel) { askedModel = true; btnModel.post { pickModel() } }
        // لو اتكلمت في النافذة الصغيرة، المحادثة تظهر هنا
        if (messages.size != shownCount) { adapter.notifyDataSetChanged(); shownCount = messages.size }
        if (messages.isNotEmpty()) list.scrollToPosition(messages.size - 1)
    }

    override fun onPause() { voice.cancel(); super.onPause() }
    override fun onDestroy() { voice.release(); super.onDestroy() }

    /** زر المساعد بيفتح التطبيق من غير ما يشغّل المايك. المايك بس لما تدوس عليه. */
    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_REQUEST_MIC, false) == true) requestMic()
        // النداء فتح التطبيق مباشرة (لو Nour مش المساعد الافتراضي)
        if (i?.getBooleanExtra(WakeController.EXTRA_AUTO_MIC, false) == true) {
            i.removeExtra(WakeController.EXTRA_AUTO_MIC)
            btnMic.post { toggleVoice() }
        }
    }

    private fun refreshModelLabel() {
        btnModel.text = "🤖  " + (prefs.model.ifBlank { getString(R.string.model_pick) }) + "  ▾"
    }

    private fun pickModel() {
        ModelPicker.show(this) { id -> prefs.model = id; refreshModelLabel() }
    }

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
        val history = ChatStore.history() + Msg("user", text)
        messages += Msg("user", text)
        messages += Msg("assistant", "…")
        val placeholder = messages.size - 1
        shownCount = messages.size
        adapter.notifyItemRangeInserted(messages.size - 2, 2)
        list.scrollToPosition(placeholder)

        fun update(m: Msg) {
            if (placeholder < messages.size) {
                messages[placeholder] = m
                adapter.notifyItemChanged(placeholder)
                list.scrollToPosition(placeholder)
            }
        }
        fun done() {
            busy = false; btnSend.isEnabled = true; orb.mode = NourOrbView.Mode.IDLE
            val pending = tools.drainPending()
            if (pending.isNotEmpty()) list.postDelayed({
                pending.forEach { NourTools.safeStart({ i -> startActivity(i) }, it) }
            }, 1100)
        }
        orb.mode = NourOrbView.Mode.THINKING

        val tw = Typewriter(onText = { update(Msg("assistant", it)) }, onDone = { update(Msg("assistant", it.trim())); done() })
        lifecycleScope.launch {
            when (val r = ai.chatStream(history) { tw.feed(it) }) {
                is AiResult.Ok -> tw.finish(r.text)
                is AiResult.Err -> { tw.cancel(); update(Msg("assistant", r.message, isError = true)); done() }
            }
        }
    }

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
