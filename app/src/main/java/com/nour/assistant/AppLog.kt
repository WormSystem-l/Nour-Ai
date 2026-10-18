package com.nour.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * سجل أخطاء محلي (على الجهاز) بيتصدّر بزرار واحد من الإعدادات.
 * بيسجل الأخطاء والـ crashes وحالة الخدمات. مفيش API Key ولا نص محادثات بيتسجل.
 */
object AppLog {
    private var file: File? = null
    private const val MAX_BYTES = 256 * 1024
    private val onceAt = HashMap<String, Long>()
    private val fmt get() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(ctx: Context) {
        file = File(ctx.applicationContext.filesDir, "nour.log")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { write("CRASH", "thread", "${t.name}: ${stack(e)}") } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
        write("I", "app", "start v${version(ctx)} sdk=${Build.VERSION.SDK_INT} ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, if (t != null) "$msg | ${stack(t)}" else msg)

    /** نفس الخطأ مايتسجلش أكتر من مرة كل 30 ثانية (عشان مانغرقش السجل) */
    fun eOnce(tag: String, msg: String, t: Throwable? = null) {
        val k = "$tag:$msg"
        val now = System.currentTimeMillis()
        synchronized(onceAt) {
            if (now - (onceAt[k] ?: 0L) < 30_000) return
            onceAt[k] = now
        }
        e(tag, msg, t)
    }

    private fun stack(t: Throwable): String {
        val sb = StringBuilder(t.toString())
        t.stackTrace.take(12).forEach { sb.append("\n    at ").append(it) }
        t.cause?.let { sb.append("\n  caused by ").append(it) }
        return sb.toString()
    }

    @Synchronized
    private fun write(level: String, tag: String, msg: String) {
        val f = file ?: return
        try {
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText(keep.substringAfter('\n', keep))
            }
            f.appendText("${fmt.format(Date())} $level/$tag: $msg\n")
        } catch (_: Throwable) {}
    }

    private fun version(ctx: Context) = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: PackageManager.NameNotFoundException) { "?" }

    /** تقرير كامل (حالة الجهاز والخدمات + السجل) في ملف جاهز للمشاركة */
    fun buildReport(ctx: Context): String {
        val p = Prefs(ctx)
        val host = try { java.net.URI(p.baseUrl).host } catch (_: Exception) { "?" }
        val sb = StringBuilder()
        sb.append("=== Nour AI report ===\n")
        sb.append("version: ${version(ctx)}\n")
        sb.append("android: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})\n")
        sb.append("device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("locale: ${Locale.getDefault().toLanguageTag()}\n")
        sb.append("api host: $host | model: ${p.model} | key set: ${p.apiKey.isNotBlank()}\n")
        sb.append("wake enabled: ${p.wakeEnabled} running: ${com.nour.assistant.wake.WakeWordService.running} models: ${com.nour.assistant.wake.WakeModels.isReady(ctx)}\n")
        sb.append("gesture enabled: ${p.gestureEnabled} running: ${com.nour.assistant.gesture.GestureService.running} accessibility: ${com.nour.assistant.gesture.NourAccessibilityService.instance != null}\n")
        sb.append("web search: ${p.webSearch} | allowed apps: ${p.allowedApps.size}\n")
        sb.append("\n=== log ===\n")
        sb.append(try { file?.readText().orEmpty() } catch (_: Exception) { "" })
        return sb.toString()
    }

    /** Intent مشاركة (Chooser) لملف التقرير */
    fun shareIntent(ctx: Context): Intent? = try {
        val dir = File(ctx.cacheDir, "logs").apply { mkdirs() }
        val out = File(dir, "nour-log.txt")
        out.writeText(buildReport(ctx))
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, "Nour AI log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            L.t("تصدير سجل الأخطاء", "Export error log")
        )
    } catch (e: Exception) { null }

    fun clear() { try { file?.writeText("") } catch (_: Throwable) {} }
}
