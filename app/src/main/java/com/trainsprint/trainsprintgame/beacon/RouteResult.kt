package com.trainsprint.trainsprintgame.beacon

/**
 * Outcome of a config-endpoint call.
 *
 * [answered] separates the two kinds of "no". A server that replies — 404, an empty
 * body, `ok:false`, anything with a status line — has ruled on this install, and that
 * ruling is final and worth persisting. A request that never reached one has ruled on
 * nothing, and the run must not be recorded as native on the strength of it.
 */
data class RouteResult(
    val active: Boolean,
    val destination: String?,
    val expiresAt: Long,
    val answered: Boolean
) {
    companion object {
        fun native(answered: Boolean = true) = RouteResult(false, null, 0L, answered)
        fun unreachable() = native(answered = false)
        fun stream(url: String, exp: Long = 0L) = RouteResult(true, url, exp, true)
    }
}
