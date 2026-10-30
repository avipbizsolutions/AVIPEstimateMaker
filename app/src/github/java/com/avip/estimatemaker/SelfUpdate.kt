package com.avip.estimatemaker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * GitHub (sideloaded) build only: updates the app from GitHub Releases on launch.
 * The Google Play build has a no-op version of this class; Play handles its updates.
 */
class SelfUpdate(private val a: SplashActivity) {

    private var pending: ReleaseInfo? = null
    private val prefs get() = a.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirm != null) {
                        try {
                            a.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
                        } catch (_: Exception) {
                        }
                    }
                    a.goNext()
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                else -> { a.toast("Update could not be installed"); a.goNext() }
            }
        }
    }

    private val unknownSourcesLauncher =
        a.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val r = pending
            if (r != null && Updater.canInstall(a)) a.lifecycleScope.launch { downloadAndInstall(r) } else a.goNext()
        }

    fun onCreate() {
        ContextCompat.registerReceiver(
            a, installStatusReceiver, IntentFilter(Updater.ACTION_INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun onDestroy() {
        try { a.unregisterReceiver(installStatusReceiver) } catch (_: Exception) {}
    }

    suspend fun fetchLatest(): ReleaseInfo? =
        withTimeoutOrNull(5000) { withContext(Dispatchers.IO) { Updater.fetchLatest() } }

    /** Returns true when the update flow has taken over the splash screen. */
    fun offer(latest: ReleaseInfo?): Boolean {
        if (latest == null || latest.versionCode <= AppInfo.versionCode(a)) return false
        pending = latest
        if (Updater.canInstall(a)) {
            a.lifecycleScope.launch { downloadAndInstall(latest) }
            return true
        }
        if (prefs.getLong("declined_update", 0L) != latest.versionCode) {
            askInstallPermission(latest)
            return true
        }
        return false
    }

    private suspend fun downloadAndInstall(release: ReleaseInfo) {
        a.showStatus("Updating to version ${release.versionName}…")
        a.showProgress(0)
        val apk = withContext(Dispatchers.IO) {
            Updater.download(a, release) { pct -> a.runOnUiThread { a.showProgress(pct) } }
        }
        if (apk == null) { a.toast("Update download failed"); a.goNext(); return }
        a.showStatus("Installing update…")
        try {
            withContext(Dispatchers.IO) { Updater.install(a, apk) }
        } catch (_: Exception) {
            a.toast("Update could not be installed"); a.goNext()
        }
    }

    private fun askInstallPermission(release: ReleaseInfo) {
        AlertDialog.Builder(a)
            .setTitle("New version available")
            .setMessage("Version ${release.versionName} is ready. Allow Bihar Solar Circle to install its own updates? This is needed only once.")
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ ->
                unknownSourcesLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}"))
                )
            }
            .setNegativeButton("Later") { _, _ ->
                prefs.edit().putLong("declined_update", release.versionCode).apply()
                if (Catalog.isLoaded) a.goNext() else a.start()
            }
            .show()
    }
}
