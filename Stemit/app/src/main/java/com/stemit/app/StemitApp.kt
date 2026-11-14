package com.stemit.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class StemitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val mode = getSharedPreferences("stemit", MODE_PRIVATE).getString("theme_mode", "system")
        AppCompatDelegate.setDefaultNightMode(when (mode) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        })
    }
}
