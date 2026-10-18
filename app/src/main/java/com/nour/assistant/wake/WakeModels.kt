package com.nour.assistant.wake

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * موديلات Vosk الصغيرة بتاعت النداء. بتتنزل مرة واحدة (الصوت نفسه مبيطلعش بره الجهاز أبداً،
 * اللي بيتنزل هو الموديل بس) وبتتخزن في ملفات التطبيق.
 */
object WakeModels {
    data class Spec(val id: String, val url: String, val approxMb: Int)

    val AR = Spec("ar", "https://alphacephei.com/vosk/models/vosk-model-small-ar-tn-0.1-linto.zip", 158)
    val EN = Spec("en", "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40)

    private fun dir(ctx: Context, s: Spec) = File(ctx.filesDir, "vosk/${s.id}")

    /** فولدر الموديل نفسه (اللي جواه am/ و conf/) أو null لو لسه متنزلش */
    fun path(ctx: Context, s: Spec): File? {
        val d = dir(ctx, s)
        if (!File(d, ".ok").exists()) return null
        if (looksLikeModel(d)) return d
        return d.listFiles()?.firstOrNull { it.isDirectory && looksLikeModel(it) }
    }

    private fun looksLikeModel(d: File) = File(d, "am").isDirectory || File(d, "conf").isDirectory

    /** العربي إجباري، الإنجليزي اختياري */
    fun isReady(ctx: Context) = path(ctx, AR) != null

    fun delete(ctx: Context) { File(ctx.filesDir, "vosk").deleteRecursively() }

    /** تنزيل وفك ضغط. onProgress من 0 لـ 1 (على Thread خلفي). بيرجّع true لو نجح */
    suspend fun download(ctx: Context, s: Spec, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val target = dir(ctx, s)
        val zip = File(ctx.cacheDir, "vosk-${s.id}.zip")
        try {
            target.deleteRecursively(); target.mkdirs()
            var conn = URL(s.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            var hops = 0
            while (conn.responseCode in 300..399 && hops++ < 4) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(URL(s.url), loc).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            }
            if (conn.responseCode !in 200..299) return@withContext false
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: (s.approxMb * 1_000_000L)
            conn.inputStream.use { inp ->
                FileOutputStream(zip).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress((done.toFloat() / total * 0.8f).coerceAtMost(0.8f))
                    }
                }
            }
            conn.disconnect()
            unzip(zip, target) { onProgress(0.8f + it * 0.2f) }
            File(target, ".ok").writeText("ok")
            path(ctx, s) != null
        } catch (_: Exception) {
            target.deleteRecursively()
            false
        } finally {
            zip.delete()
        }
    }

    private fun unzip(zip: File, to: File, onProgress: (Float) -> Unit) {
        val root = to.canonicalPath + File.separator
        val size = zip.length().coerceAtLeast(1)
        var read = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val f = File(to, e.name)
                // حماية Zip Slip
                if (!f.canonicalPath.startsWith(root)) continue
                if (e.isDirectory) { f.mkdirs(); continue }
                f.parentFile?.mkdirs()
                FileOutputStream(f).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                    }
                }
                onProgress((read.toFloat() / (size * 2)).coerceAtMost(1f))
            }
        }
    }
}
