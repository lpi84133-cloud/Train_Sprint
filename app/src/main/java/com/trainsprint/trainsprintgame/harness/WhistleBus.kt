package com.trainsprint.trainsprintgame.harness

/**
 * Process-wide hand-off for one-time push URLs, never persisted (one-time rule).
 *
 * Two cases, and they need different handling. A push that arrives while the shell is
 * on screen goes straight to [onWarmUrl]. A push the user taps while the app sits in
 * the background arrives at the launcher instead, and by then the shell has cleared
 * its callback — so the URL is [queue]d, the launcher draws nothing at all, and the
 * shell picks the URL up as it comes back. Drawing the splash for that would take the
 * page the user left off the screen for no reason.
 */
object WhistleBus {

    @Volatile
    var onWarmUrl: ((String) -> Unit)? = null

    /** True between CarriageShell's onCreate and onDestroy, screen state aside. */
    @Volatile
    var shellAlive = false

    @Volatile
    private var queue: String? = null

    /** @return true when the live shell will handle it and the caller must not route. */
    fun handOver(url: String): Boolean {
        val live = onWarmUrl
        if (live != null) {
            live(url)
            return true
        }
        if (!shellAlive) return false
        queue = url
        return true
    }

    fun consume(): String? {
        val url = queue
        queue = null
        return url
    }
}
