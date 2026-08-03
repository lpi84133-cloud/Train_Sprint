package com.trainsprint.trainsprintgame.ignition

import com.trainsprint.trainsprintgame.BuildConfig
import java.net.URI

/**
 * Host-suffix allowlist for URLs that arrive from outside the WebView: the
 * config endpoint's answer and push payload URLs. Deliberately not applied to
 * inside-WebView navigation — affiliate chains legitimately cross hosts nobody
 * can enumerate in advance.
 *
 * The suffix list is comma-separated in `gray.allowedHosts` and reaches the
 * runtime through BuildConfig, so no two builds can share the list unless the
 * operator writes it out twice. When the list is empty, the gate is disabled
 * and a warning is traced — a shipped build with no allowlist is a review
 * risk (an app that will load any URL a server names).
 */
internal object UrlGuard {

    private val suffixes: List<String> = BuildConfig.ALLOWED_HOSTS
        .split(',')
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }

    val enabled: Boolean = suffixes.isNotEmpty()

    /** True if [url] is safe to hand to a WebView / caller. Empty list ⇒ true. */
    fun accepts(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (suffixes.isEmpty()) return true
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
        return suffixes.any { s ->
            host == s || host.endsWith(".$s")
        }
    }

    fun warnIfMissing() {
        if (suffixes.isEmpty())
            Trace.w("UrlGuard", "gray.allowedHosts is empty — every incoming URL will be accepted")
    }
}
