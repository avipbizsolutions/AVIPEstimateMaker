package com.avip.estimatemaker

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Things that differ between the Google Play build and the GitHub build. */
object Store {
    /** This is the GitHub (sideloaded) build. */
    const val IS_PLAY = false

    /** Android 13+: allow the "app updated" notice. Asked once. */
    fun onHomeOpened(a: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        val prefs = a.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        if (a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }
}
