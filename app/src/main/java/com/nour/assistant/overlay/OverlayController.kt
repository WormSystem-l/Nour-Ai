package com.nour.assistant.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.nour.assistant.AiClient
import com.nour.assistant.AiResult
import com.nour.assistant.ChatStore
import com.nour.assistant.Msg
import com.nour.assistant.R
import com.nour.assistant.ui.GlassCardView
import com.nour.assistant.L
import com.nour.assistant.tools.NourTools
import com.nour.assistant.tools.PageFetcher
import com.nour.assistant.tools.PagePrompts
import com.nour.assistant.tools.ScreenContext
import com.nour.assistant.tools.Source
import com.nour.assistant.tools.ToolEvent
import com.nour.assistant.ui.GradientOrbButton
import com.nour.assistant.ui.Markdown
import com.nour.assistant.ui.MarkdownView
import com.nour.assistant.ui.NourTheme
import com.nour.assistant.ui.ThinkingView
import com.nour.assistant.ui.NourOrbView
import com.nour.assistant.ui.Typewriter
import com.nour.assistant.ui.WaveView
import com.nour.assistant.voice.VoiceInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * كل منطق وواجهة النافذة الصغيرة. منفصل عن الـ Session عشان يبقى سهل نختبره.
 */
class OverlayController(
    private val ctx: Context,
    private val ai: AiClient,
    private val callbacks: Callbacks,
    private val tools: NourTools? = null
) {
    interface Callbacks {
        fun onDismiss()
        fun onOpenFull()
        fun onNeedMicPermission()
        /** نور عايز يفتح تطبيق (أداة open_app) */
        fun onLaunch(intent: Intent) {}
    }

    enum class State { COMPACT, LISTENING, THINKING, ANSWERING, DONE }

    @SuppressLint("InflateParams") // النافذة هي الجذر، مفيش Parent
    val root: View = LayoutInflater.from(ctx).inflate(R.layout.overlay_session, null, false)
    private val d = ctx.resources.displayMetrics.density
    private val scrim: View = root.findViewById(R.id.scrim)
    val card: GlassCardView = root.findViewById(R.id.card)
    private val status: TextView = root.findViewById(R.id.status)
    private val scroll: ScrollView = root.findViewById(R.id.responseScroll)
    private val question: TextView = root.findViewById(R.id.question)
    private val answer: TextView = root.findViewById(R.id.answer)          // النص الخام (مخفي)
    private val answerMd: MarkdownView = root.findViewById(R.id.answerMd)
    private val thinkingPop: ThinkingView = root.findViewById(R.id.thinkingPop)
    private val wave: WaveView = root.findViewById(R.id.wave)
    val input: EditText = root.findViewById(R.id.input)
    private val btnSend: View = root.findViewById(R.id.btnSend)
    private val btnMic: GradientOrbButton = root.findViewById(R.id.btnMic)
    private val orb: NourOrbView = root.findViewById(R.id.orb)

    private val pagePills: LinearLayout = root.findViewById(R.id.pagePills)
    private val pillAsk: TextView = root.findViewById(R.id.pillAsk)
    private val pillSum: TextView = root.findViewById(R.id.pillSum)
    private val pillTr: TextView = root.findViewById(R.id.pillTr)
    private val attachChip: LinearLayout = root.findViewById(R.id.attachChip)
    private val attachLabel: TextView = root.findViewById(R.id.attachLabel)
    private val sourcesBtn: TextView = root.findViewById(R.id.sourcesBtn)
    private val sourcesBox: LinearLayout = root.findViewById(R.id.sourcesBox)
    private var pageAttached = false
    private var pageMode = PagePrompts.Mode.ASK

    init {
        val pal = NourTheme.palette(ctx)
        // أزرار الصفحة زجاجية (أسود أو ملوّن حسب الثيم)
        fun styled(v: TextView, icon: Int, text: String) {
            v.text = text; v.background = NourTheme.glass(ctx, 26f, pal, strong = true)
            val dr = androidx.core.content.ContextCompat.getDrawable(ctx, icon)?.mutate()
            dr?.setBounds(0, 0, NourTheme.dp(ctx, 22f), NourTheme.dp(ctx, 22f)); dr?.setTint(0xFFFFFFFF.toInt())
            v.setCompoundDrawablesRelative(dr, null, null, null)
        }
        styled(pillAsk, R.drawable.ic_page, L.t("اسأل عن الصفحة", "Ask about page"))
        styled(pillSum, R.drawable.ic_summary, L.t("لخّص الصفحة", "Summarize page"))
        styled(pillTr, R.drawable.ic_translate, L.t("ترجم الصفحة", "Translate page"))
        attachChip.background = NourTheme.glass(ctx, 20f, pal, strong = true)
        sourcesBtn.background = NourTheme.glass(ctx, 20f, pal, strong = true)
        pillAsk.setOnClickListener { attachPage(PagePrompts.Mode.ASK); input.requestFocus() }
        pillSum.setOnClickListener { attachPage(PagePrompts.Mode.SUMMARIZE); ask("") }
        pillTr.setOnClickListener { attachPage(PagePrompts.Mode.TRANSLATE); ask("") }
        root.findViewById<View>(R.id.attachClose).setOnClickListener { detachPage() }
        sourcesBtn.setOnClickListener { sourcesBox.visibility = if (sourcesBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        // الثيم: أسود تماماً (زجاج أسود) أو ألوان
        card.applyBlack(NourTheme.isBlack(ctx))
        answerMd.textColor = 0xFFFFFFFF.toInt(); answerMd.textSizeSp = 16f
    }

    private var pillsToken = 0
    private var pillsHooked = false

    /**
     * مكان الأزرار = حرف الكرت العلوي الفعلي ناقص مسافة. بنقراه من الكرت نفسه (top + translationY)
     * فبيشمل هامش الكيبورد وشريط التنقل (اللي بيتحط على الكرت من الـ insets) وأي حركة، من غير ما نحسب حاجة يدوي.
     * الأزرار أساسها ملصوق بأسفل النافذة (من غير هامش)، فبنرفعها بالفرق.
     */
    private val pillsSync = android.view.ViewTreeObserver.OnPreDrawListener {
        if (pagePills.visibility == View.VISIBLE) placePills()
        true
    }

    private fun placePills() {
        val gap = NourTheme.dp(ctx, 12f)
        val h = root.height
        if (h <= 0 || card.height <= 0) return
        pagePills.translationY = (card.top + card.translationY) - gap - h
    }

    /** ظهور الأزرار: كل واحد بيطلع من تحت بنعومة ورا التاني */
    private fun showPills() {
        pillsToken++
        // الأزرار عائمة فوق الكرت: بتتبع مكانه الفعلي في كل إطار (كيبورد، شريط تنقل، تكبير، سحب، حركة دخول/خروج)
        if (!pillsHooked) { root.viewTreeObserver.addOnPreDrawListener(pillsSync); pillsHooked = true }
        pagePills.alpha = 1f
        if (pagePills.visibility == View.VISIBLE) return
        pagePills.visibility = View.VISIBLE
        placePills()
        val d = NourTheme.dp(ctx, 20f).toFloat()
        listOf(pillAsk, pillSum, pillTr).forEachIndexed { i, v ->
            v.alpha = 0f; v.translationY = d; v.scaleX = 0.92f; v.scaleY = 0.92f
            v.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(60L * i + 80).setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
        }
    }

    private fun hidePills() {
        if (pagePills.visibility != View.VISIBLE) return
        val t = ++pillsToken
        pagePills.animate().alpha(0f).setDuration(150).withEndAction { if (t == pillsToken) { pagePills.visibility = View.GONE; pagePills.alpha = 1f } }.start()
    }

    /** وصل محتوى صفحة ويب من نظام المساعد: نعرض الأزرار (بس لو فعلاً موقع ويب) */
    fun onScreenContext() {
        if (!ScreenContext.has() || ScreenContext.url.isNullOrBlank()) { hidePills(); return }
        if (!pageAttached) showPills()
    }

    private fun attachPage(mode: PagePrompts.Mode) {
        pageAttached = true; pageMode = mode
        hidePills()
        val host = try { java.net.URI(ScreenContext.url ?: "").host?.removePrefix("www.") } catch (_: Exception) { null }
        attachLabel.text = L.t("الصفحة", "Page") + (if (host.isNullOrBlank()) "" else " · $host")
        attachChip.visibility = View.VISIBLE
        btnSend.visibility = View.VISIBLE
    }

    private fun detachPage() {
        pageAttached = false; pageMode = PagePrompts.Mode.ASK
        attachChip.visibility = View.GONE
        if (ScreenContext.has() && !ScreenContext.url.isNullOrBlank()) showPills()
        btnSend.visibility = if (input.text.isNotBlank()) View.VISIBLE else View.GONE
    }

    private fun showSources(list: List<Source>) {
        sourcesBox.removeAllViews()
        if (list.isEmpty()) { sourcesBtn.visibility = View.GONE; sourcesBox.visibility = View.GONE; return }
        val pal = NourTheme.palette(ctx)
        sourcesBtn.text = L.t("المصادر", "Sources") + "  ·  ${list.size}"
        sourcesBtn.visibility = View.VISIBLE
        for (src in list) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; isClickable = true
                setPadding(NourTheme.dp(ctx, 14f), NourTheme.dp(ctx, 10f), NourTheme.dp(ctx, 14f), NourTheme.dp(ctx, 10f))
                background = NourTheme.glass(ctx, 16f, pal)
                setOnClickListener { try { callbacks.onLaunch(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(src.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
            }
            row.addView(TextView(ctx).apply { text = src.title.ifBlank { src.domain }; setTextColor(0xFFFFFFFF.toInt()); textSize = 14f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
            row.addView(TextView(ctx).apply { text = src.domain; setTextColor(pal.accent); textSize = 12f })
            sourcesBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = NourTheme.dp(ctx, 6f) })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var typer: Typewriter? = null
    private var heightAnim: ValueAnimator? = null
    var state = State.COMPACT
        private set
    var expanded = false
        private set

    private val voice = VoiceInput(ctx, object : VoiceInput.Listener {
        override fun onReady() { setStatus(R.string.st_listening) }
        override fun onLevel(level: Float) { wave.level = level; card.level = level; orb.level = level }
        override fun onPartial(text: String) { input.setText(text); input.setSelection(text.length) }
        override fun onFinal(text: String) { ask(text) }
        override fun onStopped() {
            btnMic.active = false; wave.level = 0f; card.level = 0f; orb.level = 0f
            if (state == State.LISTENING) setState(if (expanded) State.DONE else State.COMPACT)
        }
        override fun onError(message: String, needsPermission: Boolean) {
            if (needsPermission) { callbacks.onNeedMicPermission(); return }
            status.text = message
        }
    })

    init {
        setupInsets()
        setupInput()
        setupDrag()
        scrim.setOnClickListener { dismissAnimated() }
        root.findViewById<View>(R.id.btnExpand).setOnClickListener { callbacks.onOpenFull() }
        setStatus(R.string.st_ready)
    }

    // ---------------------------------------------------------------- lifecycle

    /** بيتنادى كل مرة النافذة تظهر. المايك مش بيشتغل لوحده (بس لما تدوس عليه). */
    fun onShow(autoMic: Boolean = false) {
        reset()
        if (autoMic) root.postDelayed({
            // بعد النداء: المايك يشتغل لوحده (لو النافذة لسه ظاهرة ومفيش حاجة شغالة)
            if (state == State.COMPACT && voice.hasPermission() && !voice.isListening) toggleMic()
        }, 650)
        card.post {
            card.translationY = card.height + 80 * d
            card.alpha = 0f
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(260).start()
            card.animate().translationY(0f).alpha(1f).setDuration(520)
                .setInterpolator(OvershootInterpolator(0.85f)).start()
        }
    }

    fun onHide() {
        pageAttached = false; pageMode = PagePrompts.Mode.ASK; attachChip.visibility = View.GONE; pagePills.visibility = View.GONE; pillsToken++; showSources(emptyList()); ScreenContext.clear()
        voice.cancel()
        job?.cancel(); typer?.cancel()
        hideKeyboard()
    }

    fun destroy() { onHide(); voice.release(); scope.cancel() }

    fun reset() {
        job?.cancel(); typer?.cancel(); heightAnim?.cancel()
        expanded = false
        scroll.visibility = View.GONE
        wave.visibility = View.GONE
        question.text = ""; answer.text = ""; answerMd.clear(); thinkingPop.visibility = View.GONE
        input.setText("")
        card.layoutParams = card.layoutParams.apply { height = ViewGroup.LayoutParams.WRAP_CONTENT }
        card.translationY = 0f
        setState(State.COMPACT)
    }

    // ---------------------------------------------------------------- actions

    fun toggleMic() {
        if (voice.isListening) { voice.stop(); return }
        if (state == State.THINKING || state == State.ANSWERING) return
        if (!voice.hasPermission()) { callbacks.onNeedMicPermission(); return }
        hideKeyboard()
        setState(State.LISTENING)
        btnMic.active = true
        voice.start()
    }

    fun ask(textRaw: String) {
        val typed = textRaw.trim()
        val withPage = pageAttached && ScreenContext.has()
        if ((typed.isEmpty() && !withPage) || state == State.THINKING || state == State.ANSWERING) return
        val ar = java.util.Locale.getDefault().language == "ar"
        val mode = if (withPage) pageMode else PagePrompts.Mode.ASK
        // اللي بيظهر للمستخدم كسؤاله
        val shown = if (withPage) PagePrompts.visible(mode, typed, ar) else typed
        if (voice.isListening) voice.cancel()
        hideKeyboard()
        input.setText("")
        question.text = shown
        answer.text = ""; answerMd.clear(); showSources(emptyList())
        thinkingPop.setLabel("Nour"); thinkingPop.visibility = View.VISIBLE
        expand()
        setState(State.THINKING)

        tools?.forceSearch = !withPage && com.nour.assistant.tools.SearchIntent.needsWeb(typed)
        val base = ChatStore.history()
        ChatStore.messages += Msg("user", shown)
        val url = ScreenContext.url
        if (withPage) { pageAttached = false; pageMode = PagePrompts.Mode.ASK; attachChip.visibility = View.GONE; hidePills() }
        val tw = Typewriter(
            onText = { t ->
                if (state == State.THINKING) setState(State.ANSWERING)
                thinkingPop.visibility = View.GONE
                answer.text = t
                answerMd.setMarkdown(t)
                scroll.post { scroll.smoothScrollTo(0, answerMd.bottom) }
            },
            onDone = { setState(State.DONE); ChatStore.persist(ctx); launchPending() }
        )
        typer = tw
        job = scope.launch {
            // محتوى الصفحة كاملة (من رابطها) بيتبعت للموديل مرة واحدة بس في السؤال ده (مش بيتخزن في المحادثة) = توفير توكنز
            var sent = shown
            if (withPage) {
                thinkingPop.setLabel(L.t("جاري قراءة الصفحة", "Reading page"))
                val block = withContext(Dispatchers.IO) { PageFetcher.load(url, typed) }
                sent = PagePrompts.build(mode, typed, block, ar)
                thinkingPop.setLabel("Nour")
            }
            val history = base + Msg("user", sent)
            val onEvent: (ToolEvent) -> Unit = { ev ->
                root.post {
                    when (ev) {
                        is ToolEvent.Start -> NourTools.statusLabel(ev.name)?.let { thinkingPop.setLabel(it); thinkingPop.visibility = View.VISIBLE }
                        is ToolEvent.End -> if (state == State.THINKING) thinkingPop.setLabel("Nour") else thinkingPop.visibility = View.GONE
                    }
                }
            }
            when (val r = ai.chatStream(history, onEvent = onEvent) { tw.feed(it) }) {
                is AiResult.Ok -> {
                    ChatStore.messages += Msg("assistant", r.text, sources = r.sources)
                    tw.finish(r.text); showSources(r.sources)
                }
                is AiResult.Err -> {
                    tw.cancel()
                    ChatStore.messages += Msg("assistant", r.message, isError = true)
                    answer.setTextColor(0xFFFCA5A5.toInt())
                    answer.text = r.message
                    thinkingPop.visibility = View.GONE
                    answerMd.clear(); answerMd.textColor = 0xFFFCA5A5.toInt(); answerMd.setMarkdown(r.message)
                    tools?.drainPending()
                    setState(State.DONE)
                }
            }
        }
    }

    /** فتح التطبيقات اللي الموديل طلبها (بعد ما الرد يخلص عرض بثانية عشان المستخدم يلحق يقراه) */
    private fun launchPending() {
        val list = tools?.drainPending().orEmpty()
        if (list.isEmpty()) return
        root.postDelayed({ list.forEach { callbacks.onLaunch(it) } }, 1100)
    }

    // ---------------------------------------------------------------- state & animation

    private fun setState(s: State) {
        state = s
        orb.mode = when (s) {
            State.LISTENING -> NourOrbView.Mode.LISTENING
            State.THINKING, State.ANSWERING -> NourOrbView.Mode.THINKING
            else -> NourOrbView.Mode.IDLE
        }
        when (s) {
            State.COMPACT -> { card.mode = GlassCardView.Mode.IDLE; wave.visibility = View.GONE; setStatus(R.string.st_ready) }
            State.LISTENING -> {
                card.mode = GlassCardView.Mode.LISTENING
                wave.thinking = false; wave.visibility = View.VISIBLE; setStatus(R.string.st_listening)
            }
            State.THINKING -> {
                card.mode = GlassCardView.Mode.THINKING
                wave.thinking = true; wave.visibility = View.VISIBLE; setStatus(R.string.st_thinking)
            }
            State.ANSWERING -> {
                card.mode = GlassCardView.Mode.THINKING
                wave.visibility = View.GONE; setStatus(R.string.st_answering)
            }
            State.DONE -> {
                card.mode = GlassCardView.Mode.IDLE; wave.visibility = View.GONE; status.text = ""
            }
        }
        btnMic.isEnabled = s != State.THINKING && s != State.ANSWERING
    }

    private fun setStatus(res: Int) { status.setText(res) }

    /** النافذة بتطول لحد ~62% من الشاشة بحركة ناعمة فيها "نطة" خفيفة */
    fun expand() {
        answer.setTextColor(0xFFFFFFFF.toInt()); answerMd.textColor = 0xFFFFFFFF.toInt()
        if (expanded) return
        expanded = true
        val screenH = ctx.resources.displayMetrics.heightPixels
        val from = card.height.takeIf { it > 0 } ?: (180 * d).toInt()
        val to = (screenH * 0.62f).toInt().coerceAtLeast(from)
        scroll.visibility = View.VISIBLE
        scroll.alpha = 0f
        card.layoutParams = card.layoutParams.apply { height = from }
        heightAnim?.cancel()
        heightAnim = ValueAnimator.ofInt(from, to).apply {
            duration = 560
            interpolator = PathInterpolator(0.2f, 0.9f, 0.25f, 1.08f)
            addUpdateListener {
                card.layoutParams = card.layoutParams.apply { height = it.animatedValue as Int }
            }
            start()
        }
        scroll.animate().alpha(1f).setStartDelay(160).setDuration(320).start()
    }

    fun dismissAnimated() {
        hideKeyboard()
        card.animate().translationY(card.height + 80 * d).alpha(0f).setDuration(260)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { card.animate().setListener(null); callbacks.onDismiss() }
            }).start()
        scrim.animate().alpha(0f).setDuration(240).start()
    }

    // ---------------------------------------------------------------- setup

    private fun setupInput() {
        btnMic.setOnClickListener { toggleMic() }
        btnSend.setOnClickListener { ask(input.text.toString()) }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { ask(input.text.toString()); true } else false
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // زرار الإرسال بيظهر بس لما تكتب (وإنت مش بتتكلم)
                btnSend.visibility = if ((!s.isNullOrBlank() || pageAttached) && !voice.isListening) View.VISIBLE else View.GONE
            }
        })
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            (card.layoutParams as ViewGroup.MarginLayoutParams).let {
                it.bottomMargin = bottom + (2 * d).toInt()
                card.layoutParams = it
            }
            insets
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        var startY = 0f
        var dragging = false
        val threshold = 110 * d
        val touch = View.OnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - startY
                    if (abs(dy) > 8 * d) dragging = true
                    // لتحت بيتحرك عادي، لفوق بمقاومة (rubber band)
                    card.translationY = if (dy > 0) dy else dy * 0.35f
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dy = e.rawY - startY
                    when {
                        dragging && dy > threshold -> dismissAnimated()
                        dragging && dy < -threshold -> { card.animate().translationY(0f).setDuration(200).start(); callbacks.onOpenFull() }
                        else -> card.animate().translationY(0f).setDuration(380)
                            .setInterpolator(OvershootInterpolator(1.4f)).start()
                    }
                    true
                }
                else -> false
            }
        }
        root.findViewById<View>(R.id.dragArea).setOnTouchListener(touch)
        root.findViewById<View>(R.id.header).setOnTouchListener(touch)
    }

    private fun hideKeyboard() {
        val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }
}
