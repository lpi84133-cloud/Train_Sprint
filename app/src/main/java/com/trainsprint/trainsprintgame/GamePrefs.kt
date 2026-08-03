package com.trainsprint.trainsprintgame

import android.content.Context

/**
 * Persistent player state. All values are purely virtual in-game credits.
 * This is a social-casino simulator: no real money, no purchases, no cashout.
 */
class GamePrefs(context: Context) {

    private val sp = context.getSharedPreferences("train_sprint", Context.MODE_PRIVATE)

    var balance: Long
        get() = sp.getLong(KEY_BALANCE, START_BALANCE)
        set(value) = sp.edit().putLong(KEY_BALANCE, value.coerceAtLeast(0)).apply()

    var betIndex: Int
        get() = sp.getInt(KEY_BET_INDEX, DEFAULT_BET_INDEX)
        set(value) = sp.edit().putInt(KEY_BET_INDEX, value).apply()

    var soundEnabled: Boolean
        get() = sp.getBoolean(KEY_SOUND, true)
        set(value) = sp.edit().putBoolean(KEY_SOUND, value).apply()

    var musicEnabled: Boolean
        get() = sp.getBoolean(KEY_MUSIC, true)
        set(value) = sp.edit().putBoolean(KEY_MUSIC, value).apply()

    fun resetBalance() {
        balance = START_BALANCE
        betIndex = DEFAULT_BET_INDEX
    }

    companion object {
        private const val KEY_BALANCE = "balance"
        private const val KEY_BET_INDEX = "bet_index"
        private const val KEY_SOUND = "sound"
        private const val KEY_MUSIC = "music"

        const val START_BALANCE = 5_000_000L
        const val DEFAULT_BET_INDEX = 2

        /** Available total bets. Every value is divisible by the number of paylines (20). */
        val BETS = longArrayOf(20, 40, 100, 200, 400, 1000, 2000, 5000, 10000)
    }
}
