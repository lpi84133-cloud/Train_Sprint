package com.trainsprint.trainsprintgame

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.trainsprint.trainsprintgame.databinding.ActivityWebBinding

/**
 * Displays the Privacy Policy and Support pages. Loads the live URL when the
 * device is online and falls back to a bundled offline copy otherwise, so the
 * pages are ALWAYS available — with or without an internet connection.
 */
class WebActivity : AppCompatActivity() {

    private lateinit var b: ActivityWebBinding
    private var usedFallback = false
    private lateinit var localUrl: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityWebBinding.inflate(layoutInflater)
        setContentView(b.root)

        val type = intent.getStringExtra(EXTRA_TYPE) ?: TYPE_PRIVACY
        val remoteUrl: String
        if (type == TYPE_SUPPORT) {
            b.tvTitle.setText(R.string.support)
            remoteUrl = getString(R.string.support_url)
            localUrl = "file:///android_asset/support.html"
        } else {
            b.tvTitle.setText(R.string.privacy_policy)
            remoteUrl = getString(R.string.privacy_url)
            localUrl = "file:///android_asset/privacy_policy.html"
        }

        b.btnBack.setOnClickListener { finish() }

        with(b.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        }
        b.webView.setBackgroundColor(0xFFFFFFFF.toInt())
        b.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                b.webProgress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                b.webProgress.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) loadFallback()
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                loadFallback()
            }
        }

        if (isOnline()) b.webView.loadUrl(remoteUrl) else loadFallback()
    }

    private fun loadFallback() {
        if (usedFallback) return
        usedFallback = true
        b.webView.loadUrl(localUrl)
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val n = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(n) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    override fun onDestroy() {
        b.webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TYPE = "type"
        const val TYPE_PRIVACY = "privacy"
        const val TYPE_SUPPORT = "support"
    }
}
