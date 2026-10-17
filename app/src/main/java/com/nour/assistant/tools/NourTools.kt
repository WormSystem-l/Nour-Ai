package com.nour.assistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.nour.assistant.AllowedApp
import com.nour.assistant.L
import com.nour.assistant.Prefs
import org.json.JSONArray
import org.json.JSONObject
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
 * أدوات نور. دلوقتي: open_app.
 * فتح التطبيق بيتأجل لحد ما الرد يخلص عرض (drainPending) عشان المستخدم يلحق يقرا الكابشن.
 */
class NourTools(private val ctx: Context) : ToolHost {
    private val pending = CopyOnWriteArrayList<Intent>()

    private fun allowed(): List<AllowedApp> = Prefs(ctx).allowedApps

    override val hasTools: Boolean get() = allowed().isNotEmpty()
    override val toolNames: Set<String> = setOf(OPEN_APP)

    /**
     * قايمة صغيرة (<= 10): بنكتب الأسماء للموديل. قايمة كبيرة: مبنكتبهاش (بتلخبط الموديل وبتغلّي التكلفة)،
     * والتطبيق هو اللي بيطابق الاسم ويتأكد إنه مسموح.
     */
    private fun namesForModel(apps: List<AllowedApp>): String? =
        if (apps.size <= SMALL_LIST) apps.joinToString(", ") { it.label } else null

    override fun specs(): JSONArray {
        val names = namesForModel(allowed())
        val hint = if (names != null) "The app to open. One of: $names"
        else "The app name exactly as the user said it (Arabic or English), e.g. Instagram, واتساب. Do not translate it."
        val params = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("app", JSONObject()
                .put("type", "string")
                .put("description", hint)))
            .put("required", JSONArray().put("app"))
        val desc = "Open an app on the user's phone. Call it only when the user asks to open/launch an app." +
            (if (names != null) " Allowed apps: $names" else " The app checks permissions itself.")
        return JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", OPEN_APP)
            .put("description", desc)
            .put("parameters", params)))
    }

    override fun systemNote(native: Boolean): String {
        val sb = StringBuilder()
        sb.append("The user's message may come from voice dictation: English words (app names, captions, brands) can appear in Arabic letters. ")
        sb.append("When you write English text for the user (captions, messages, names), write it in correct English spelling and script, and keep Arabic parts in Arabic.")
        val apps = allowed()
        if (apps.isEmpty()) {
            sb.append("\nYou cannot open apps right now. If the user asks you to open an app, tell them to allow apps in Nour settings.")
        } else {
            val names = namesForModel(apps)
            sb.append("\nYou can open apps for the user with the tool open_app. ")
            if (names != null) sb.append("Allowed apps: $names. ")
            else sb.append("Pass the app name exactly as the user said it; the app will check permission. ")
            sb.append("Do the user's other requests first (for example write the caption in your reply) and call open_app in the same turn. Never open an app that is not in the list.")
            if (!native) {
                sb.append("\nTo call the tool, add this on its own line at the very end of your reply, exactly in this format: ")
                sb.append("<tool>{\"name\":\"open_app\",\"arguments\":{\"app\":\"Instagram\"}}</tool>")
            }
        }
        return sb.toString()
    }

    override suspend fun run(call: ToolCall): String {
        if (call.name != OPEN_APP) return "Error: unknown tool ${call.name}"
        val query = call.args.optString("app", call.args.optString("name", call.args.optString("package", "")))
        val apps = allowed()
        val found = AppResolver.candidates(query, apps)
        if (found.size > 1) {
            return "Ambiguous: several allowed apps match '$query': ${found.take(5).joinToString { it.label }}. Ask the user which one they mean."
        }
        val app = found.firstOrNull()
            ?: return "Error: '$query' is not allowed (or not found). " +
                (namesForModel(apps)?.let { "Allowed: $it. " } ?: "") + "Tell the user they can allow it in Nour settings."
        val intent = launchIntent(ctx, app.pkg)
        pending += intent
        return if (isInstalled(ctx, app.pkg)) "OK: opening ${app.label} now."
        else "OK: ${app.label} is not installed, opening its Google Play page."
    }

    /** الـ Intents اللي لازم تتنفذ بعد ما الرد يخلص عرض */
    fun drainPending(): List<Intent> {
        val l = pending.toList(); pending.clear(); return l
    }

    companion object {
        const val OPEN_APP = "open_app"
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
