package com.trainsprint.trainsprintgame.ledger

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.trainsprint.trainsprintgame.BuildConfig
import com.trainsprint.trainsprintgame.CabView
import com.trainsprint.trainsprintgame.BoardingView
import com.trainsprint.trainsprintgame.beacon.Depot
import com.trainsprint.trainsprintgame.beacon.RouteResult
import com.trainsprint.trainsprintgame.ignition.Trace
import com.trainsprint.trainsprintgame.ignition.UrlGuard
import com.trainsprint.trainsprintgame.ignition.UserAgent
import com.trainsprint.trainsprintgame.deck.SemaphorePrompt
import com.trainsprint.trainsprintgame.deck.DeadTrackScreen
import com.trainsprint.trainsprintgame.deck.CarriageShell
import com.trainsprint.trainsprintgame.GameActivity
import com.trainsprint.trainsprintgame.dispatch.DispatchClient
import com.trainsprint.trainsprintgame.harness.WhistleBus
import com.trainsprint.trainsprintgame.circuit.Sidings
import com.trainsprint.trainsprintgame.circuit.Sidings.RunChannel
import com.trainsprint.trainsprintgame.gauge.TrackMon
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Entry-point router. Shows the branded loading screen while performing the
 * gray/white decision in the background. State machine — every branch below
 * is grounded in `.cursor/rules/kotlin_launch_flow.mdc`; changes need a read
 * there first.
 *
 *  UNDECIDED (first launch):
 *    * No internet → DeadTrackScreen on the first frame. Nothing started or
 *      persisted; the offline screen relaunches this router when the link
 *      returns.
 *    * Has internet → ignite AppsFlyer → attribution + deep link → config
 *      POST → decide.
 *      ok+url         → STREAM → optional SemaphorePrompt → CarriageShell
 *      otherwise      → the native part, and persist NATIVE only when the
 *                       endpoint really answered AND the attribution was
 *                       non-empty.
 *
 *  STREAM (was WebView last time):
 *    * No internet → DeadTrackScreen with the saved URL.
 *    * Cold push URL → CarriageShell (highest priority).
 *    * Attribution → config POST.
 *      ok+url         → CarriageShell(newUrl)
 *      failure+saved  → CarriageShell(savedUrl)
 *      failure+none   → DeadTrackScreen
 *
 *  NATIVE (was the game last time):
 *    * The game, always. Once native, stay native — including if a push URL
 *      arrives for this install.
 */
class JunctionGate : AppCompatActivity() {

    private lateinit var vault: Sidings
    private lateinit var wire: TrackMon
    private var splash: BoardingView? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = Sidings(applicationContext)
        wire  = TrackMon(applicationContext)

        val pushUrl = pushUrlFrom(intent)

        // Warm-tap hand-off: a live shell means the user is in the WebView right
        // now, whatever the persisted channel says — a debug-forced session never
        // writes STREAM, and gating on it here is exactly why a foreground push
        // tap did nothing. handOver loads it through onWarmUrl when the shell is
        // resumed, or queues it for the shell's onStart when the tap arrives while
        // it is backgrounded. NATIVE users have no live shell, so handOver returns
        // false for them and control falls through to the game branch below.
        // Draw nothing on this path so the page the user left is never replaced.
        if (pushUrl != null && WhistleBus.handOver(pushUrl)) {
            Trace.i(TAG, "Warm push handed to the live shell")
            finish()
            overridePendingTransition(0, 0)
            return
        }

        // NATIVE users keep their game, regardless of what a push carries.
        if (vault.runChannel == RunChannel.NATIVE) {
            Trace.i(TAG, "Returning NATIVE — game, no attribution work")
            setContentView(BoardingView(this, indeterminate = true) {})
            CabView.apply(this)
            scope.launch { goNative() }
            return
        }

        // Installed via a link with the radio off. Straight to the no-wifi
        // screen — no splash, no bar for a decision that will not be made.
        if (pushUrl == null &&
            vault.runChannel == RunChannel.UNDECIDED &&
            !wire.isConnected()
        ) {
            Trace.i(TAG, "First run with no link → offline first frame")
            startActivity(Intent(this, DeadTrackScreen::class.java))
            finish()
            return
        }

        val loader = BoardingView(this, indeterminate = true) { /* never auto-completes */ }
        splash = loader
        setContentView(loader)
        CabView.apply(this)

