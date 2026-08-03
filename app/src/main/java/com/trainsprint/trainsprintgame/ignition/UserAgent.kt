package com.trainsprint.trainsprintgame.ignition

import android.os.Build
import com.trainsprint.trainsprintgame.BuildConfig

/**
 * The one place the User-Agent is built. Every part of the app that presents a
 * UA to the outside — the config POST, the WebView, any partner GET — reads
 * from here, so nothing that talks to a server sees a different string.
 *
 * Two projects never share the UA: the Chrome major/build/patch numbers are
 * drawn from the per-project seed and reach the runtime through BuildConfig.
 * A build flag toggles the `appid/<bundle> appname/<token>` suffix — the
 * partner backends that need it usually accept a header instead, so leave the
 * suffix off unless there is a specific reason to keep it (no honest browser
 * writes it).
 */
internal object UserAgent {

    val value: String by lazy(LazyThreadSafetyMode.PUBLICATION) { build() }

    private fun build(): String {
        val ver = Build.VERSION.RELEASE
        val brand = safe(Build.BRAND)
        val model = safe(Build.MODEL).replace(' ', '_')
        val buildId = safe(Build.ID)
        val chrome = "${BuildConfig.UA_CHROME_MAJOR}.0.${BuildConfig.UA_CHROME_BUILD}.${BuildConfig.UA_CHROME_PATCH}"

        val base = buildString {
            append("Mozilla/5.0 (Linux; Android ")
            append(ver)
            append("; ")
            append(brand)
            append(' ')
            append(model)
            if (buildId.isNotBlank()) {
                append(" Build/")
                append(buildId)
            }
            append(") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/")
            append(chrome)
            append(" Mobile Safari/537.36")
        }

        return if (BuildConfig.GRAY_UA_APP_SUFFIX)
            "$base appid/${BuildConfig.GRAY_BUNDLE_ID} appname/${BuildConfig.GRAY_UA_TOKEN}"
        else
            base
    }

    private fun safe(s: String?): String =
        s?.filter { it in ' '..'~' } ?: ""
}
