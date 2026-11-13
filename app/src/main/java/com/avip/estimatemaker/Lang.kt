package com.avip.estimatemaker

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.view.View

/**
 * Interface language: English, or Hindi (Devanagari) with technical words such as
 * Inverter, Panel and GST kept in English. Documents (PDFs) stay in English.
 */
object Lang {
    @Volatile var hindi: Boolean = false
        private set

    fun load(c: Context) {
        hindi = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("lang", "en") == "hi"
    }

    fun set(c: Context, useHindi: Boolean) {
        hindi = useHindi
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("lang", if (useHindi) "hi" else "en").apply()
    }
}

/** Parts of the app that are switched off for now. */
object Features {
    /** Customer Quotation, GST Tax Invoice, Google Drive and My Business (hidden for now). */
    const val SALES_DOCS = false
}

/** Picks the text for the current interface language. */
fun tr(en: String, hi: String): String = if (Lang.hindi) hi else en

/** Loads the saved language before any screen opens. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Lang.load(this)
    }
}

/** Top-bar chip that switches between English and हिंदी, then redraws the screen. */
fun Activity.langToggle(): View = text(if (Lang.hindi) "EN" else "हिंदी", 13f, Color.WHITE, bold = true) {
    background = rounded(0x33FFFFFF, dpf(14f), 0x88FFFFFF.toInt(), dp(1))
    setPadding(dp(10), dp(5), dp(10), dp(5))
    contentDescription = "Language"
    setOnClickListener {
        Lang.set(this@langToggle, !Lang.hindi)
        recreate()
    }
}.also {
    it.layoutParams = android.widget.LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(4), 0, dp(6), 0) }
}
