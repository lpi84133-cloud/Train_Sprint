package com.trainsprint.trainsprintgame

import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.trainsprint.trainsprintgame.databinding.ActivityGameBinding
import kotlin.random.Random

class GameActivity : AppCompatActivity() {

    private lateinit var b: ActivityGameBinding
    private lateinit var prefs: GamePrefs
    private lateinit var sound: SoundManager
    private val rng = Random(System.nanoTime())
    private val ui = Handler(Looper.getMainLooper())

    private var spinning = false
    private var bonusRunning = false
    private var autoOn = false
    private var turboOn = false

    private var currentBet = 0L
    private var pendingBonus = false
    private var pendingGrid: Array<Array<Sym>> = Slot.randomGrid(rng)
    private var balanceAnim: ValueAnimator? = null

    // Ticking jackpot pots (MINI, MINOR, MAJOR, GRAND) — cosmetic social-casino flavor.
    private val jackpots = longArrayOf(
        SlotView.JP_MINI, SlotView.JP_MINOR, SlotView.JP_MAJOR, SlotView.JP_GRAND
    )
    private val jackpotTick = object : Runnable {
        override fun run() {
            jackpots[0] += rng.nextInt(3, 12)
            jackpots[1] += rng.nextInt(8, 40)
            jackpots[2] += rng.nextInt(40, 160)
            jackpots[3] += rng.nextInt(200, 900)
            renderJackpots()
            ui.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityGameBinding.inflate(layoutInflater)
        setContentView(b.root)

        prefs = GamePrefs(this)
        sound = SoundManager(this, prefs)

        b.slotView.setSoundHook { res -> sound.play(res) }
        b.slotView.setGrid(Slot.randomGrid(rng))

        b.btnSpin.setOnClickListener { sound.play(R.raw.button_click_asset); doSpin() }
        b.btnBetMinus.setOnClickListener { changeBet(-1) }
        b.btnBetPlus.setOnClickListener { changeBet(1) }
        b.btnAuto.setOnClickListener { toggleAuto() }
        b.btnTurbo.setOnClickListener { toggleTurbo() }
        b.btnSettings.setOnClickListener {
            sound.play(R.raw.menu_open_asset)
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        renderBet()
        renderBalance(prefs.balance)
        renderJackpots()
        applyToggleVisuals()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        renderBalance(prefs.balance)
        renderBet()
        ui.removeCallbacks(jackpotTick)
        ui.post(jackpotTick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(jackpotTick)
    }

    // ---- Spin flow ----

    private fun doSpin() {
        if (spinning || bonusRunning) return
        val bet = GamePrefs.BETS[prefs.betIndex]
        if (prefs.balance < bet) {
            sound.play(R.raw.error_asset)
            Toast.makeText(this, R.string.not_enough, Toast.LENGTH_SHORT).show()
            autoOn = false
            applyToggleVisuals()
            return
        }
        currentBet = bet
        prefs.balance -= bet
        renderBalance(prefs.balance)
        b.tvWin.text = "0"
        b.winBanner.visibility = View.GONE
        b.slotView.clearWins()

        spinning = true
        setControlsSpinning(true)

        pendingBonus = rng.nextDouble() < Slot.BONUS_CHANCE
        pendingGrid = if (pendingBonus) Slot.triggerGrid(rng, 6 + rng.nextInt(4))
        else Slot.randomGrid(rng)

        b.slotView.spin(pendingGrid, turboOn) { onSpinComplete() }
    }

    private fun onSpinComplete() {
        spinning = false
        if (pendingBonus) {
            startBonus(pendingGrid)
            return
        }
        val wins = Slot.evaluate(pendingGrid, currentBet)
        val total = wins.sumOf { it.amount }
        if (total > 0) {
            award(total)
            b.slotView.showWins(wins)
            presentWin(total)
        }
        setControlsSpinning(false)
        scheduleAutoIfNeeded()
    }

    private fun presentWin(total: Long) {
        when {
            total >= currentBet * 15 -> {
                sound.play(R.raw.big_win_asset)
                showBanner(getString(R.string.mega_win) + "\n" + fmt(total))
            }
            total >= currentBet * 5 -> {
                sound.play(R.raw.big_win_asset)
                showBanner(getString(R.string.big_win) + "\n" + fmt(total))
            }
            total >= currentBet * 2 -> sound.play(R.raw.win_medium_asset)
            else -> sound.play(R.raw.win_small_asset)
        }
    }

    private fun showBanner(text: String) {
        b.winBanner.text = text
        b.winBanner.visibility = View.VISIBLE
        ui.postDelayed({ b.winBanner.visibility = View.GONE }, 2200)
    }

    // ---- Hold & Win bonus ----

    private fun startBonus(grid: Array<Array<Sym>>) {
        bonusRunning = true
        setControlsSpinning(true)
        b.bonusBanner.visibility = View.VISIBLE
        b.tvBonusInfo.text = getString(R.string.respins_left) + ": 3"
        b.slotView.startHoldAndWin(
            bet = currentBet,
            triggerGrid = grid,
            jackpotValues = jackpots.copyOf(),
            onUpdate = { respins, total ->
                b.tvBonusInfo.text = getString(R.string.respins_left) + ": $respins   " +
                        getString(R.string.win) + ": " + fmt(total)
            },
            onFinished = { total, jp -> onBonusFinished(total, jp) }
        )
    }

    private fun onBonusFinished(total: Long, jp: Slot.Jackpot?) {
        bonusRunning = false
        b.bonusBanner.visibility = View.GONE
        award(total)
        b.slotView.setGrid(Slot.randomGrid(rng))
        val msg = if (jp == Slot.Jackpot.GRAND)
            getString(R.string.jackpot) + "\n" + fmt(total)
        else getString(R.string.hold_and_win) + "\n" + fmt(total)
        showBanner(msg)
        setControlsSpinning(false)
        scheduleAutoIfNeeded()
    }

    // ---- Money ----

    private fun award(amount: Long) {
        if (amount <= 0) return
        val from = prefs.balance
        val to = from + amount
        prefs.balance = to
        b.tvWin.text = fmt(amount)
        sound.play(R.raw.coin_collect_asset)
        balanceAnim?.cancel()
        balanceAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800L
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                b.tvBalance.text = fmt(from + (amount * f).toLong())
            }
            start()
        }
    }

    // ---- Controls ----

    private fun changeBet(dir: Int) {
        if (spinning || bonusRunning) return
        val ni = (prefs.betIndex + dir).coerceIn(0, GamePrefs.BETS.size - 1)
        if (ni != prefs.betIndex) {
            prefs.betIndex = ni
            sound.play(R.raw.button_click_asset)
            renderBet()
        }
    }

    private fun toggleAuto() {
        autoOn = !autoOn
        sound.play(R.raw.auto_spin_asset)
        applyToggleVisuals()
        if (autoOn && !spinning && !bonusRunning) doSpin()
    }

    private fun toggleTurbo() {
        turboOn = !turboOn
        sound.play(R.raw.turbo_mode_asset)
        applyToggleVisuals()
    }

    private fun scheduleAutoIfNeeded() {
        if (autoOn && !bonusRunning && prefs.balance >= GamePrefs.BETS[prefs.betIndex]) {
            ui.postDelayed({ if (autoOn) doSpin() }, if (turboOn) 350 else 850)
        } else if (autoOn) {
            autoOn = false
            applyToggleVisuals()
        }
    }

    private fun setControlsSpinning(busy: Boolean) {
        b.btnSpin.isEnabled = !busy
        b.btnSpin.alpha = if (busy) 0.5f else 1f
        b.btnBetMinus.isEnabled = !busy
        b.btnBetPlus.isEnabled = !busy
        b.btnBetMinus.alpha = if (busy) 0.5f else 1f
        b.btnBetPlus.alpha = if (busy) 0.5f else 1f
    }

    private fun applyToggleVisuals() {
        b.btnAuto.alpha = if (autoOn) 1f else 0.6f
        b.btnTurbo.alpha = if (turboOn) 1f else 0.6f
    }

    // ---- Rendering ----

    private fun renderBet() {
        b.tvBet.text = fmt(GamePrefs.BETS[prefs.betIndex])
    }

    private fun renderBalance(v: Long) {
        balanceAnim?.cancel()
        b.tvBalance.text = fmt(v)
    }

    private fun renderJackpots() {
        b.jpGrand.text = fmt(jackpots[3])
        b.jpMajor.text = fmt(jackpots[2])
        b.jpMinor.text = fmt(jackpots[1])
        b.jpMini.text = fmt(jackpots[0])
    }

    private fun fmt(v: Long): String = String.format("%,d", v)

    private fun hideSystemUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, b.root)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        balanceAnim?.cancel()
        sound.release()
    }
}
