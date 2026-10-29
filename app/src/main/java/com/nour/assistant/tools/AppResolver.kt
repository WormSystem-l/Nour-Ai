package com.nour.assistant.tools

import com.nour.assistant.AllowedApp

/** بيفهم اسم التطبيق اللي الموديل (أو المستخدم) قاله، عربي أو إنجليزي، ويطابقه على التطبيقات المسموحة */
object AppResolver {

    /** أسماء شائعة -> package */
    val ALIASES: Map<String, String> = mapOf(
        "instagram" to "com.instagram.android", "انستجرام" to "com.instagram.android",
        "انستا" to "com.instagram.android", "انستغرام" to "com.instagram.android",
        "whatsapp" to "com.whatsapp", "واتساب" to "com.whatsapp", "واتس" to "com.whatsapp", "وتساب" to "com.whatsapp",
        "facebook" to "com.facebook.katana", "فيسبوك" to "com.facebook.katana", "فيس" to "com.facebook.katana",
        "tiktok" to "com.zhiliaoapp.musically", "تيكتوك" to "com.zhiliaoapp.musically",
        "youtube" to "com.google.android.youtube", "يوتيوب" to "com.google.android.youtube",
        "telegram" to "org.telegram.messenger", "تليجرام" to "org.telegram.messenger",
        "تيليجرام" to "org.telegram.messenger", "تلجرام" to "org.telegram.messenger",
        "snapchat" to "com.snapchat.android", "سناب" to "com.snapchat.android", "سنابشات" to "com.snapchat.android",
        "spotify" to "com.spotify.music", "سبوتيفاي" to "com.spotify.music",
        "chrome" to "com.android.chrome", "كروم" to "com.android.chrome",
        "maps" to "com.google.android.apps.maps", "googlemaps" to "com.google.android.apps.maps",
        "خرائط" to "com.google.android.apps.maps", "ماب" to "com.google.android.apps.maps",
        "gmail" to "com.google.android.gm", "جيميل" to "com.google.android.gm",
        "twitter" to "com.twitter.android", "تويتر" to "com.twitter.android"
    )

    fun norm(s: String): String = s.lowercase()
        .replace(Regex("[\\u064B-\\u0652\\u0640]"), "")
        .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي').replace('ة', 'ه')
        .replace(Regex("[^\\p{L}\\p{N}.]"), "")

    fun resolve(query: String, allowed: List<AllowedApp>): AllowedApp? {
        val q = norm(query)
        if (q.isEmpty()) return null
        allowed.firstOrNull { it.pkg.equals(query.trim(), true) }?.let { return it }
        allowed.firstOrNull { norm(it.label) == q }?.let { return it }
        // اسم شائع -> package -> لو مسموح
        for ((alias, pkg) in ALIASES) {
            val a = norm(alias)
            if (q == a || q.contains(a) || (q.length >= 3 && a.contains(q))) {
                allowed.firstOrNull { it.pkg == pkg }?.let { return it }
            }
        }
        allowed.firstOrNull { val l = norm(it.label); l.isNotEmpty() && (l.contains(q) || q.contains(l)) }?.let { return it }
        return null
    }

    /**
     * كل التطبيقات المحتملة لاسم معين: لو فيه تطابق قوي (اسم/package/اسم شائع) بيرجّع واحد،
     * غير كده كل التطبيقات اللي اسمها فيه الكلمة (لو أكتر من واحد يبقى المستخدم لازم يحدد).
     */
    fun candidates(query: String, allowed: List<AllowedApp>): List<AllowedApp> {
        resolve(query, allowed)?.let { strong ->
            val q = norm(query)
            val exact = strong.pkg.equals(query.trim(), true) || norm(strong.label) == q ||
                ALIASES.keys.any { norm(it) == q || q.contains(norm(it)) }
            if (exact) return listOf(strong)
        }
        val q = norm(query)
        if (q.isEmpty()) return emptyList()
        val hits = allowed.filter { val l = norm(it.label); l.isNotEmpty() && (l.contains(q) || q.contains(l)) }
        return if (hits.isEmpty()) listOfNotNull(resolve(query, allowed)) else hits
    }
}
