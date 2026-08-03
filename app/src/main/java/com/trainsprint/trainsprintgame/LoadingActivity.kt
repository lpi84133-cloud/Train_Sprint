package com.trainsprint.trainsprintgame

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Splash / loading screen. Works in both orientations, fully offline.
 * The bar starts empty and fills to exactly 100% right before the game opens.
 */
class LoadingActivity : AppCompatActivity() {

    private var progressBar: ProgressBar? = null
    private var tvPercent: TextView? = null
    private var tvLoading: TextView? = null

    private var currentProgress = 0
    private var launched = false

    private val ui = Handler(Looper.getMainLooper())
    private var animator: ValueAnimator? = null

    // Animated "Loading" dots
    private var dotCount = 0
    private val dotRunnable = object : Runnable {
        override fun run() {
            dotCount = (dotCount + 1) % 4
            tvLoading?.text = getString(R.string.loading) + ".".repeat(dotCount)
            ui.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_loading)
        bindViews()
        startProgress()
        ui.post(dotRunnable)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-inflate the correct orientation layout and keep current progress.
        setContentView(R.layout.activity_loading)
        bindViews()
    }

    private fun bindViews() {
        progressBar = findViewById(R.id.progress)
        tvPercent = findViewById(R.id.tvPercent)
        tvLoading = findViewById(R.id.tvLoading)
        progressBar?.max = 100
        progressBar?.progress = currentProgress
        tvPercent?.text = "$currentProgress%"
        tvLoading?.text = getString(R.string.loading) + ".".repeat(dotCount)
    }

    private fun startProgress() {
        animator?.cancel()
        animator = ValueAnimator.ofInt(0, 100).apply {
            duration = 4200L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { a ->
                currentProgress = a.animatedValue as Int
                progressBar?.progress = currentProgress
                tvPercent?.text = "$currentProgress%"
            }
            start()
        }
        // Guaranteed launch even on slow devices: force completion by 4.6s.
        ui.postDelayed({ finishToGame() }, 4600L)
    }

    private fun finishToGame() {
        if (launched) return
        launched = true
        animator?.cancel()
        currentProgress = 100
        progressBar?.progress = 100
        tvPercent?.text = "100%"
        ui.removeCallbacks(dotRunnable)
        startActivity(Intent(this, GameActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        animator?.cancel()
        ui.removeCallbacksAndMessages(null)
    }

    @Deprecated("Loading is not interruptible")
    override fun onBackPressed() {
        // Ignore back during loading.
    }
}
