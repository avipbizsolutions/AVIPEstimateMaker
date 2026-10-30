package com.avip.estimatemaker

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat

/** Installed version of this app. */
object AppInfo {
    fun versionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )

    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
}
