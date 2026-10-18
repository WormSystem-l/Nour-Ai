package com.nour.assistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nour.assistant.AllowedApp
import com.nour.assistant.AppLog
import com.nour.assistant.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/** الواجهة اللي AiClient بيتعامل معاها: أدوات نور اللي الموديل يقدر ينادي عليها */
interface ToolHost {
    val hasTools: Boolean
    val toolNames: Set<String>
    /** تعريف الأدوات بصيغة OpenAI (tools[]) */
    fun specs(): JSONArray
    /** تعليمات إضافية للـ system prompt. native=false يعني الموديل مش بيدعم Function Calling */
    fun systemNote(native: Boolean): String
    suspend fun run(call: ToolCall): String
}

/**
 * أدوات نور:
 *  - open_app   : فتح تطبيق مسموح (بيتأجل لحد ما الرد يخلص عرض)
 *  - web_search : بحث مجاني في الإنترنت (نتايج مصغّرة)
 *  - read_page  : قراءة صفحة (أهم الفقرات للسؤال بس)
 *  - read_screen: محتوى الشاشة/الصفحة اللي كنت فاتحها لما نادّيت نور
 * والوقت والتاريخ بيتحطوا في الـ system prompt مباشرة (من غير أداة).
 */
class NourTools(private val ctx: Context) : ToolHost {
    private val pending = CopyOnWriteArrayList<Intent>()

    private fun apps(): List<AllowedApp> = Prefs(ctx).allowedApps
    private fun web(): Boolean = Prefs(ctx).webSearch
    private fun screen(): Boolean = ScreenContext.has()

    override val hasTools: Boolean get() = apps().isNotEmpty() || web() || screen()

    override val toolNames: Set<String>
        get() = buildSet {
            if (apps().isNotEmpty()) add(OPEN_APP)
            if (web()) { add(WEB_SEARCH); add(READ_PAGE) }
            if (screen()) add(READ_SCREEN)
        }

    /** قايمة صغيرة (<= 10): بنكتب الأسماء للموديل. قايمة كبيرة: المطابقة بتحصل جوه التطبيق (بتمنع اللخبطة). */
    private fun namesForModel(a: List<AllowedApp>): String? =
        if (a.size <= SMALL_LIST) a.joinToString(", ") { it.label } else null

