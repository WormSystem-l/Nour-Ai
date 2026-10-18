package com.nour.assistant

import android.app.Application

class NourApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }
}
