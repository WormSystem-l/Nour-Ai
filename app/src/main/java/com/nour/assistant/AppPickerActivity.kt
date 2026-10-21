package com.nour.assistant

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nour.assistant.tools.AppResolver

/**
 * اختيار التطبيقات (حد أقصى 10) اللي نور مسموحله يفتحها.
 * فيها تطبيقات مشهورة (حتى لو مش متثبتة: نور هيفتح صفحتها في Google Play)
 * + كل التطبيقات المثبتة + إضافة يدوية بالـ package.
 */
class AppPickerActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private val selected = linkedMapOf<String, String>() // pkg -> label
    private lateinit var counter: TextView
    private val boxes = mutableMapOf<String, CheckBox>()
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        prefs.allowedApps.forEach { selected[it.pkg] = it.label }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        counter = TextView(this).apply { textSize = 18f; setPadding(0, 0, 0, dp(6)) }
        root.addView(counter)
        root.addView(TextView(this).apply { text = getString(R.string.apps_hint); textSize = 13f; alpha = 0.75f })

        // بحث + تحديد الكل / إلغاء الكل
        val search = EditText(this).apply { hint = getString(R.string.apps_search); setSingleLine() }
        root.addView(search)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val bAll = Button(this).apply { text = getString(R.string.apps_select_all); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        val bNone = Button(this).apply { text = getString(R.string.apps_clear_all); layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        bar.addView(bAll); bar.addView(bNone)
        root.addView(bar)
        bAll.setOnClickListener { bulk(true) }
        bNone.setOnClickListener { bulk(false) }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { filter(e?.toString().orEmpty()) }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // إضافة يدوية
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val pkgInput = EditText(this).apply {
            hint = getString(R.string.apps_add_hint); setSingleLine(); textDirection = View.TEXT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val addBtn = Button(this).apply { text = getString(R.string.apps_add) }
        addBtn.setOnClickListener {
            val pkg = pkgInput.text.toString().trim()
            if (!Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+").matches(pkg)) { pkgInput.error = "com.example.app"; return@setOnClickListener }
            if (!boxes.containsKey(pkg)) addRow(pkg, pkg, d)
            boxes[pkg]?.isChecked = true
            pkgInput.setText("")
        }
        row.addView(pkgInput); row.addView(addBtn)
        root.addView(row)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply {
            addView(listBox)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        val done = Button(this).apply { text = getString(R.string.save); setOnClickListener { finish() } }
        root.addView(done)
        setContentView(root)
        com.nour.assistant.ui.NourTheme.applyScreen(this, root)
        fitSystemWindows(root)

        // 1) الشائعة 2) اللي اخترتها قبل كده 3) كل المثبت
        val seen = mutableSetOf<String>()
        fun add(pkg: String, label: String) { if (seen.add(pkg)) addRow(pkg, label, d) }
        POPULAR.forEach { add(it.second, it.first) }
        selected.forEach { (pkg, label) -> add(pkg, label) }
        installed().sortedBy { it.second.lowercase() }.forEach { add(it.first, it.second) }
        updateCounter()
    }

    private fun fitSystemWindows(v: View) { v.fitsSystemWindows = true }

    private fun installed(): List<Pair<String, String>> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return try {
            pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != packageName }.distinctBy { it.first }
        } catch (_: Exception) { emptyList() }
    }

    private fun addRow(pkg: String, label: String, d: Float) {
        val cb = CheckBox(this).apply {
            text = if (label == pkg) pkg else "$label\n$pkg"
            textSize = 15f
            isChecked = selected.containsKey(pkg)
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }
        cb.setOnCheckedChangeListener { _, on ->
            if (on) selected[pkg] = label else selected.remove(pkg)
            if (!bulking) save()
        }
        boxes[pkg] = cb
        listBox.addView(cb)
    }

    private var bulking = false

    /** تحديد/إلغاء كل الصفوف الظاهرة (حسب البحث) مرة واحدة */
    private fun bulk(on: Boolean) {
        bulking = true
        boxes.values.filter { it.visibility == View.VISIBLE }.forEach { it.isChecked = on }
        bulking = false
        save()
    }

    private fun filter(q: String) {
        val n = com.nour.assistant.tools.AppResolver.norm(q)
        boxes.forEach { (pkg, cb) ->
            val hay = com.nour.assistant.tools.AppResolver.norm(cb.text.toString() + pkg)
            cb.visibility = if (n.isEmpty() || hay.contains(n)) View.VISIBLE else View.GONE
        }
    }

    private fun save() {
        prefs.allowedApps = selected.map { AllowedApp(it.value, it.key) }
        updateCounter()
    }

    private fun updateCounter() { counter.text = getString(R.string.apps_counter, selected.size) }

    companion object {
        val POPULAR = listOf(
            "Instagram" to "com.instagram.android",
            "WhatsApp" to "com.whatsapp",
            "Facebook" to "com.facebook.katana",
            "TikTok" to "com.zhiliaoapp.musically",
            "YouTube" to "com.google.android.youtube",
            "Telegram" to "org.telegram.messenger",
            "X (Twitter)" to "com.twitter.android",
            "Snapchat" to "com.snapchat.android",
            "Spotify" to "com.spotify.music",
            "Chrome" to "com.android.chrome",
            "Google Maps" to "com.google.android.apps.maps",
            "Gmail" to "com.google.android.gm"
        )
    }
}
