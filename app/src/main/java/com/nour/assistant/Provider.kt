package com.nour.assistant

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** مزوّد ذكاء اصطناعي (أي API متوافق مع OpenAI) */
data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val enabled: Boolean = true
) {
    val host: String get() = try { java.net.URI(baseUrl).host ?: baseUrl } catch (_: Exception) { baseUrl }
}

/** تشفير مفاتيح الـ API بمفتاح الجهاز (Android Keystore). لو مش متاح بنكمّل من غير تشفير ونسجّل ده. */
object KeyCrypto {
    private const val ALIAS = "nour_api_keys_v1"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return kg.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, secretKey())
            "v1:" + Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        } catch (e: Throwable) {
            AppLog.eOnce("crypto", "keystore unavailable, storing key without encryption", e)
            "p:" + Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        }
    }

    fun decrypt(s: String): String {
        if (s.isEmpty()) return ""
        return try {
            when {
                s.startsWith("v1:") -> {
                    val raw = Base64.decode(s.removePrefix("v1:"), Base64.NO_WRAP)
                    val c = Cipher.getInstance("AES/GCM/NoPadding")
                    c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw, 0, 12))
                    String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
                }
                s.startsWith("p:") -> String(Base64.decode(s.removePrefix("p:"), Base64.NO_WRAP), Charsets.UTF_8)
                else -> s
            }
        } catch (e: Throwable) {
            AppLog.eOnce("crypto", "couldn't decrypt an API key (re-enter it)", e)
            ""
        }
    }
}

/** من 1 لـ 5 مزوّدين. المفاتيح متخزنة مشفّرة. */
class ProviderStore(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("nour_providers", Context.MODE_PRIVATE)

    fun all(): List<Provider> = try {
        val a = JSONArray(p.getString("list", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Provider(it.getString("id"), it.optString("name"), it.optString("url"), KeyCrypto.decrypt(it.optString("key")), it.optBoolean("on", true))
        }
    } catch (_: Exception) { emptyList() }

    fun enabled(): List<Provider> = all().filter { it.enabled && it.baseUrl.isNotBlank() }

    fun get(id: String?): Provider? = all().firstOrNull { it.id == id }

    fun save(list: List<Provider>) {
        val a = JSONArray()
        list.take(MAX).forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.baseUrl.trim().trimEnd('/'))
                .put("key", KeyCrypto.encrypt(it.apiKey.trim())).put("on", it.enabled))
        }
        p.edit().putString("list", a.toString()).apply()
    }

    companion object {
        const val MAX = 5
        fun newId() = java.util.UUID.randomUUID().toString().take(8)
    }
}

/** قوالب جاهزة (بتملا الاسم والـ URL) */
object ProviderPresets {
    class Preset(val name: String, val url: String)
    val list = listOf(
        Preset("OpenAI", "https://api.openai.com/v1"),
        Preset("Groq", "https://api.groq.com/openai/v1"),
        Preset("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
        Preset("OpenRouter", "https://openrouter.ai/api/v1"),
        Preset("DeepSeek", "https://api.deepseek.com/v1"),
        Preset("Mistral", "https://api.mistral.ai/v1"),
        Preset("Together", "https://api.together.xyz/v1"),
        Preset("xAI", "https://api.x.ai/v1")
    )
}
