package com.trainsprint.trainsprintgame.circuit

import com.trainsprint.trainsprintgame.BuildConfig

/**
 * Decodes the strings the build encoded into BuildConfig. Never called with a
 * hard-coded array — every input comes from BuildConfig, so the class has no
 * knowledge of what it decodes and no ability to reveal anything on its own.
 *
 * Three interchangeable variants live here. Which one the runtime uses for a
 * particular build is chosen by [BuildConfig.CIPHER_VARIANT], which the build
 * script sets from `gray.codecVariant` in gray.properties. rebrand.py rotates
 * that value across projects, so two apps never share both the algorithm and
 * the parameters — see .cursor/rules/kotlin_fingerprint.mdc.
 *
 * The class name and file path themselves are meant to be renamed per project;
 * rebrand.py does that automatically.
 */
internal object Secrets {

    fun reveal(obscured: IntArray): String {
        if (obscured.isEmpty()) return ""
        val seed = BuildConfig.CIPHER_SEED
        val mult = BuildConfig.CIPHER_MULT
        val add  = BuildConfig.CIPHER_ADD
        val n = seed.size
        val out = ByteArray(obscured.size)
        for (i in obscured.indices) {
            val s = seed[i % n] and 0xFF
            val mix = when (BuildConfig.CIPHER_VARIANT) {
                1    -> (i * mult + add) and 0xFF
                2    -> ((i + 1) * mult xor add) and 0xFF
                else -> (((i * mult) and 0xFF) + add + (i shr 3)) and 0xFF
            }
            out[i] = ((obscured[i] and 0xFF) xor s xor mix).toByte()
        }
        return String(out, Charsets.UTF_8)
    }
}
