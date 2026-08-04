package com.trainsprint.trainsprintgame.deck

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.trainsprint.trainsprintgame.BuildConfig
import com.trainsprint.trainsprintgame.CabView
import com.trainsprint.trainsprintgame.R
import com.trainsprint.trainsprintgame.beacon.Depot
import com.trainsprint.trainsprintgame.ignition.Trace
import com.trainsprint.trainsprintgame.ignition.UserAgent
import com.trainsprint.trainsprintgame.harness.WhistleBus
import com.trainsprint.trainsprintgame.circuit.Sidings
import com.trainsprint.trainsprintgame.gauge.TrackMon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen WebView shell.
 *
 *  - Black background everywhere (no Android system flash on load / page exit).
 *  - Safe-area paddings: top inset in portrait, left+right insets in landscape
 *    (handles notch / cutout cameras).
 *  - Instant DeadTrackScreen navigation on connectivity loss — no DNS probe.
 *  - Keyboard handled by [PlatformSlide] (the view slides, it never resizes) plus the
 *    safe-area CSS kill injection.
 *  - A loading cover over redirect hops and failed loads, so the user only ever sees
 *    a finished page — never an intermediate hop or the WebView's own error page.
 *  - Cold + warm push URL routing through Intent extras / onNewIntent.
 *  - User-Agent ends with "appid/<bundleId> appname/<AppName>".
 */
class CarriageShell : AppCompatActivity() {

    private lateinit var wv: WebView
    private lateinit var container: FrameLayout
    private lateinit var vault: Sidings
    private lateinit var wire: TrackMon
    private lateinit var keyboard: PlatformSlide
    private val scope = CoroutineScope(Dispatchers.Main)

    /** Last main-frame URL that actually settled. What a renderer recovery reloads. */
    private var lastMainFrameUrl: String? = null

    /**
     * Deepest main-frame URL seen, settled or not. A redirect loop is resumed
     * from here rather than from the last settled page — restarting the chain
     * from its entry point only walks into the same loop again (pitfalls #30).
     */
    private var deepestHop: String? = null

    private var redirectRetries = 0
    /** One fallback to the configured entry point per settled page. */
    private var entryPointRetried = false
    private var rendererRecoveries = 0

    /** A failed load still reaches onPageFinished; without this it resets the budget. */
    private var loadFailed = false
    /** True once one page of this session has rendered — gates the cover. */
    private var firstPageSettled = false
    /** Keeps the cover raised across the reload a retry queues up. */
    private var retryPending = false

    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val fc = fileCallback ?: return@registerForActivityResult
        fileCallback = null
        fc.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
                ?: arrayOf()
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = Sidings(applicationContext)
        wire  = TrackMon(applicationContext)
        WhistleBus.shellAlive = true

