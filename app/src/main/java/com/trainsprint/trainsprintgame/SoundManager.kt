package com.trainsprint.trainsprintgame

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Lightweight SFX player backed by SoundPool. All clips are bundled in res/raw,
 * so audio works completely offline.
 */
class SoundManager(context: Context, private val prefs: GamePrefs) {

    private val appContext = context.applicationContext
    private val pool: SoundPool
    private val ids = HashMap<Int, Int>()
    private val loaded = HashSet<Int>()

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build()
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded.add(sampleId)
        }
        listOf(
            R.raw.button_click_asset,
            R.raw.spin_start_asset,
            R.raw.reel_stop_asset,
            R.raw.win_small_asset,
            R.raw.win_medium_asset,
            R.raw.big_win_asset,
            R.raw.coin_collect_asset,
            R.raw.jackpot_asset,
            R.raw.jackpot_coin_asset,
            R.raw.bonus_trigger_asset,
            R.raw.turbo_mode_asset,
            R.raw.auto_spin_asset,
            R.raw.balance_count_asset,
            R.raw.menu_open_asset,
            R.raw.menu_close_asset,
            R.raw.error_asset,
            R.raw.achievement_unlock_asset
        ).forEach { res -> ids[res] = pool.load(appContext, res, 1) }
    }

    fun play(res: Int, volume: Float = 1f) {
        if (!prefs.soundEnabled) return
        val id = ids[res] ?: return
        if (id in loaded) pool.play(id, volume, volume, 1, 0, 1f)
    }

    fun release() {
        pool.release()
    }
}