        // Cold-start URL: STREAM channel already means WebView was the last
        // face of the app; UNDECIDED will become STREAM via route(). NATIVE
        // was handled above.
        if (pushUrl != null) {
            Trace.i(TAG, "Cold push URL received")
            vault.coldPushUrl = pushUrl
        }

        scope.launch { route() }
    }

    // ── State machine ───────────────────────────────────────────────────────

    private suspend fun route() {
        val forced = BuildConfig.DEBUG_FORCE_URL
        if (BuildConfig.DEBUG && forced.isNotBlank()) {
            Trace.w(TAG, "DEBUG: forcing stream URL")
            goGray(forced)
            return
        }

        val coldPush = vault.coldPushUrl
        if (!coldPush.isNullOrBlank() && UrlGuard.accepts(coldPush)) {
            Trace.i(TAG, "Cold push URL → STREAM directly")
            vault.coldPushUrl = null
            if (vault.runChannel == RunChannel.UNDECIDED)
                vault.runChannel = RunChannel.STREAM
            goGray(coldPush)
            return
        }

        when (vault.runChannel) {
            RunChannel.NATIVE   -> goNative()
            RunChannel.STREAM   -> handleOnlineReturn()
            RunChannel.UNDECIDED -> handleFirstLaunch()
        }
    }

    private suspend fun handleFirstLaunch() {
        if (!ensureInternet(isFirstLaunch = true)) return

        val tracker = (applicationContext as RailApp).trackingDispatch
        tracker.ignite(this)
        tracker.retrace(this)
        val attribution = tracker.awaitAttribution(Depot.attributionFirstMs)

        val result = fetchConfig(attribution)
        if (result.active && !result.destination.isNullOrBlank()) {
            vault.runChannel     = RunChannel.STREAM
            vault.destinationUrl = result.destination
            vault.urlExpiresAt   = result.expiresAt
            goGray(result.destination)
        } else {
            // A "no" sticks forever, so it has to be a real one — the endpoint
            // did answer, and it did with this install's attribution in hand.
            when {
                !result.answered ->
                    Trace.i(TAG, "endpoint unreachable → game, decision left open")
                attribution.isEmpty() ->
                    Trace.i(TAG, "no attribution behind the answer → game, decision left open")
                else -> {
                    vault.runChannel = RunChannel.NATIVE
                    Trace.i(TAG, "backend → NATIVE")
                }
            }
            goNative()
        }
    }

    private suspend fun handleOnlineReturn() {
        if (!ensureInternet(isFirstLaunch = false)) return

        val coldPush = vault.consumeColdPushUrl()
        if (!coldPush.isNullOrBlank() && UrlGuard.accepts(coldPush)) {
            goGray(coldPush)
            return
        }

        val savedUrl = if (vault.isUrlValid()) vault.destinationUrl else null

        val tracker = (applicationContext as RailApp).trackingDispatch
        tracker.ignite(this)
        tracker.retrace(this)
        val attribution = tracker.awaitAttribution(Depot.attributionReturnMs)

        val result = fetchConfig(attribution)
        when {
            result.active && !result.destination.isNullOrBlank() -> {
                vault.destinationUrl = result.destination
                vault.urlExpiresAt   = result.expiresAt
                goGray(result.destination)
            }
            !savedUrl.isNullOrBlank() -> {
                goGray(savedUrl)
            }
            else -> handOver {
                startActivity(Intent(this, DeadTrackScreen::class.java))
                finish()
            }
        }
    }

    private suspend fun ensureInternet(isFirstLaunch: Boolean): Boolean {
        if (wire.isConnected()) return true

        // The old code left a collect on `wire.connectivityFlow` hanging past
        // the first `resume`. This variant hands ownership of a Job to the
        // suspended coroutine and cancels it on completion or cancellation.
        val gate = Channel<Boolean>(capacity = Channel.CONFLATED)
        val watcher: Job = scope.launch {
            wire.connectivityFlow.collect { ok -> gate.trySend(ok) }
        }
        val online = try {
            withTimeoutOrNull(Depot.connectGraceMs) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val listener = scope.launch {
                        for (v in gate) if (v) { cont.resume(true); break }
                    }
                    cont.invokeOnCancellation { listener.cancel() }
                }
            } == true
        } finally {
            watcher.cancel()
            gate.close()
        }
        if (online) return true

        val savedUrl = if (!isFirstLaunch && vault.isUrlValid()) vault.destinationUrl else null
        startActivity(
            Intent(this, DeadTrackScreen::class.java).apply {
                if (!savedUrl.isNullOrBlank())
                    putExtra(DeadTrackScreen.EXTRA_RETURN_URL, savedUrl)
            }
        )
        finish()
        return false
    }

    private suspend fun fetchConfig(attribution: Map<String, Any?>): RouteResult {
        val tracker = (applicationContext as RailApp).trackingDispatch
        val fcmToken = vault.fcmToken ?: getFcmToken()?.also { vault.fcmToken = it }

        val body = tracker.buildRequestBody(
            attributionData = attribution,
            os              = "Android",
            locale          = Locale.getDefault().toLanguageTag().replace('-', '_'),
            pushToken       = fcmToken,
            firebaseProject = Depot.resolveAnalyticsProject()
        )
        return DispatchClient().fetchChannel(body)
    }

    // ── Navigation ──────────────────────────────────────────────────────────

    private fun handOver(go: () -> Unit) {
        val view = splash
        if (view == null) go() else view.complete { if (!isFinishing) go() }
    }

    private fun goNative() = handOver {
        startActivity(
            Intent(this, GameActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    private fun goGray(url: String) = handOver {
        val target = if (vault.shouldShowNotifScreen()) SemaphorePrompt::class.java
                     else CarriageShell::class.java
        val extra = if (target == SemaphorePrompt::class.java)
            SemaphorePrompt.EXTRA_TARGET_URL else CarriageShell.EXTRA_STREAM_URL
        startActivity(
            Intent(this, target)
                .putExtra(extra, url)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private suspend fun getFcmToken(): String? =
        withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { cont ->
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (cont.isActive) cont.resume(if (task.isSuccessful) task.result else null)
                }
            }
        }

    /**
     * The URL a notification tap carried, in either shape it can arrive in.
     *
     * A data-only message reaches [FcmWhistle], which builds the tap intent with
     * this class's own extras. A message that carries a `notification` block is
     * drawn by the Firebase SDK itself whenever the app is not in the
     * foreground — that path never runs our service, and the tap opens the
     * launcher with the raw `data` payload as plain string extras instead.
     * Reading only our own extras is why a pushed link was dropped and the
     * shell reopened on the previously saved page (pitfalls #32).
     */
    private fun pushUrlFrom(intent: Intent): String? {
        val own = if (intent.getBooleanExtra(EXTRA_FROM_PUSH, false))
            intent.getStringExtra(EXTRA_PUSH_URL) else null
        val raw = intent.getStringExtra(FCM_KEY_URL) ?: intent.getStringExtra(FCM_KEY_LINK)
        return (own ?: raw)?.trim()?.takeIf { it.isNotBlank() && UrlGuard.accepts(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val pushUrl = pushUrlFrom(intent)

        if (!pushUrl.isNullOrBlank()) {
            // Once native, stay native — a push URL never flips the mode.
            if (vault.runChannel == RunChannel.NATIVE) {
                Trace.i(TAG, "Push tap while NATIVE — game stays open")
                return
            }
            // Live shell → hand the URL over (callback when resumed, queue when
            // backgrounded) and get out of the way, no splash.
            if (WhistleBus.handOver(pushUrl)) {
                Trace.i(TAG, "Warm push handed to the live shell (onNewIntent)")
                finish()
                overridePendingTransition(0, 0)
                return
            }
            // No live shell to take it. A stream user reopens the shell on the
            // pushed URL; an undecided install stashes it for the router's cold
            // path so the first real launch opens it.
            if (vault.runChannel == RunChannel.STREAM) {
                val dest = vault.destinationUrl?.takeIf { vault.isUrlValid() }
                startActivity(
                    Intent(this, CarriageShell::class.java)
                        .putExtra(CarriageShell.EXTRA_STREAM_URL, dest ?: pushUrl)
                        .putExtra(CarriageShell.EXTRA_PUSH_URL, pushUrl)
                        .putExtra(CarriageShell.EXTRA_PUSH_WARM, true)
                        .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
                finish()
            } else {
                vault.coldPushUrl = pushUrl
            }
        }
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    /** Present the current UA to callers who need to log it. */
    fun currentUserAgent(): String = UserAgent.value

    companion object {
        private const val TAG = "JunctionGate"
        const val EXTRA_FROM_PUSH = "from_push"
        const val EXTRA_PUSH_URL  = "push_url"

        /** Payload keys FcmWhistle reads, and the ones the SDK forwards verbatim. */
        private const val FCM_KEY_URL  = "url"
        private const val FCM_KEY_LINK = "link"
    }
}
