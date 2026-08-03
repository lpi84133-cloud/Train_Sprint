package com.trainsprint.trainsprintgame.circuit

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.trainsprint.trainsprintgame.BuildConfig
import com.trainsprint.trainsprintgame.ignition.Trace

/**
 * Persistent state for the gray flow. Two-tier storage:
 *   * plain SharedPreferences for flags and timestamps (non-sensitive), and
 *   * EncryptedSharedPreferences for URLs (destination, cold push).
 *
 * The file names and every key string are per-project — the build derives them
 * from `gray.seed` and writes them into BuildConfig, so no two apps in the
 * portfolio share so much as a preference filename. `rebrand.py` can rename
 * the file and its class name without touching semantics.
 *
 * If the encrypted store cannot be created (the crypto provider is missing or
 * the keystore is inaccessible), the *class* refuses to expose URL setters at
 * all — an in-memory holder is used instead, so a URL from an earlier launch
 * is never accidentally written to plaintext prefs on this device.
 */
class Sidings(ctx: Context) {

    /** Values persisted in [SharedPreferences]. */
    enum class RunChannel { UNDECIDED, STREAM, NATIVE }

    private val plain: SharedPreferences =
        ctx.getSharedPreferences(BuildConfig.PREFS_PLAIN, Context.MODE_PRIVATE)

    private val secureImpl: SharedPreferences? = try {
        val master = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            BuildConfig.PREFS_SECURE,
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Trace.w(TAG, "EncryptedSharedPreferences unavailable, keeping URLs in-memory", e)
        null
    }

    /** In-memory backup used when the encrypted store failed to open. */
    private val fallback = HashMap<String, String?>()

    private fun readSecure(key: String): String? =
        secureImpl?.getString(key, null) ?: fallback[key]

    private fun writeSecure(key: String, value: String?) {
        val store = secureImpl
        if (store != null) {
            store.edit().apply {
                if (value == null) remove(key) else putString(key, value)
                apply()
            }
        } else {
            fallback[key] = value
        }
    }

    // ── run channel ─────────────────────────────────────────────────────────

    var runChannel: RunChannel
        get() {
            val raw = plain.getString(BuildConfig.K_RUN_CHANNEL, null) ?: return RunChannel.UNDECIDED
            return runCatching { RunChannel.valueOf(raw) }.getOrDefault(RunChannel.UNDECIDED)
        }
        set(v) = plain.edit().putString(BuildConfig.K_RUN_CHANNEL, v.name).apply()

    // ── destination URL + expiry ────────────────────────────────────────────

    var destinationUrl: String?
        get() = readSecure(BuildConfig.K_DEST_URL)
        set(v) = writeSecure(BuildConfig.K_DEST_URL, v)

    var urlExpiresAt: Long
        get() = plain.getLong(BuildConfig.K_EXPIRES, 0L)
        set(v) = plain.edit().putLong(BuildConfig.K_EXPIRES, v).apply()

    fun isUrlValid(): Boolean {
        val url = destinationUrl ?: return false
        if (url.isBlank()) return false
        val exp = urlExpiresAt
        return exp == 0L || System.currentTimeMillis() / 1000 < exp
    }

    // ── cold-start push URL (one-shot) ──────────────────────────────────────

    var coldPushUrl: String?
        get() = readSecure(BuildConfig.K_PUSH_COLD)
        set(v) = writeSecure(BuildConfig.K_PUSH_COLD, v)

    fun consumeColdPushUrl(): String? {
        val v = coldPushUrl
        coldPushUrl = null
        return v
    }

    // ── notification state ──────────────────────────────────────────────────

    var notifSkipUntil: Long
        get() = plain.getLong(BuildConfig.K_NOTIF_SKIP, 0L)
        set(v) = plain.edit().putLong(BuildConfig.K_NOTIF_SKIP, v).apply()

    var notifGranted: Boolean
        get() = plain.getBoolean(BuildConfig.K_NOTIF_GRANTED, false)
        set(v) = plain.edit().putBoolean(BuildConfig.K_NOTIF_GRANTED, v).apply()

    var notifOsDenied: Boolean
        get() = plain.getBoolean(BuildConfig.K_NOTIF_OS_DENIED, false)
        set(v) = plain.edit().putBoolean(BuildConfig.K_NOTIF_OS_DENIED, v).apply()

    fun shouldShowNotifScreen(): Boolean {
        if (notifGranted) return false
        if (notifOsDenied) return false
        val now = System.currentTimeMillis() / 1000
        return now >= notifSkipUntil
    }

    fun snoozeNotifPrompt() {
        val now = System.currentTimeMillis() / 1000
        notifSkipUntil = now + BuildConfig.PUSH_SNOOZE_SEC
    }

    // ── FCM token ───────────────────────────────────────────────────────────

    var fcmToken: String?
        get() = readSecure(BuildConfig.K_FCM)
        set(v) = writeSecure(BuildConfig.K_FCM, v)

    // ── keyboard resting height (per orientation) ───────────────────────────

    fun keyboardRest(portrait: Boolean): Int =
        plain.getInt(if (portrait) BuildConfig.K_KB_PORTRAIT else BuildConfig.K_KB_LANDSCAPE, 0)

    fun rememberKeyboardRest(portrait: Boolean, height: Int) {
        plain.edit().putInt(
            if (portrait) BuildConfig.K_KB_PORTRAIT else BuildConfig.K_KB_LANDSCAPE,
            height
        ).apply()
    }

    private companion object { const val TAG = "Sidings" }
}