    private fun fn(name: String, desc: String, props: JSONObject, required: List<String>) =
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", name).put("description", desc)
            .put("parameters", JSONObject().put("type", "object").put("properties", props)
                .put("required", JSONArray(required))))

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)

    override fun specs(): JSONArray {
        val arr = JSONArray()
        val a = apps()
        if (a.isNotEmpty()) {
            val names = namesForModel(a)
            val hint = if (names != null) "The app to open. One of: $names"
            else "The app name exactly as the user said it (Arabic or English). Do not translate it."
            arr.put(fn(OPEN_APP, "Open an app on the user's phone. Call only when the user asks to open/launch an app." +
                (if (names != null) " Allowed apps: $names" else " The app checks permissions itself."),
                JSONObject().put("app", str(hint)), listOf("app")))
        }
        if (web()) {
            arr.put(fn(WEB_SEARCH, "Search the web. Use for current events, prices, scores, weather, news, or facts you are unsure about. Returns a few short results.",
                JSONObject().put("query", str("Short search keywords")), listOf("query")))
            arr.put(fn(READ_PAGE, "Read ONE web page (only the parts relevant to 'focus'). Use after web_search only if the snippets are not enough.",
                JSONObject().put("url", str("Full http(s) URL from search results")).put("focus", str("What you need from the page")), listOf("url")))
        }
        if (screen()) {
            arr.put(fn(READ_SCREEN, "Read the text of the screen/page the user was looking at when they called Nour. Use when they ask about 'this page', 'this screen', translate it, or summarize it.",
                JSONObject(), emptyList()))
        }
        return arr
    }

    override fun systemNote(native: Boolean): String {
        val sb = StringBuilder()
        val now = Date()
        val tz = TimeZone.getDefault()
        val f = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).apply { timeZone = tz }
        sb.append("Current local date and time: ").append(f.format(now)).append(" (").append(tz.id).append("). Use it for any time/date question; do not guess.\n")
        sb.append("The user's message may come from voice dictation: English words (app names, captions, brands) can appear in Arabic letters. ")
        sb.append("When you write English text for the user (captions, messages, names), write it in correct English spelling and script, and keep Arabic parts in Arabic.")
        val a = apps()
        if (a.isEmpty()) {
            sb.append("\nYou cannot open apps right now. If the user asks you to open an app, tell them to allow apps in Nour settings.")
        } else {
            val names = namesForModel(a)
            sb.append("\nYou can open apps with the tool open_app. ")
            if (names != null) sb.append("Allowed apps: $names. ") else sb.append("Pass the app name exactly as the user said it; the app will check permission. ")
            sb.append("Do the user's other requests first (e.g. write the caption in your reply) and call open_app in the same turn. Never open an app that is not allowed.")
        }
        if (web()) sb.append("\nYou can search the web with web_search. Search only when the answer may have changed over time or you are unsure; do ONE search, then answer briefly and mention the source name. Use read_page for at most one result and only if needed.")
        if (screen()) sb.append("\nThe user opened Nour from another screen. If they ask about 'this page/screen/site', call read_screen first.")
        if (!native && toolNames.isNotEmpty()) {
            sb.append("\nTo call a tool, add this on its own line at the very end of your reply, exactly in this format (no markdown fences): ")
            sb.append("<tool>{\"name\":\"web_search\",\"arguments\":{\"query\":\"...\"}}</tool>  (tool names: ${toolNames.joinToString()}).")
        }
        return sb.toString()
    }

    override suspend fun run(call: ToolCall): String = try {
        when (call.name) {
            OPEN_APP -> openApp(call)
            WEB_SEARCH -> if (!web()) "Error: web search is disabled in settings." else WebTools.search(call.args.optString("query", call.args.optString("q", "")))
            READ_PAGE -> if (!web()) "Error: web access is disabled in settings." else WebTools.readPage(call.args.optString("url", ""), call.args.optString("focus", "").ifBlank { null })
            READ_SCREEN -> if (ScreenContext.has()) ScreenContext.forModel() else "Error: no screen content is available."
            else -> "Error: unknown tool ${call.name}"
        }
    } catch (e: Exception) {
        AppLog.eOnce("tools", "${call.name} failed", e)
        "Error: ${call.name} failed (${e.javaClass.simpleName})"
    }

    private fun openApp(call: ToolCall): String {
        val query = call.args.optString("app", call.args.optString("name", call.args.optString("package", "")))
        val list = apps()
        val found = AppResolver.candidates(query, list)
        if (found.size > 1) {
            return "Ambiguous: several allowed apps match '$query': ${found.take(5).joinToString { it.label }}. Ask the user which one they mean."
        }
        val app = found.firstOrNull()
            ?: return "Error: '$query' is not allowed (or not found). " +
                (namesForModel(list)?.let { "Allowed: $it. " } ?: "") + "Tell the user they can allow it in Nour settings."
        pending += launchIntent(ctx, app.pkg)
        return if (isInstalled(ctx, app.pkg)) "OK: opening ${app.label} now."
        else "OK: ${app.label} is not installed, opening its Google Play page."
    }

    /** الـ Intents اللي لازم تتنفذ بعد ما الرد يخلص عرض */
    fun drainPending(): List<Intent> {
        val l = pending.toList(); pending.clear(); return l
    }

    companion object {
        const val OPEN_APP = "open_app"
        const val WEB_SEARCH = "web_search"
        const val READ_PAGE = "read_page"
        const val READ_SCREEN = "read_screen"
        /** لحد العدد ده بنكتب أسماء التطبيقات للموديل */
        const val SMALL_LIST = 10

        fun isInstalled(ctx: Context, pkg: String) = try { ctx.packageManager.getLaunchIntentForPackage(pkg) != null } catch (_: Exception) { false }

        /** التطبيق لو متثبت، غير كده صفحته في Google Play */
        fun launchIntent(ctx: Context, pkg: String): Intent {
            val launch = try { ctx.packageManager.getLaunchIntentForPackage(pkg) } catch (_: Exception) { null }
            if (launch != null) return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                .setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /** لو Google Play مش موجود: صفحة الويب */
        fun webFallback(pkg: String): Intent =
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** بيشغّل الـ Intent وفي حالة الفشل بيجرب صفحة الويب */
        fun safeStart(start: (Intent) -> Unit, i: Intent) {
            try { start(i) } catch (_: Exception) {
                val pkg = i.data?.getQueryParameter("id")
                if (pkg != null) try { start(webFallback(pkg)) } catch (_: Exception) {}
            }
        }
    }
}
