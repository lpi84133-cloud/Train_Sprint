package com.trainsprint.trainsprintgame.dispatch

import com.trainsprint.trainsprintgame.beacon.Depot
import com.trainsprint.trainsprintgame.beacon.RouteResult
import com.trainsprint.trainsprintgame.ignition.Trace
import com.trainsprint.trainsprintgame.ignition.UrlGuard
import com.trainsprint.trainsprintgame.ignition.UserAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Config endpoint client. One responsibility, one method: POST the attribution
 * body and return the parsed answer.
 *
 * The URL out of a successful response is checked against [UrlGuard] before it
 * is handed back. A destination outside the allowlist is treated the same as
 * `ok:false` — the app opens the native part, and the mode is not persisted
 * (the endpoint did answer, but its answer was rejected by our own gate, so
 * the "did the server rule on this install" question is still open next launch).
 */
class DispatchClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(Depot.configTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(Depot.configTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchChannel(body: JSONObject): RouteResult = withContext(Dispatchers.IO) {
        val endpoint = Depot.resolveConfigEndpoint()
        if (endpoint.isBlank()) {
            Trace.w(TAG, "endpoint is blank — nobody to ask")
            return@withContext RouteResult.unreachable()
        }
        Trace.i(TAG, "POST config endpoint")
        try {
            val req = Request.Builder()
                .url(endpoint)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", UserAgent.value)
                .post(body.toString().toRequestBody(json))
                .build()

            http.newCall(req).execute().use { resp ->
                val code = resp.code
                val raw = resp.body?.string().orEmpty()
                Trace.i(TAG, "HTTP $code (${raw.length} chars)")

                if (code == 404) return@withContext RouteResult.native()
                if (code !in 200..299) return@withContext RouteResult.native()
                parseResponse(raw)
            }
        } catch (e: Exception) {
            Trace.w(TAG, "request never landed: ${e.message}")
            RouteResult.unreachable()
        }
    }

    private fun parseResponse(raw: String): RouteResult {
        if (raw.isBlank()) return RouteResult.native()
        return try {
            val j = JSONObject(raw)
            val ok = j.optBoolean("ok", false)
            val url = j.optString("url", "")
            val exp = j.optLong("expires", 0L)
            if (ok && url.isNotBlank()) {
                if (!UrlGuard.accepts(url)) {
                    Trace.w(TAG, "endpoint URL rejected by allowlist")
                    return RouteResult.native()
                }
                RouteResult.stream(url, exp)
            } else {
                RouteResult.native()
            }
        } catch (e: Exception) {
            Trace.w(TAG, "JSON parse error: ${e.message}")
            RouteResult.native()
        }
    }

    private companion object { const val TAG = "DispatchClient" }
}
