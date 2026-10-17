package com.nour.assistant.gesture

import com.nour.assistant.L

/** الأفعال اللي تقدر تربطها بإيماءتك */
enum class GestureAction(val key: String, private val ar: String, private val en: String) {
    BACK("back", "رجوع", "Back"),
    HOME("home", "الشاشة الرئيسية", "Home"),
    RECENTS("recents", "التطبيقات الأخيرة", "Recent apps"),
    NOTIFICATIONS("notifications", "فتح الإشعارات", "Open notifications"),
    SCROLL_UP("scroll_up", "تمرير لفوق", "Scroll up"),
    SCROLL_DOWN("scroll_down", "تمرير لتحت", "Scroll down"),
    TAP("tap", "ضغط في مكان المؤشر", "Tap at cursor"),
    VOLUME_UP("vol_up", "رفع الصوت", "Volume up"),
    VOLUME_DOWN("vol_down", "خفض الصوت", "Volume down"),
    PLAY_PAUSE("play_pause", "تشغيل / إيقاف الوسائط", "Play / pause media"),
    LOCK("lock", "قفل الشاشة", "Lock screen"),
    OPEN_NOUR("open_nour", "نداء نور (يفتح النافذة والمايك)", "Call Nour (opens window + mic)");

    val label: String get() = L.t(ar, en)

    companion object {
        fun fromKey(k: String) = values().firstOrNull { it.key == k }
    }
}
