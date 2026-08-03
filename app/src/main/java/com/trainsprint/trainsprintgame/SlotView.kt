package com.trainsprint.trainsprintgame

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.min
import kotlin.random.Random

/**
 * Custom 5x3 reel view. Handles reel spin animation, win highlighting and the
 * Hold & Win bonus rendering. All game money is virtual (social casino).
 */
class SlotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val rng = Random(System.nanoTime())
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val bitmaps = HashMap<Sym, Bitmap>()

    // Reel window geometry (square cells, centred inside the view)
    private var originX = 0f
    private var originY = 0f
    private var cellW = 0f
    private var cellH = 0f
    private var gridW = 0f
    private var gridH = 0f

    private enum class State { IDLE, SPINNING, BONUS }
    private var state = State.IDLE

    // Current settled grid (col x row)
    private val current = Slot.randomGrid(rng)

    // ----- Paints -----
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55000000"); strokeWidth = dp(1.5f)
    }
    private val symPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val winPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val winStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.parseColor("#FFFFE9A8")
    }
    private val dimPaint = Paint().apply { color = Color.parseColor("#B3000000") }
    private val slotBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A0E0E") }
    private val slotStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); color = Color.parseColor("#C79A3B")
    }
    private val coinTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A2E00"); textAlign = Paint.Align.CENTER; isFakeBoldText = true
        setShadowLayer(dp(1.5f), 0f, dp(1f), Color.parseColor("#66FFF0C0"))
    }

    // ----- Reels -----
    private inner class Reel(val col: Int) {
        val cells = arrayOfNulls<Sym>(Slot.ROWS + 1) // 0 = hidden top, 1..ROWS visible
        var offset = 0f
        var spinning = false
        var stopArmed = false
        val pending = ArrayDeque<Sym>()

        fun settle(rows: Array<Sym>) {
            for (r in 0 until Slot.ROWS) cells[r + 1] = rows[r]
            cells[0] = Slot.randomSymbol(rng)
            offset = 0f
            spinning = false
        }

        fun startSpin(target: Array<Sym>, fillers: Int) {
            pending.clear()
            repeat(fillers) { pending.add(Slot.randomSymbol(rng)) }
            // Add target rows bottom→top so they scroll into view correctly
            for (r in Slot.ROWS - 1 downTo 0) pending.add(target[r])
            pending.add(Slot.randomSymbol(rng)) // hidden top filler
            spinning = true
            stopArmed = false
        }

        private fun produce(): Sym {
            if (pending.isNotEmpty()) {
                val s = pending.removeFirst()
                if (pending.isEmpty()) stopArmed = true
                return s
            }
            return Slot.randomSymbol(rng)
        }

        private fun shiftDown() {
            for (i in Slot.ROWS downTo 1) cells[i] = cells[i - 1]
            cells[0] = produce()
        }

        /** Advances by [px]; returns true if the reel just came to a stop. */
        fun advance(px: Float): Boolean {
            if (!spinning) return false
            offset += px
            while (offset >= cellH) {
                offset -= cellH
                shiftDown()
                if (stopArmed) {
                    offset = 0f
                    spinning = false
                    stopArmed = false
                    return true
                }
            }
            return false
        }
    }

    private val reels = Array(Slot.COLS) { Reel(it) }

    // Spin control
    private var turbo = false
    private var spinSpeedPxPerSec = 0f
    private var onSpinComplete: (() -> Unit)? = null
    private var reelsRemaining = 0

    // Win highlight
    private var winCells: Set<Long> = emptySet()
    private var pulsePhase = 0f

    // ----- Bonus (Hold & Win) -----
    class Coin(val text: String, val value: Long, val jackpot: Slot.Jackpot?) {
        var pop = 1f
    }

    private var bonusGrid = Array(Slot.COLS) { arrayOfNulls<Coin>(Slot.ROWS) }
    private var bonusRespins = 0
    private var bonusBet = 0L
    private var bonusTotal = 0L
    private var bonusGrandLabel: Slot.Jackpot? = null
    private var jackpots = longArrayOf(JP_MINI, JP_MINOR, JP_MAJOR, JP_GRAND)
    private var onBonusUpdate: ((respins: Int, total: Long) -> Unit)? = null
    private var onBonusFinished: ((total: Long, jackpot: Slot.Jackpot?) -> Unit)? = null
    private var soundHook: ((Int) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())

    fun setSoundHook(hook: (Int) -> Unit) { soundHook = hook }

    // ----- Frame loop -----
    private var lastFrame = 0L
    private var looping = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!looping) return
            val dt = if (lastFrame == 0L) 0f else (frameTimeNanos - lastFrame) / 1_000_000_000f
            lastFrame = frameTimeNanos
            step(dt.coerceAtMost(0.05f))
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startLoop() {
        if (looping) return
        looping = true
        lastFrame = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        looping = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun step(dt: Float) {
        pulsePhase += dt * 3.2f
        if (state == State.SPINNING) {
            val px = spinSpeedPxPerSec * dt
            for (reel in reels) {
                if (reel.spinning) {
                    val stopped = reel.advance(px)
                    if (stopped) {
                        soundHook?.invoke(R.raw.reel_stop_asset)
                        reelsRemaining--
                        if (reelsRemaining <= 0) finishSpin()
                    }
                }
            }
        }
        // bonus pop animation
        if (state == State.BONUS) {
            for (col in bonusGrid) for (c in col) if (c != null && c.pop < 1f) {
                c.pop = (c.pop + dt * 4f).coerceAtMost(1f)
            }
        }
    }

    // ----- Public API -----

    fun setGrid(grid: Array<Array<Sym>>) {
        for (c in 0 until Slot.COLS) {
            for (r in 0 until Slot.ROWS) current[c][r] = grid[c][r]
            reels[c].settle(grid[c])
        }
        winCells = emptySet()
        state = State.IDLE
        invalidate()
    }

    fun spin(target: Array<Array<Sym>>, turboMode: Boolean, onComplete: () -> Unit) {
        winCells = emptySet()
        turbo = turboMode
        onSpinComplete = onComplete
        for (c in 0 until Slot.COLS)
            for (r in 0 until Slot.ROWS) current[c][r] = target[c][r]

        val cps = if (turbo) 60f else 26f
        spinSpeedPxPerSec = cps * (if (cellH > 0) cellH else dp(90f))
        val baseFill = if (turbo) 10 else 18
        val extra = if (turbo) 4 else 7
        for (c in 0 until Slot.COLS) reels[c].startSpin(target[c], baseFill + c * extra)
        reelsRemaining = Slot.COLS
        state = State.SPINNING
        soundHook?.invoke(R.raw.spin_start_asset)
        startLoop()
    }

    private fun finishSpin() {
        state = State.IDLE
        onSpinComplete?.invoke()
        maybeStopLoop()
    }

    private fun maybeStopLoop() {
        if (state == State.IDLE && winCells.isEmpty()) stopLoop()
    }

    fun showWins(wins: List<Slot.LineWin>) {
        val set = HashSet<Long>()
        for (w in wins) for ((c, r) in w.cells) set.add(key(c, r))
        winCells = set
        if (set.isNotEmpty()) startLoop() else maybeStopLoop()
        invalidate()
    }

    fun clearWins() {
        winCells = emptySet()
        maybeStopLoop()
        invalidate()
    }

    // ----- Hold & Win bonus -----

    fun startHoldAndWin(
        bet: Long,
        triggerGrid: Array<Array<Sym>>,
        jackpotValues: LongArray,
        onUpdate: (respins: Int, total: Long) -> Unit,
        onFinished: (total: Long, jackpot: Slot.Jackpot?) -> Unit
    ) {
        bonusBet = bet
        jackpots = jackpotValues
        bonusTotal = 0L
        bonusGrandLabel = null
        bonusRespins = 3
        onBonusUpdate = onUpdate
        onBonusFinished = onFinished
        bonusGrid = Array(Slot.COLS) { arrayOfNulls<Coin>(Slot.ROWS) }

        for (c in 0 until Slot.COLS) for (r in 0 until Slot.ROWS) {
            if (triggerGrid[c][r] == Sym.COIN) {
                bonusGrid[c][r] = newCoin().also { it.pop = 0f; bonusTotal += it.value }
            }
        }
        state = State.BONUS
        soundHook?.invoke(R.raw.bonus_trigger_asset)
        onBonusUpdate?.invoke(bonusRespins, bonusTotal)
        startLoop()
        handler.postDelayed({ runRespin() }, 1100)
    }

    private fun newCoin(): Coin {
        // Small chance for a jackpot coin (excluding GRAND, which is full-screen only).
        val roll = rng.nextInt(1000)
        return when {
            roll < 6 -> Coin("MAJOR", jackpots[2], Slot.Jackpot.MAJOR)
            roll < 26 -> Coin("MINOR", jackpots[1], Slot.Jackpot.MINOR)
            roll < 80 -> Coin("MINI", jackpots[0], Slot.Jackpot.MINI)
            else -> {
                val mult = Slot.COIN_MULTIPLIERS[rng.nextInt(Slot.COIN_MULTIPLIERS.size)]
                val value = bonusBet * mult
                Coin(formatCredits(value), value, null)
            }
        }
    }

    private fun runRespin() {
        // Find empty cells
        val empties = ArrayList<Pair<Int, Int>>()
        for (c in 0 until Slot.COLS) for (r in 0 until Slot.ROWS) if (bonusGrid[c][r] == null) empties.add(c to r)
        if (empties.isEmpty()) { finishBonus(); return }

        soundHook?.invoke(R.raw.spin_start_asset)
        var landed = 0
        for ((c, r) in empties) {
            if (rng.nextInt(100) < 24) {
                val coin = newCoin().also { it.pop = 0f }
                bonusGrid[c][r] = coin
                bonusTotal += coin.value
                landed++
            }
        }

        if (landed > 0) {
            bonusRespins = 3
            soundHook?.invoke(R.raw.jackpot_coin_asset)
            onBonusUpdate?.invoke(bonusRespins, bonusTotal)
        } else {
            bonusRespins--
            onBonusUpdate?.invoke(bonusRespins, bonusTotal)
        }

        val full = (0 until Slot.COLS).all { c -> (0 until Slot.ROWS).all { r -> bonusGrid[c][r] != null } }
        when {
            full -> { bonusGrandLabel = Slot.Jackpot.GRAND; handler.postDelayed({ finishBonus() }, 900) }
            bonusRespins <= 0 -> handler.postDelayed({ finishBonus() }, 900)
            else -> handler.postDelayed({ runRespin() }, if (turbo) 550 else 950)
        }
    }

    private fun finishBonus() {
        var total = bonusTotal
        if (bonusGrandLabel == Slot.Jackpot.GRAND) total += jackpots[3]
        state = State.IDLE
        soundHook?.invoke(R.raw.jackpot_asset)
        onBonusFinished?.invoke(total, bonusGrandLabel)
        maybeStopLoop()
        invalidate()
    }

    // ----- Layout & drawing -----

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Square cells that fit the available space, grid centred horizontally.
        val margin = dp(4f)
        val availW = w - 2 * margin
        val availH = h - 2 * margin
        val cell = min(availW / Slot.COLS, availH / Slot.ROWS)
        cellW = cell
        cellH = cell
        gridW = cell * Slot.COLS
        gridH = cell * Slot.ROWS
        originX = (w - gridW) / 2f
        originY = (h - gridH) / 2f
        bgPaint.shader = LinearGradient(
            0f, originY, 0f, originY + gridH,
            Color.parseColor("#4A1010"), Color.parseColor("#1E0606"),
            Shader.TileMode.CLAMP
        )
        coinTextPaint.textSize = cellH * 0.16f
        rebuildBitmaps()
    }

    private fun rebuildBitmaps() {
        if (cellW <= 0 || cellH <= 0) return
        bitmaps.values.forEach { it.recycle() }
        bitmaps.clear()
        for (s in Sym.entries) {
            val src = BitmapFactory.decodeResource(resources, s.res) ?: continue
            // Framed characters fill the whole cell; cut-out art sits a bit smaller.
            val fill = if (s.framed) 1.0f else 0.9f
            val tw = (cellW * fill).coerceAtLeast(1f)
            val th = (cellH * fill).coerceAtLeast(1f)
            val scale = min(tw / src.width, th / src.height)
            val bw = (src.width * scale).toInt().coerceAtLeast(1)
            val bh = (src.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(src, bw, bh, true)
            bitmaps[s] = scaled
            if (src != scaled) src.recycle()
        }
    }

    private val cellRect = RectF()
    private val dst = RectF()

    override fun onDraw(canvas: Canvas) {
        if (cellW <= 0) return
        // Reel window background — exactly around the grid
        val left = originX
        val top = originY
        val right = originX + gridW
        val bottom = originY + gridH
        val radius = dp(10f)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, bgPaint)

        // clip to window
        val save = canvas.save()
        canvas.clipRect(left, top, right, bottom)

        if (state == State.BONUS) drawBonus(canvas, left, top)
        else drawReels(canvas, left, top)

        canvas.restoreToCount(save)

        canvas.drawRoundRect(left, top, right, bottom, radius, radius, slotStroke)
    }

    private fun drawReels(canvas: Canvas, left: Float, top: Float) {
        val pulse = (Math.sin(pulsePhase.toDouble()) * 0.5 + 0.5).toFloat()
        for (c in 0 until Slot.COLS) {
            val reel = reels[c]
            val x = left + c * cellW
            for (i in 0..Slot.ROWS) {
                val sym = reel.cells[i] ?: continue
                val y = top + (i - 1) * cellH + reel.offset
                if (y + cellH < top || y > originY + gridH) continue
                drawSymbol(canvas, sym, x, y)
            }
        }
        // win highlights (only when idle)
        if (winCells.isNotEmpty() && state == State.IDLE) {
            for (c in 0 until Slot.COLS) for (r in 0 until Slot.ROWS) {
                if (key(c, r) in winCells) {
                    val x = left + c * cellW
                    val y = top + r * cellH
                    cellRect.set(x + dp(2f), y + dp(2f), x + cellW - dp(2f), y + cellH - dp(2f))
                    winPaint.color = Color.argb((70 + pulse * 90).toInt(), 255, 216, 120)
                    canvas.drawRoundRect(cellRect, dp(8f), dp(8f), winPaint)
                    winStroke.alpha = (140 + pulse * 115).toInt()
                    canvas.drawRoundRect(cellRect, dp(8f), dp(8f), winStroke)
                }
            }
        }
    }

    private fun drawSymbol(canvas: Canvas, sym: Sym, cellX: Float, cellY: Float) {
        val bmp = bitmaps[sym] ?: return
        val cx = cellX + cellW / 2f
        val cy = cellY + cellH / 2f
        dst.set(cx - bmp.width / 2f, cy - bmp.height / 2f, cx + bmp.width / 2f, cy + bmp.height / 2f)
        canvas.drawBitmap(bmp, null, dst, symPaint)
    }

    private fun drawBonus(canvas: Canvas, left: Float, top: Float) {
        canvas.drawRect(left, top, originX + gridW, originY + gridH, dimPaint)
        val coinBmp = bitmaps[Sym.COIN]
        for (c in 0 until Slot.COLS) for (r in 0 until Slot.ROWS) {
            val x = left + c * cellW
            val y = top + r * cellH
            cellRect.set(x + dp(4f), y + dp(4f), x + cellW - dp(4f), y + cellH - dp(4f))
            val coin = bonusGrid[c][r]
            if (coin == null) {
                slotBgPaint.color = Color.parseColor("#33140A04")
                canvas.drawRoundRect(cellRect, dp(8f), dp(8f), slotBgPaint)
                slotStroke.alpha = 120
                canvas.drawRoundRect(cellRect, dp(8f), dp(8f), slotStroke)
                slotStroke.alpha = 255
            } else if (coinBmp != null) {
                val s = coin.pop
                val cx = x + cellW / 2f
                val cy = y + cellH / 2f
                val hw = coinBmp.width / 2f * s
                val hh = coinBmp.height / 2f * s
                dst.set(cx - hw, cy - hh, cx + hw, cy + hh)
                canvas.drawBitmap(coinBmp, null, dst, symPaint)
                if (s >= 1f) {
                    // Fit the value inside the golden coin face and centre it.
                    val faceW = coinBmp.width * 0.62f
                    coinTextPaint.textSize = cellH * 0.17f
                    val w = coinTextPaint.measureText(coin.text)
                    if (w > faceW) coinTextPaint.textSize *= faceW / w
                    val fm = coinTextPaint.fontMetrics
                    val baseY = cy - (fm.ascent + fm.descent) / 2f
                    canvas.drawText(coin.text, cx, baseY, coinTextPaint)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLoop()
        handler.removeCallbacksAndMessages(null)
    }

    private fun key(c: Int, r: Int): Long = c.toLong() * 100 + r

    companion object {
        const val JP_MINI = 5_000L
        const val JP_MINOR = 20_000L
        const val JP_MAJOR = 100_000L
        const val JP_GRAND = 1_000_000L

        fun formatCredits(v: Long): String {
            return if (v >= 1000) String.format("%,d", v) else v.toString()
        }
    }
}
