package com.avip.estimatemaker

/** Google Play build: Play installs updates, so the app never updates itself. */
@Suppress("UNUSED_PARAMETER")
class SelfUpdate(a: SplashActivity) {
    fun onCreate() {}
    fun onDestroy() {}
    suspend fun fetchLatest(): Unit? = null
    fun offer(latest: Unit?): Boolean = false
}
