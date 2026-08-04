package com.trainsprint.trainsprintgame.harness

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import com.trainsprint.trainsprintgame.BuildConfig
import com.trainsprint.trainsprintgame.R
import com.trainsprint.trainsprintgame.ignition.Trace
import com.trainsprint.trainsprintgame.ignition.UrlGuard
import com.trainsprint.trainsprintgame.deck.CarriageShell
import com.trainsprint.trainsprintgame.ledger.JunctionGate
import com.trainsprint.trainsprintgame.circuit.Sidings
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * Firebase Cloud Messaging receiver. The class name, package and channel id
 * are per-project (`.cursor/rules/kotlin_fingerprint.mdc`) — reached through
 * [BuildConfig] rather than hardcoded here.
 *
 * URL handling:
 *   * A URL that fails [UrlGuard] is dropped silently — a push tap must never
 *     open a page the app cannot recognise.
 *   * Warm URLs (shell alive) go straight to [WhistleBus] and are never saved.
 *   * Cold URLs go into the vault and are consumed exactly once by the router.
 *   * A user whose channel is NATIVE keeps their game: the URL becomes a
 *     harmless notification, and the tap opens the launcher instead of the
 *     WebView. Flipping a NATIVE user into a WebView after the fact is a
 *     store-review problem, not a feature.
 *
 * Image fetch runs on a background scope, so a slow image URL never blocks
 * the FCM service's main-thread callback.
 */
class FcmWhistle : FirebaseMessagingService() {

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        bg.cancel()
        super.onDestroy()
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Sidings(applicationContext).fcmToken = token
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        super.onMessageReceived(msg)
        val data = msg.data
        val notif = msg.notification
        val title = data["title"] ?: notif?.title ?: return
        val body  = data["body"]  ?: notif?.body  ?: return
        val rawUrl = data["url"] ?: data["link"] ?: ""
        val imgUrl = data["image"] ?: notif?.imageUrl?.toString() ?: ""

        val url = rawUrl.trim()
        val urlOk = url.isNotEmpty() && UrlGuard.accepts(url)
        if (url.isNotEmpty() && !urlOk) {
            Trace.w(TAG, "push URL rejected by allowlist — showing text-only notification")
        }

        val vault = Sidings(applicationContext)

        // A push must behave the same whether the app is open or not: land in the
        // tray and navigate only when the user taps it. The old warm path loaded
        // the URL the instant it arrived while the app was foreground and returned
        // without ever posting a notification — so a foreground push silently
        // replaced the current page and the tester saw neither a notification nor
        // the expected navigation. Always post the notification; the tap routes
        // through JunctionGate, which hands the URL to a live shell (warm) or
        // opens it cold. NATIVE users keep their game and only get the text.
        val tapUrl = if (urlOk && vault.runChannel != Sidings.RunChannel.NATIVE) url else ""

        // A live shell means a stream user is in the WebView right now (a NATIVE
        // user never has one). Route the tap straight at it: CarriageShell is
        // singleTask, so an explicit intent always reaches its running instance
        // through onNewIntent — none of the launcher/task juggling that was
        // eating the warm tap while the app was open. Otherwise the launcher
        // handles it (cold open, or a background-alive shell via the bus queue).
        val warm = tapUrl.isNotEmpty() && WhistleBus.shellAlive

        // Stash for a cold tap only when there is no live shell to hand off to:
        // a warm tap loads through the live shell (the tap intent carries the URL),
        // so leaving a cold URL behind would replay it on the next cold start.
        if (tapUrl.isNotEmpty() && !WhistleBus.shellAlive) vault.coldPushUrl = tapUrl

        bg.launch { showNotification(title, body, tapUrl, imgUrl, warm) }
    }

    private suspend fun showNotification(
        title: String, body: String, url: String, imgUrl: String, warm: Boolean
    ) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val tap = if (warm) {
            // Straight to the live WebView. singleTask delivers this to the
            // existing instance (onNewIntent → load the URL); if the shell died
            // between arrival and tap, a fresh CarriageShell reads the same warm
            // extras in onCreate and opens the URL directly. No CLEAR_TOP: it
            // would destroy the very shell being handed to.
            Intent(ctx, CarriageShell::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(CarriageShell.EXTRA_PUSH_URL, url)
                putExtra(CarriageShell.EXTRA_PUSH_WARM, true)
            }
        } else {
            Intent(ctx, JunctionGate::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (url.isNotBlank()) putExtra(JunctionGate.EXTRA_PUSH_URL, url)
                putExtra(JunctionGate.EXTRA_FROM_PUSH, true)
            }
        }
        val pi = PendingIntent.getActivity(
            ctx, System.currentTimeMillis().toInt(), tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(ctx, BuildConfig.FCM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_flame)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val bitmap = if (imgUrl.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                URL(imgUrl).openConnection().apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                }.getInputStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }

        if (bitmap != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bitmap)
                    .bigLargeIcon(null as android.graphics.Bitmap?)
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        withContext(Dispatchers.Main) {
            nm.notify(NOTIF_ID++, builder.build())
        }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(BuildConfig.FCM_CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            BuildConfig.FCM_CHANNEL_ID,
            BuildConfig.FCM_CHANNEL_TITLE,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableLights(true)
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val TAG = "FcmWhistle"
        @Volatile private var NOTIF_ID = 1001
    }
}
