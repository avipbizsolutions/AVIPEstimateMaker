package com.avip.estimatemaker

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Launch screen: loads latest prices (and, in the GitHub build, app updates), then opens the app. */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: View
    private var leaving = false

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    /** GitHub build: self-update. Google Play build: does nothing. */
    private val updates = SelfUpdate(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = vertical {
            gravity = Gravity.CENTER
            background = gradient(0f, Ui.BRAND_DARK, Ui.BRAND)
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        val brandBlock = vertical {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.bsc_logo)
                elevation = dpf(6f)
            }, lp(dp(230), dp(230)))
            addView(text("B2B solar prices & quotations in seconds", 14f, 0xDDFFFFFF.toInt()) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 20))
        }
        // Spacer above pushes the logo block to the middle; the footer sits at the bottom.
        root.addView(View(this), lp(MATCH, 0, 1f))
        root.addView(brandBlock)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.SUN)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.SUN)
            max = 100
        }
        root.addView(progress, lp(dp(200), WRAP).margins(this, t = 36))
        status = text("Loading latest prices…", 13f, 0xDDFFFFFF.toInt()) { gravity = Gravity.CENTER }
        root.addView(status, lp(WRAP, WRAP).margins(this, t = 10))
        retry = pillButton("Retry", Ui.SUN, Ui.TEXT) { start() }.apply { visibility = View.GONE }
        root.addView(retry, lp(dp(160), WRAP).margins(this, t = 16))
        root.addView(View(this), lp(MATCH, 0, 1f))
        root.addView(text("by AVIP Business Solutions LLP", 14f, Ui.SUN, bold = true) { gravity = Gravity.CENTER }, lp(WRAP, WRAP))
        root.addView(text("v" + AppInfo.versionName(this), 11f, 0x99FFFFFF.toInt()), lp(WRAP, WRAP).margins(this, t = 4, b = 8))
        setEdgeContent(root, lightBars = false)

        brandBlock.alpha = 0f
        brandBlock.animate().alpha(1f).setDuration(450).start()

        updates.onCreate()
        Cart.load(this)

        // Tell the user once after an update.
        val versionCode = AppInfo.versionCode(this)
        val lastSeen = prefs.getLong("last_version_code", 0L)
        if (lastSeen in 1 until versionCode) {
            Toast.makeText(this, "Updated to version ${AppInfo.versionName(this)}", Toast.LENGTH_LONG).show()
        }
        prefs.edit().putLong("last_version_code", versionCode).apply()

        start()
    }

    fun start() {
        retry.visibility = View.GONE
        status.text = "Loading latest prices…"
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        val hadCache = Catalog.isLoaded || Catalog.loadCached(this)

        lifecycleScope.launch {
            val minimum = async { delay(1200) }
            val update = async { updates.fetchLatest() }
            val fresh = withTimeoutOrNull(10000) { Catalog.refresh(this@SplashActivity) } ?: false
            Remote.flushAsync(this@SplashActivity)

            if (updates.offer(update.await())) return@launch
            minimum.await()
            if (!fresh && !hadCache) {
                progress.visibility = View.GONE
                status.text = "Couldn't load prices. Check your internet connection."
                retry.visibility = View.VISIBLE
                return@launch
            }
            if (!fresh) toast("Offline: showing prices saved earlier")
            goNext()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        updates.onDestroy()
    }

    fun showStatus(text: String) { status.text = text }

    fun showProgress(pct: Int) {
        progress.isIndeterminate = false
        progress.progress = pct
    }

    fun goNext() {
        if (leaving || isFinishing) return
        if (!Catalog.isLoaded) { start(); return }
        leaving = true
        val next = if (Vendor.isComplete(this)) MainActivity::class.java else ProfileActivity::class.java
        startActivity(Intent(this, next))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
