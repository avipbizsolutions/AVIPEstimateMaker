package com.avip.estimatemaker

import android.app.Activity

/** Things that differ between the Google Play build and the GitHub build. */
object Store {
    /** This is the Google Play build. */
    const val IS_PLAY = true

    /** Nothing to ask on the home screen: the Play build sends no notifications. */
    fun onHomeOpened(a: Activity) {}
}