        // Background stays black at all times — windowBackground in the theme is black,
        // and we keep the root view black too.
        container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = false
        }
        setContentView(container)
        applyInsets()

        keyboard = PlatformSlide(window.decorView, vault)
        keyboard.install()
        recreateWebView()

        hideSystemUi()
        enableNotchCutout()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (wv.canGoBack()) wv.goBack()
            }
        })

        // Choose the initial URL: warm push > intent extra > saved.
        val warmPush = intent.takeIf { it.getBooleanExtra(EXTRA_PUSH_WARM, false) }
            ?.getStringExtra(EXTRA_PUSH_URL)
        val coldPush = vault.consumeColdPushUrl()
        val initial  = warmPush
            ?: coldPush
            ?: intent.getStringExtra(EXTRA_STREAM_URL)
            ?: vault.destinationUrl

        if (initial.isNullOrBlank()) {
            Trace.w(TAG, "No URL to load — finishing")
            finish(); return
        }
        Trace.i(TAG, "loading initial URL (warm=${warmPush != null}, cold=${coldPush != null})")
        // Raise the loading screen on the very first frame, before the page has a
        // chance to paint. A cold or push launch hands over from the splash to a
        // WebView whose background is black; without the cover already up there is
        // a window — the whole redirect chain of a push link, seconds of it — where
        // that black is all the user sees. onPageStarted would only raise it once
        // the engine reports the navigation, which is too late. onPageFinished
        // drops it when the destination actually settles.
        raiseCover()
        wv.loadUrl(initial)

        // Connectivity monitoring — react instantly on OS callback.
        scope.launch {
            wire.connectivityFlow.collect { online ->
                if (!online) {
                    Trace.i(TAG, "Connectivity lost (callback) → DeadTrackScreen")
                    goOffline()
                }
            }
        }

        // Heartbeat — covers the case where the page is already loaded and the user
        // turns off the internet: no WebView request fails, so we actively probe.
        scope.launch {
            while (true) {
                delay(Depot.heartbeatMs)
                if (navigatedOffline) continue
                if (!wire.isConnected()) {
                    Trace.i(TAG, "Heartbeat: no network → DeadTrackScreen")
                    goOffline()
                }
            }
        }

        scope.launch {
            delay(Depot.safeAreaDelayMs)
            injectSafeAreaKill()
        }
    }

    /** Builds the WebView, puts it in the container and hooks everything to it. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun recreateWebView() {
        wv = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
                userAgentString = buildUserAgent()
                // Performance + compatibility.
                cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                loadsImagesAutomatically = true
                blockNetworkImage = false
                // Popups stay in this view. Asking for real second windows is what
                // makes the WebView demand a host for them and throw when it cannot
                // get one ("Parent WebView cannot host its own popup window").
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = true
            }
            setBackgroundColor(Color.BLACK)
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
        }
        container.addView(
            wv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.webViewClient   = buildClient()
        wv.webChromeClient = buildChromeClient()
        keyboard.bind(wv)
    }

    // ── Loading cover ───────────────────────────────────────────────────

    private var cover: View? = null
    private var coverJob: Job? = null

    /**
     * The offline artwork inside the cover, kept so a rotation can swap it for the
     * other orientation's image. Null whenever the cover is a plain scrim.
     */
    private var coverArt: ImageView? = null

    /**
     * Hides the empty view behind a scrim and a spinner while the session's
     * **first** page resolves. Nothing else earns a cover: every later
     * navigation, including every hop of an affiliate redirect chain, resolves
     * behind the page the user is already reading, so they see the destination
     * site appear rather than a loading screen sitting between them and it.
     *
     * Note what this is NOT: a snapshot of the view. `WebView.draw` into a software
     * canvas on a hardware-accelerated view yields solid black, which is precisely the
     * "black screen between redirects" this replaced.
     *
     * @param artwork draw the no-internet image behind the spinner instead of a
     *   scrim. Used on the way back from [DeadTrackScreen]: that screen is a
     *   separate Activity and CLEAR_TOP destroys it the moment the shell comes
     *   forward, so it cannot itself stay up while the page loads — and keeping it
     *   on top would stop the shell and leave the WebView unable to lay out at
     *   all. Repeating its image here is what makes the offline screen appear to
     *   stay until the page has fully loaded, which is the point.
     */
    private fun raiseCover(artwork: Boolean = false) {
        coverJob?.cancel()
        coverJob = null
        val existing = cover
        if (existing != null) {
            existing.animate().cancel()
            existing.alpha = 1f
            // A cover raised for the failed first load is a plain scrim and is
            // still up when the offline screen hands the shell back, so the
            // artwork has to be added to it here. Returning early instead is why
            // the no-internet image never appeared on the way back.
            if (artwork) addArtwork(existing as FrameLayout)
            existing.bringToFront()
            return
        }
        val fresh = FrameLayout(this).apply {
            // A deliberate loading screen, not a scrim. Fully opaque so the engine's
            // error page (the green robot) behind it never shows through, and a solid
            // near-black brand tone rather than pure black so it reads as "loading",
            // not "dead". A scrim here was exactly what let the robot bleed through.
            setBackgroundColor(COVER_BG)
            isClickable = true
            if (artwork) addArtwork(this)
            val spin = (56 * resources.displayMetrics.density).toInt()
            addView(
                android.widget.ProgressBar(this@CarriageShell).apply {
                    isIndeterminate = true
                    indeterminateTintList =
                        android.content.res.ColorStateList.valueOf(COVER_SPINNER)
                },
                FrameLayout.LayoutParams(spin, spin, android.view.Gravity.CENTER)
            )
        }
        cover = fresh
        container.addView(
            fresh,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        // A page that never reports back must not hold the screen for good.
        scope.launch {
            delay(COVER_MAX_MS)
            if (cover === fresh) {
                Trace.w(TAG, "Loading cover timed out")
                // The backstop must win even mid-retry: 20 s means the chain is
                // genuinely stuck, so clear the guard before releasing the cover.
                retryPending = false
                dropCover(0L)
            }
        }
    }

    /**
     * @param after grace before the page is handed back. A redirect hop finishes and
     *   starts the next load within a frame or two, and this is what keeps the cover
     *   from blinking off and on between them.
     */
    private fun dropCover(after: Long = COVER_LINGER_MS) {
        // A retry is queued: the load that just ended is a failed hop in the
        // redirect chain, not the destination. Both onPageFinished and
        // onProgressChanged(100) fire for that failed error document, and either
        // one dropping the cover here would bare the engine's error page — the
        // green robot — for the seconds until the next hop commits. The cover is
        // only ever released once the chain settles, where onPageFinished has
        // already cleared retryPending.
        if (retryPending) return
        val current = cover ?: return
        coverJob?.cancel()
        coverJob = scope.launch {
            delay(after)
            if (cover !== current) return@launch
            cover = null
            coverArt = null
            current.animate().alpha(0f).setDuration(150L).withEndAction {
                container.removeView(current)
            }.start()
        }
    }

    /**
     * Puts the no-internet image behind whatever the cover already holds, so the
     * spinner stays on top of it. Idempotent: a cover that already carries the
     * artwork is left alone.
     */
    private fun addArtwork(host: FrameLayout) {
        if (coverArt != null) return
        val art = ImageView(this).apply {
            setImageResource(offlineArtRes())
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        coverArt = art
        host.addView(
            art,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    /** The no-internet artwork for the current orientation, as DeadTrackScreen picks it. */
    private fun offlineArtRes(): Int =
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
            R.drawable.rl_nowifi_landscape else R.drawable.rl_nowifi_portrait

    // ── WebView clients ────────────────────────────────────────────────

    private var pageStartMs = 0L

    private fun buildClient() = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val u = req.url.toString()
            val scheme = u.substringBefore(':').lowercase()
            return when {
                scheme in WEB_SCHEMES -> {
                    if (req.isForMainFrame) deepestHop = u
                    false  // load inside this WebView
                }
                scheme == "intent" -> { openIntentUri(u); true }
                // Everything else is an app link: banks, wallets, messengers, stores.
                // Handing it to the WebView would only produce ERR_UNKNOWN_URL_SCHEME,
                // and the list of schemes worth knowing about has no end.
                else -> { openExternally(u); true }
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            pageStartMs = System.currentTimeMillis()
            loadFailed = false
            retryPending = false
            keyboard.forget()
            // shouldOverrideUrlLoading does not see every server-side 30x, so the
            // URL the engine actually committed to is the other half of the trail.
            if (url != BLANK) deepestHop = url
            // Only the very first page of the session is covered. After that the
            // previous page stays on screen while the next hop resolves, so a
            // redirect chain hands the user its destination instead of a scrim.
            if (url != BLANK && !firstPageSettled) raiseCover()
            Trace.i(TAG, "onPageStarted")
        }

        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
            if (!req.isForMainFrame) return
            loadFailed = true
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) err.errorCode else -1
            val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) err.description.toString() else ""
            Trace.w(TAG, "main-frame error $code \"$desc\" on ${req.url}")

            // A custom scheme reaching this point was already handed to the system;
            // the page behind it is still fine, so give it straight back.
            if (code == ERROR_UNSUPPORTED_SCHEME) {
                dropCover(0L)
                return
            }

            val isLoop = code == -9 || code == -1007 ||
                    desc.contains("too_many", ignoreCase = true)
            if (isLoop) {
                handleRedirectLoop(view, req.url.toString())
                return
            }

            val isNetErr = code in setOf(-2, -6, -7, -8, -11)
            if (isNetErr || !wire.isConnected()) {
                // goOffline() blanks the view itself. Blanking here as well used to
                // overwrite wv.url with about:blank *before* goOffline() read it as
                // the return target, so the offline screen was handed "about:blank"
                // and retry reloaded that — a page that is black by definition.
                goOffline()
                return
            }

            // Anything else: nothing is queued to fix it, so covering it would put a
            // spinner over a load that is already over. The cover belongs only where
            // a retry is actually pending — see handleRedirectLoop.
            dropCover(0L)
        }

        override fun onPageFinished(view: WebView, url: String) {
            Trace.i(TAG, "onPageFinished")
            if (loadFailed || url == BLANK) return
            redirectRetries = 0
            entryPointRetried = false
            retryPending = false
            firstPageSettled = true
            lastMainFrameUrl = url
            deepestHop = url
            injectSafeAreaKill()
            view.evaluateJavascript(keyboard.script, null)
            reportGeometry(view)
            dropCover()
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: android.webkit.RenderProcessGoneDetail
        ): Boolean {
            Trace.w(TAG, "render process gone, crashed=${detail.didCrash()}")
            if (isFinishing || view !== wv) {
                runCatching { view.destroy() }
                return true
            }
            if (rendererRecoveries >= MAX_RENDERER_RECOVERIES) {
                Trace.w(TAG, "renderer recovery budget exhausted → DeadTrackScreen")
                goOffline()
                return true
            }
            rendererRecoveries++
            replaceWebView()
            return true
        }
    }

    /**
     * Logs the sizes that decide whether the page can lay out at all: the view's
     * own measured box, the container it sits in, and the viewport the engine
     * committed to. A page rendered as a narrow strip shows up here as a small
     * innerWidth against a full-width view, which separates "the engine got the
     * wrong size" from "the site laid itself out badly".
     */
    private fun reportGeometry(view: WebView) {
        if (!BuildConfig.DEBUG) return
        Trace.i(TAG, "geometry view=${view.width}x${view.height} " +
                "container=${container.width}x${container.height} " +
                "padL=${container.paddingLeft} padR=${container.paddingRight} " +
                "scrollX=${view.scrollX} tX=${view.translationX} tY=${view.translationY}")
        view.evaluateJavascript(
            "JSON.stringify({iw:innerWidth,ih:innerHeight," +
                    "cw:document.documentElement.clientWidth," +
                    "sw:document.documentElement.scrollWidth," +
                    "sh:document.documentElement.scrollHeight," +
                    "vv:(window.visualViewport?Math.round(visualViewport.width):-1)," +
                    // Tells a page that loaded but paints black apart from an empty
                    // one: text length and the colours actually computed on it.
                    "txt:(document.body?document.body.innerText.length:-1)," +
                    "kids:(document.body?document.body.children.length:-1)," +
                    "bg:(document.body?getComputedStyle(document.body).backgroundColor:''), " +
                    "hbg:getComputedStyle(document.documentElement).backgroundColor," +
                    "url:location.href})"
        ) { Trace.i(TAG, "geometry page=$it") }
    }

    /**
     * ERR_TOO_MANY_REDIRECTS. Chromium gives up after 20 hops and affiliate
     * chains are routinely longer, so this is an ordinary condition rather than
     * a failure — the chain has to be resumed, not restarted.
     *
     * Three things this gets right that the obvious version does not:
     *
     *  - It resumes from [deepestHop]. Reloading the entry point walks the same
     *    hops again and burns the budget on the identical loop. `lastMainFrameUrl`
     *    is the wrong field for this: `onPageFinished` overwrites it with the
     *    page that settled, so by error time it names the chain's start.
     *  - It posts the reload instead of calling `loadUrl` from inside the
     *    callback. The engine is still unwinding the failed navigation at that
     *    point and swallows or defers a re-entrant load — which is where the
     *    multi-second stalls between attempts came from.
     *  - When the budget is gone it does not leave the user under an overlay
     *    until the cover's own timeout. ERR_TOO_MANY_REDIRECTS is not in the
     *    network-error set, so before this the exhausted path did nothing at all.
     *
     * Nothing here raises the cover. A loop in an affiliate chain is dead time
     * mid-navigation, not a state worth putting a screen in front of the user
     * for — the retry is queued within 60 ms and the page underneath is
     * replaced before it has drawn.
     */
    private fun handleRedirectLoop(view: WebView, failedUrl: String) {
        if (redirectRetries < Depot.redirectRetryMax) {
            redirectRetries++
            retryPending = true
            val resumeAt = deepestHop ?: failedUrl
            Trace.i(TAG, "redirect loop, resuming attempt $redirectRetries")
            // A failed main-frame load leaves the engine's own error page on screen —
            // black with the green Android robot. Every retry here takes a couple of
            // seconds, and affiliate chains routinely need two before they settle, so
            // that page would otherwise be what the user stares at for ~5 s after
            // tapping a link. Cover it: onPageFinished drops the cover once the chain
            // resolves, and the retry budget below ends in dropCover either way.
            raiseCover()
            postLoad(view, resumeAt)
            return
        }

        // Budget spent. The chain itself is stuck; the entry point the backend
        // named usually still resolves, and cookies picked up along the way are
        // often what the chain was missing.
        val entryPoint = vault.destinationUrl
        if (!entryPointRetried && !entryPoint.isNullOrBlank() && entryPoint != deepestHop) {
            entryPointRetried = true
            retryPending = true
            Trace.w(TAG, "redirect budget spent → retrying the configured entry point")
            raiseCover()
            postLoad(view, entryPoint)
            return
        }

        Trace.w(TAG, "redirect chain unresolvable — handing the page back")
        retryPending = false
        dropCover(0L)
    }

    /**
     * A load queued out of a WebViewClient callback. The short pause is dead
     * time in the middle of a navigation, not a delay the user can feel.
     */
    private fun postLoad(view: WebView, url: String) {
        view.postDelayed({
            if (!isFinishing && !isDestroyed) view.loadUrl(url)
        }, RETRY_PAUSE_MS)
    }

    /**
     * Builds a fresh WebView after a renderer death and puts the last good page back.
     * The dead one cannot be reused for anything, including being asked what it was
     * showing, so [lastMainFrameUrl] is what there is to go on.
     */
    private fun replaceWebView() {
        val resumeAt = lastMainFrameUrl ?: vault.destinationUrl ?: return
        val dead = wv
        container.removeView(dead)
        runCatching { dead.destroy() }
        recreateWebView()
        wv.loadUrl(resumeAt)
    }

    private fun buildChromeClient() = object : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            // Backstop for a page that reports progress but never a finished load.
            // about:blank is only ever loaded on the way out to the offline screen,
            // so its progress says nothing about the page the user is waiting for.
            if (newProgress < 100 || view.url == BLANK) return
            dropCover()
        }

        override fun onShowFileChooser(
            view: WebView, callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(arrayOf())
            fileCallback = callback
            return try {
                filePicker.launch(params.createIntent())
                true
            } catch (_: Exception) {
                fileCallback = null
                false
            }
        }
    }

    // ── Links the WebView cannot take ───────────────────────────────────

    private fun openExternally(url: String) {
        val intent = runCatching {
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrNull() ?: return
        launchOrIgnore(intent)
    }

    /**
     * intent:// URIs name a target app and usually carry a browser_fallback_url, so
     * there are three things to try before the user is left looking at nothing.
     */
    private fun openIntentUri(url: String) {
        val parsed = runCatching {
            Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
        }.getOrNull() ?: return
        val fallback = parsed.getStringExtra("browser_fallback_url")
        parsed.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        parsed.addCategory(Intent.CATEGORY_BROWSABLE)
        parsed.component = null
        parsed.selector = null

        if (launchOrIgnore(parsed)) return
        // The named app may be missing while some other app handles the scheme.
        parsed.`package` = null
        if (launchOrIgnore(parsed)) return
        if (!fallback.isNullOrBlank()) wv.loadUrl(fallback)
    }

    private fun launchOrIgnore(intent: Intent): Boolean =
        runCatching { startActivity(intent) }.isSuccess

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // ── Navigation ──────────────────────────────────────────────────────

    @Volatile private var navigatedOffline = false

    private fun goOffline() {
        if (navigatedOffline) return
        navigatedOffline = true
        // Captured before the view is blanked, and never allowed to be about:blank.
        // lastMainFrameUrl stays null until a page has actually settled, so a first
        // load that fails has only deepestHop to go on — onPageStarted records the
        // URL the engine committed to, which is exactly the page to come back to.
        val cur = lastMainFrameUrl
            ?: deepestHop
            ?: wv.url?.takeIf { it != BLANK }
            ?: vault.destinationUrl
        try { wv.stopLoading(); wv.loadUrl(BLANK) } catch (_: Exception) {}
        startActivity(Intent(this, DeadTrackScreen::class.java).apply {
            if (!cur.isNullOrBlank() && cur != BLANK)
                putExtra(DeadTrackScreen.EXTRA_RETURN_URL, cur)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        navigatedOffline = false

        if (intent.getBooleanExtra(EXTRA_PUSH_WARM, false)) {
            val url = intent.getStringExtra(EXTRA_PUSH_URL)
            if (!url.isNullOrBlank() && com.trainsprint.trainsprintgame.ignition.UrlGuard.accepts(url)) {
                Trace.i(TAG, "warm push → loading")
                wv.loadUrl(url)
                return
            }
        }

        // about:blank is never a destination worth honouring, whoever sent it —
        // reloading it is indistinguishable from a black screen.
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)?.takeIf { it != BLANK }
        val current = wv.url
        val target = streamUrl ?: vault.destinationUrl
        if (target.isNullOrBlank() || target == BLANK) return

        // Returning from the offline screen: goOffline() left this WebView on
        // about:blank, so a blank current URL is what identifies that path
        // (pitfalls #9). It is reloaded, never rebuilt — destroying a WebView to
        // swap in a fresh one is what can take the renderer down with it and
        // leave the replacement painting black.
        val fromOffline = current.isNullOrBlank() || current == BLANK
        if (fromOffline || current != target) {
            if (fromOffline) {
                // The cover carries the offline artwork so the no-internet screen
                // appears to stay up until the page has finished loading, and the
                // reset lets onPageStarted keep it raised.
                firstPageSettled = false
                loadFailed = false
                raiseCover(artwork = true)
            }
            Trace.i(TAG, "onNewIntent → reloading target (fromOffline=$fromOffline)")
            wv.loadUrl(target)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    // ── Insets / safe area ──────────────────────────────────────────────

    /**
     * Apply orientation-aware padding so the WebView never sits under the camera
     * notch / cutout.
     *   portrait  → top inset only
     *   landscape → left + right insets (cutout on either side)
     */
    private fun applyInsets() {
        container.setOnApplyWindowInsetsListener { v, insets ->
            val isLandscape = resources.configuration.orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val cutout = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                insets.displayCutout else null
            val topPad   = if (!isLandscape) (cutout?.safeInsetTop   ?: 0).coerceAtLeast(insetTop(insets))   else 0
            val leftPad  = if (isLandscape)  (cutout?.safeInsetLeft  ?: 0).coerceAtLeast(insetLeft(insets))  else 0
            val rightPad = if (isLandscape)  (cutout?.safeInsetRight ?: 0).coerceAtLeast(insetRight(insets)) else 0
            // Zero bottom padding: the page runs to the edge, the keyboard is
            // handled by PlatformSlide, and the WebView fills the container.
            v.setPadding(leftPad, topPad, rightPad, 0)
            insets
        }
        container.requestApplyInsets()
    }

    private fun insetTop(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            insets.getInsets(WindowInsets.Type.systemBars()).top else 0
    private fun insetLeft(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            insets.getInsets(WindowInsets.Type.systemBars()).left else 0
    private fun insetRight(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            insets.getInsets(WindowInsets.Type.systemBars()).right else 0

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        container.requestApplyInsets()
        keyboard.remeasure()
        CabView.apply(this)
        // The offline artwork is orientation-specific, and this Activity handles
        // rotation itself rather than being recreated, so it has to be swapped here.
        coverArt?.setImageResource(offlineArtRes())
    }

    private fun hideSystemUi() = CabView.apply(this)

    private fun enableNotchCutout() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    // ── JS injections ───────────────────────────────────────────────────

    /**
     * Safe-area CSS kill. The window already pads for the cutout, so a page that
     * also honours `env(safe-area-inset-*)` would leave a second empty band on
     * top of ours. Zeroing the variables removes that band.
     *
     * What it must not do is lay a finger on the page's own box model. An
     * earlier version zeroed `padding-left`, `padding-right` and `margin` on
     * `html, body, #__nuxt, #app, #root` — but sites build their gutters with
     * exactly those declarations, so the whole layout got squeezed flat against
     * both edges (pitfalls #10). Only `padding-top`, and only on the chrome
     * wrappers that are known to add a status-bar offset of their own.
     */
    private fun injectSafeAreaKill() {
        val sentinel = BuildConfig.JS_SAFE_AREA_SENTINEL
        val running  = sentinel + "R"
        wv.evaluateJavascript("""
            (function(){
              if(window.$running) return; window.$running = true;
              var CSS_ID = '$sentinel';
              var CSS_TEXT =
                ':root{' +
                  '--safe-area-inset-top:0px!important;' +
                  '--safe-area-inset-right:0px!important;' +
                  '--safe-area-inset-bottom:0px!important;' +
                  '--safe-area-inset-left:0px!important;' +
                  '--sat:0px!important;--sar:0px!important;' +
                  '--sab:0px!important;--sal:0px!important;' +
                  '--safe-top:0px!important;--safe-right:0px!important;' +
                  '--safe-bottom:0px!important;--safe-left:0px!important;' +
                '}' +
                '.gameview-mobile-header,.app-header{' +
                  'padding-top:0!important;' +
                '}';
              function apply(){
                var head = document.head || document.documentElement;
                if (!head) return;
                var m = document.querySelector('meta[name="viewport"]');
                if (m && !/viewport-fit\s*=\s*contain/i.test(m.getAttribute('content') || '')) {
                  var c = (m.getAttribute('content') || '')
                    .replace(/,?\s*viewport-fit\s*=\s*\w+/ig, '').trim();
                  m.setAttribute('content', c + (c ? ', ' : '') + 'viewport-fit=contain');
                }
                var s = document.getElementById(CSS_ID);
                if (!s) {
                  s = document.createElement('style');
                  s.id = CSS_ID;
                  head.appendChild(s);
                }
                if (s.textContent !== CSS_TEXT) s.textContent = CSS_TEXT;
                if (head.lastElementChild !== s) head.appendChild(s);
              }
              apply();
              ['pushState','replaceState'].forEach(function(fn){
                var orig = history[fn];
                history[fn] = function(){
                  var r = orig.apply(this, arguments);
                  setTimeout(apply, 80);
                  setTimeout(apply, 400);
                  return r;
                };
              });
              window.addEventListener('popstate', function(){ setTimeout(apply, 80); });
              setInterval(apply, 2500);
            })();
        """.trimIndent(), null)
    }

    // ── User agent ──────────────────────────────────────────────────────

    private fun buildUserAgent(): String = UserAgent.value

    override fun onStart() {
        super.onStart()
        navigatedOffline = false
        WhistleBus.onWarmUrl = { url ->
            runOnUiThread {
                Trace.i(TAG, "WhistleBus warm URL → loading")
                try { wv.loadUrl(url) } catch (_: Exception) {}
            }
        }
        WhistleBus.consume()?.let { url ->
            Trace.i(TAG, "queued push URL → loading")
            runCatching { wv.loadUrl(url) }
        }
    }

    override fun onStop() {
        if (WhistleBus.onWarmUrl != null) WhistleBus.onWarmUrl = null
        super.onStop()
    }

    override fun onDestroy() {
        if (WhistleBus.onWarmUrl != null) WhistleBus.onWarmUrl = null
        WhistleBus.shellAlive = false
        scope.cancel()
        try { wv.destroy() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val EXTRA_STREAM_URL = "stream_url"
        const val EXTRA_PUSH_URL   = "push_url"
        const val EXTRA_PUSH_WARM  = "push_warm"
        private const val TAG = "CarriageShell"

        /** Everything the WebView itself can take. Anything else belongs to an app. */
        private val WEB_SCHEMES =
            setOf("http", "https", "about", "data", "blob", "file", "javascript")

        private const val BLANK = "about:blank"

        /** Loading-cover fill: solid, opaque, near-black brand tone. */
        private const val COVER_BG = 0xFF0B0B0F.toInt()

        /** Loading-cover spinner: brand gold. */
        private const val COVER_SPINNER = 0xFFF2C464.toInt()

        /** Long enough to bridge one redirect hop, short enough not to be felt.
         *  Trimmed so the finished page is revealed the moment it settles. */
        private const val COVER_LINGER_MS = 60L

        /** No page may hold the screen longer than this, finished or not. */
        private const val COVER_MAX_MS = 20_000L

        /** Renderer recoveries per Activity — beyond this we go offline. */
        private const val MAX_RENDERER_RECOVERIES = 3

        /** Pause before a queued redirect-loop retry. Long enough to let the
         *  engine finish unwinding the failed navigation, short enough to be
         *  invisible — trimmed to shave dead time off a multi-restart chain. */
        private const val RETRY_PAUSE_MS = 40L
    }
}
