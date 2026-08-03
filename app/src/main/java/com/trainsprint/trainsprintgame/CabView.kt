package com.trainsprint.trainsprintgame

import android.app.Activity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Immersive, edge-to-edge fullscreen helper shared by both activities. */
object CabView {
    fun apply(activity: Activity) {
        val window = activity.window
        // Referencing decorView here also guarantees it exists before we touch the controller.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
