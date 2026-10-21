package com.nour.assistant

import android.app.Application

class NourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        try { Prefs(this).migrateLegacyProvider() } catch (e: Throwable) { AppLog.e("app", "provider migration failed", e) }
    }
}
