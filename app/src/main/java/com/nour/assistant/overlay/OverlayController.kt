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
import com.nour.assistant.tools.NourTools
import com.nour.assistant.ui.GradientOrbButton
import com.nour.assistant.ui.Markdown
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
    private val answer: TextView = root.findViewById(R.id.answer)
    private val wave: WaveView = root.findViewById(R.id.wave)
    val input: EditText = root.findViewById(R.id.input)
    private val btnSend: View = root.findViewById(R.id.btnSend)
    private val btnMic: GradientOrbButton = root.findViewById(R.id.btnMic)
    private val orb: NourOrbView = root.findViewById(R.id.orb)

    init { Markdown.prepare(answer) }

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
        question.text = ""; answer.text = ""
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
        val text = textRaw.trim()
        if (text.isEmpty() || state == State.THINKING || state == State.ANSWERING) return
        if (voice.isListening) voice.cancel()
        hideKeyboard()
        input.setText("")
        question.text = text
        answer.text = ""
        expand()
        setState(State.THINKING)

        val history = ChatStore.history() + Msg("user", text)
        ChatStore.messages += Msg("user", text)
        val tw = Typewriter(
            onText = { t ->
                if (state == State.THINKING) setState(State.ANSWERING)
                answer.text = Markdown.render(ctx, t, Markdown.DARK)
                scroll.post { scroll.smoothScrollTo(0, answer.bottom) }
            },
            onDone = { setState(State.DONE); launchPending() }
        )
        typer = tw
        job = scope.launch {
            when (val r = ai.chatStream(history) { tw.feed(it) }) {
                is AiResult.Ok -> { ChatStore.messages += Msg("assistant", r.text); tw.finish(r.text) }
                is AiResult.Err -> {
                    tw.cancel()
                    ChatStore.messages += Msg("assistant", r.message, isError = true)
                    answer.setTextColor(0xFFFCA5A5.toInt())
                    answer.text = r.message
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
        answer.setTextColor(0xFFFFFFFF.toInt())
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
                btnSend.visibility = if (!s.isNullOrBlank() && !voice.isListening) View.VISIBLE else View.GONE
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
