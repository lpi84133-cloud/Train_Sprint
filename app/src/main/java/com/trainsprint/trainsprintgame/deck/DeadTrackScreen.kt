package com.trainsprint.trainsprintgame.deck

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.trainsprint.trainsprintgame.R
import com.trainsprint.trainsprintgame.ledger.JunctionGate
import com.trainsprint.trainsprintgame.gauge.TrackMon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * No-internet screen. Branded background PNG (portrait / landscape) with a RETRY button.
 * On retry, checks real connectivity and returns to JunctionGate or CarriageShell.
 */
class DeadTrackScreen : AppCompatActivity() {

    private lateinit var wire: TrackMon
    private val scope = CoroutineScope(Dispatchers.Main)
    private var retryBtn: TextView? = null
    private var returnUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wire = TrackMon(applicationContext)
        // A blank page is not somewhere to return to: treating it as one sent the
        // shell back to about:blank on retry. With no real URL the router runs its
        // decision again from the top instead, which is the correct fallback.
        returnUrl = intent.getStringExtra(EXTRA_RETURN_URL)
            ?.takeIf { it.isNotBlank() && it != "about:blank" }

        val isLandscape = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val bgRes = if (isLandscape) R.drawable.rl_nowifi_landscape
                    else R.drawable.rl_nowifi_portrait

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        val bg = ImageView(this).apply {
            setImageResource(bgRes)
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(bg)

        val btn = buildRetryButton()
        retryBtn = btn
        btn.setOnClickListener { tryRetry() }

        val lp = FrameLayout.LayoutParams(dpToPx(200), dpToPx(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = dpToPx(48)
        btn.layoutParams = lp
        root.addView(btn)

        setContentView(root)
        com.trainsprint.trainsprintgame.CabView.apply(this)

        scope.launch {
            wire.connectivityFlow.collect { online ->
                if (online) tryRetry()
            }
        }
    }

    private fun tryRetry() {
        retryBtn?.text = "Connecting..."
        retryBtn?.isEnabled = false
        scope.launch {
            val ok = wire.hasRealInternet()
            if (ok) {
                val next = if (!returnUrl.isNullOrBlank()) {
                    Intent(this@DeadTrackScreen, CarriageShell::class.java)
                        .putExtra(CarriageShell.EXTRA_STREAM_URL, returnUrl)
                        .setFlags(FLAG_ACTIVITY_CLEAR_TOP)
                } else {
                    // With no page to go back to, this is a first launch that began
                    // offline: the router runs its decision from the top, which is
                    // the first time AppsFlyer is asked anything. Patching state
                    // here instead would settle the install as organic.
                    Intent(this@DeadTrackScreen, JunctionGate::class.java)
                        .setFlags(FLAG_ACTIVITY_CLEAR_TASK or FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(next)
                // No cross-fade: the shell comes up already showing this same
                // artwork as its loading cover, so an animation here would only
                // flash a seam between two identical frames.
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
                finish()
            } else {
                retryBtn?.text = "RETRY"
                retryBtn?.isEnabled = true
            }
        }
    }

    private fun buildRetryButton(): TextView = TextView(this).apply {
        text = "RETRY"
        textSize = 17f
        gravity = Gravity.CENTER
        setTypeface(null, android.graphics.Typeface.BOLD)
        setBackgroundResource(R.drawable.rl_btn_accept)
        setTextColor(Color.parseColor("#1A0A00"))
        setPadding(0, 0, 0, 0)
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density + 0.5f).toInt()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        recreate()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RETURN_URL = "offline_return_url"
    }
}
