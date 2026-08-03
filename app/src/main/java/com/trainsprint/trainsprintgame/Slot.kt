package com.trainsprint.trainsprintgame

import kotlin.random.Random

/**
 * Static slot configuration for the 5x3, 20-line "Train Sprint" machine.
 *
 * COIN is the Hold & Win money symbol (6 or more trigger the bonus).
 * WILD substitutes for every regular symbol on a pay line.
 *
 * [framed] marks the character symbols that already ship with their own square
 * ornamental frame — they fill the whole reel cell. Cards / WILD / COIN are cut
 * out on a transparent background and are drawn centred, a bit smaller.
 */
enum class Sym(
    val res: Int,
    val pay3: Int,
    val pay4: Int,
    val pay5: Int,
    val weight: Int,
    val framed: Boolean = false
) {
    // Low value — card royals (transparent art)
    J(R.drawable.sym_j, 5, 10, 25, 30),
    Q(R.drawable.sym_q, 5, 12, 30, 28),
    K(R.drawable.sym_k, 8, 15, 40, 26),
    A(R.drawable.sym_a, 10, 20, 50, 24),
    // High value — framed characters
    BOY(R.drawable.sym_boy, 15, 40, 100, 16, framed = true),
    WOMAN_LIGHT(R.drawable.sym_woman_light, 20, 50, 120, 14, framed = true),
    WOMAN_DARK(R.drawable.sym_woman_dark, 25, 60, 150, 12, framed = true),
    MAN(R.drawable.sym_man, 40, 100, 250, 10, framed = true),
    GRANDAD(R.drawable.sym_grandad, 60, 150, 400, 8, framed = true),
    // Special
    WILD(R.drawable.sym_wild, 100, 300, 1000, 4),
    COIN(R.drawable.sym_coin, 0, 0, 0, 6);

    fun payFor(count: Int): Int = when (count) {
        5 -> pay5
        4 -> pay4
        3 -> pay3
        else -> 0
    }

    companion object {
        val REGULAR = listOf(
            J, Q, K, A,
            BOY, WOMAN_LIGHT, WOMAN_DARK, MAN, GRANDAD
        )
    }
}

object Slot {
    const val COLS = 5
    const val ROWS = 4
    const val LINES = 20

    /** Each line lists the row index (0..3, top→bottom) for every column. */
    val PAYLINES: Array<IntArray> = arrayOf(
        intArrayOf(1, 1, 1, 1, 1),  // row 1 straight
        intArrayOf(2, 2, 2, 2, 2),  // row 2 straight
        intArrayOf(0, 0, 0, 0, 0),  // top straight
        intArrayOf(3, 3, 3, 3, 3),  // bottom straight
        intArrayOf(1, 2, 3, 2, 1),  // V down from row 1
        intArrayOf(2, 1, 0, 1, 2),  // V up from row 2
        intArrayOf(0, 1, 2, 3, 3),  // staircase down
        intArrayOf(3, 2, 1, 0, 0),  // staircase up
        intArrayOf(0, 1, 1, 1, 0),  // shallow dip from top
        intArrayOf(3, 2, 2, 2, 3),  // shallow rise from bottom
        intArrayOf(1, 0, 0, 0, 1),  // shallow V top-side
        intArrayOf(2, 3, 3, 3, 2),  // shallow V bottom-side
        intArrayOf(0, 1, 2, 1, 0),  // deep V down (rows 0→2)
        intArrayOf(3, 2, 1, 2, 3),  // inverted V up
        intArrayOf(1, 2, 1, 2, 1),  // zigzag rows 1–2
        intArrayOf(2, 1, 2, 1, 2),  // zigzag rows 2–1
        intArrayOf(0, 0, 1, 0, 0),  // slight dip at center
        intArrayOf(3, 3, 2, 3, 3),  // slight rise at center
        intArrayOf(1, 1, 2, 1, 1),  // slight dip row1→2
        intArrayOf(2, 2, 1, 2, 2)   // slight rise row2→1
    )

    // ---- Hold & Win coin values, expressed as a multiplier of the total bet ----
    const val COIN_TRIGGER = 6
    val COIN_MULTIPLIERS = intArrayOf(1, 1, 1, 2, 2, 3, 5, 10, 15, 25, 50)

    // Jackpot coin tiers (index into a special list)
    enum class Jackpot(val label: String) { MINI("MINI"), MINOR("MINOR"), MAJOR("MAJOR"), GRAND("GRAND") }

    /** COIN never appears on regular spins; it only exists inside Hold & Win. */
    private val weightedPool: List<Sym> = buildList {
        for (s in Sym.entries) if (s != Sym.COIN) repeat(s.weight) { add(s) }
    }

    fun randomSymbol(rng: Random): Sym = weightedPool[rng.nextInt(weightedPool.size)]

    /** Generates a fresh [COLS] x [ROWS] result grid: grid[col][row]. */
    fun randomGrid(rng: Random): Array<Array<Sym>> =
        Array(COLS) { Array(ROWS) { randomSymbol(rng) } }

    /** Chance that a spin triggers the Hold & Win bonus. */
    const val BONUS_CHANCE = 0.02

    /** Builds a result grid seeded with [coins] COIN symbols to launch Hold & Win. */
    fun triggerGrid(rng: Random, coins: Int): Array<Array<Sym>> {
        val grid = randomGrid(rng)
        val positions = ArrayList<Pair<Int, Int>>()
        for (c in 0 until COLS) for (r in 0 until ROWS) positions.add(c to r)
        positions.shuffle(rng)
        val n = coins.coerceIn(COIN_TRIGGER, COLS * ROWS)
        for (i in 0 until n) {
            val (c, r) = positions[i]
            grid[c][r] = Sym.COIN
        }
        return grid
    }

    fun countCoins(grid: Array<Array<Sym>>): Int {
        var c = 0
        for (col in grid) for (s in col) if (s == Sym.COIN) c++
        return c
    }

    data class LineWin(
        val lineIndex: Int,
        val symbol: Sym,
        val count: Int,
        val amount: Long,
        val cells: List<Pair<Int, Int>> // (col, row)
    )

    /**
     * Evaluates all pay lines. [bet] is the total bet; pay values are multiples
     * of the per-line bet (bet / LINES).
     */
    fun evaluate(grid: Array<Array<Sym>>, bet: Long): List<LineWin> {
        val perLine = bet.toDouble() / LINES
        val wins = ArrayList<LineWin>()
        for ((li, line) in PAYLINES.withIndex()) {
            // Determine the base symbol (skip leading wilds / specials).
            var base: Sym? = null
            for (col in 0 until COLS) {
                val s = grid[col][line[col]]
                if (s == Sym.WILD) continue
                if (s == Sym.COIN) { base = null; break }
                base = s; break
            }
            if (base == null || base == Sym.COIN) continue
            var count = 0
            val cells = ArrayList<Pair<Int, Int>>()
            for (col in 0 until COLS) {
                val s = grid[col][line[col]]
                if (s == base || s == Sym.WILD) {
                    count++
                    cells.add(col to line[col])
                } else break
            }
            if (count >= 3) {
                val mult = base.payFor(count)
                if (mult > 0) {
                    val amount = Math.round(mult * perLine)
                    if (amount > 0) wins.add(LineWin(li, base, count, amount, cells.take(count)))
                }
            }
        }
        return wins
    }
}
