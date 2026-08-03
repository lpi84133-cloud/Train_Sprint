package com.trainsprint.trainsprintgame.ignition

import android.util.Log
import com.trainsprint.trainsprintgame.BuildConfig

/**
 * Every log line the gray flow writes goes through here. In debug it prints
 * normally, in release it does nothing at all — and the `if` sits on top of a
 * constant the compiler folds away, so an r8 pass strips the whole branch.
 *
 * A single-file bottleneck also means one place to add a per-project tag
 * prefix, silence a noisy area, or route to a file during bring-up.
 */
internal object Trace {
    fun i(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.i(tag, msg) }
    fun w(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.w(tag, msg) }
    fun w(tag: String, msg: String, t: Throwable) { if (BuildConfig.DEBUG) Log.w(tag, msg, t) }
    fun e(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.e(tag, msg) }
    fun e(tag: String, msg: String, t: Throwable) { if (BuildConfig.DEBUG) Log.e(tag, msg, t) }
}
