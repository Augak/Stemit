package com.stemit.app

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class StemitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val dark = getSharedPreferences("stemit", MODE_PRIVATE).getBoolean("dark_theme", false)
        AppCompatDelegate.setDefaultNightMode(if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
    }
}
