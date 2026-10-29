package com.ouaaziz.tetris

import kotlin.math.pow
import kotlin.random.Random

data class Cell(val x: Int, val y: Int)

private fun cells(vararg xy: Int) = xy.toList().chunked(2) { Cell(it[0], it[1]) }

/**
 * The seven tetrominoes in their SRS spawn orientation, laid out in a [size]x[size] box
 * (y grows downward). Rotating the box clockwise yields the other SRS states.
 */
enum class Tetromino(val color: Int, val size: Int, spawn: List<Cell>) {
    I(0xFF22D3EE.toInt(), 4, cells(0, 1, 1, 1, 2, 1, 3, 1)),
    J(0xFF3B82F6.toInt(), 3, cells(0, 0, 0, 1, 1, 1, 2, 1)),
    L(0xFFF97316.toInt(), 3, cells(2, 0, 0, 1, 1, 1, 2, 1)),
    O(0xFFFACC15.toInt(), 2, cells(0, 0, 1, 0, 0, 1, 1, 1)),
    S(0xFF22C55E.toInt(), 3, cells(1, 0, 2, 0, 0, 1, 1, 1)),
    T(0xFFA855F7.toInt(), 3, cells(1, 0, 0, 1, 1, 1, 2, 1)),
    Z(0xFFEF4444.toInt(), 3, cells(0, 0, 1, 0, 1, 1, 2, 1));

    val rotations: List<List<Cell>> =
        generateSequence(spawn) { cur -> cur.map { Cell(size - 1 - it.y, it.x) } }.take(4).toList()
}

class ActivePiece(val type: Tetromino, var x: Int, var y: Int, var rotation: Int) {
    fun cells(): List<Cell> = type.rotations[rotation].map { Cell(x + it.x, y + it.y) }
}

/** Pure game rules: board, SRS rotation, 7-bag, gravity, lock delay and scoring. Times are in ms. */
class TetrisGame(private val random: Random = Random.Default) {

    enum class State { READY, PLAYING, PAUSED, GAME_OVER }

    interface Listener {
        fun onLinesCleared(count: Int, points: Int) {}
        fun onLevelUp(level: Int) {}
        fun onGameOver() {}
    }

    var listener: Listener? = null

    /** board[row][col]; 0 = empty, otherwise Tetromino.ordinal + 1. */
    val board = Array(ROWS) { IntArray(COLS) }
    var state = State.READY
        private set
    var current: ActivePiece? = null
        private set
    var held: Tetromino? = null
        private set
    var canHold = true
        private set
    var score = 0L
        private set
    var lines = 0
        private set
    var level = 1
        private set

    /** Set while the soft-drop control is held down. */
    var softDropping = false

    private val queue = ArrayDeque<Tetromino>()
    val nextPieces: List<Tetromino> get() = queue.take(PREVIEW)

    private var gravityAccum = 0f
    private var lockTimer = 0f
    private var lockResets = 0
    private var lowestY = 0

    fun start() {
        board.forEach { it.fill(0) }
        queue.clear()
        held = null
        canHold = true
        score = 0
        lines = 0
        level = 1
        softDropping = false
        state = State.PLAYING
        spawn(nextFromQueue())
    }

    fun pause() {
        if (state == State.PLAYING) state = State.PAUSED
    }

    fun resume() {
        if (state == State.PAUSED) state = State.PLAYING
    }

    fun update(dtMs: Float) {
        if (state != State.PLAYING) return
        val p = current ?: return

        val gravity = gravityIntervalMs()
        val interval = if (softDropping) minOf(gravity, maxOf(gravity / 20f, 16f)) else gravity
        gravityAccum += dtMs
        while (gravityAccum >= interval) {
            gravityAccum -= interval
            if (fits(p.type, p.x, p.y + 1, p.rotation)) {
                p.y++
                if (softDropping) score += 1
                trackLowest(p)
            } else {
                gravityAccum = 0f
                break
            }
        }

        if (!fits(p.type, p.x, p.y + 1, p.rotation)) {
            lockTimer += dtMs
            if (lockTimer >= LOCK_DELAY_MS) lockPiece()
        } else {
            lockTimer = 0f
        }
    }

    fun move(dx: Int): Boolean {
        val p = activePiece() ?: return false
        if (!fits(p.type, p.x + dx, p.y, p.rotation)) return false
        p.x += dx
        onManipulated(p)
        return true
    }

    /** Moves the piece down one row on player request (soft drop step). */
    fun softStep(): Boolean {
        val p = activePiece() ?: return false
        if (!fits(p.type, p.x, p.y + 1, p.rotation)) return false
        p.y++
        score += 1
        gravityAccum = 0f
        trackLowest(p)
        return true
    }

    /** [direction] is +1 for clockwise, -1 for counter-clockwise. */
    fun rotate(direction: Int): Boolean {
        val p = activePiece() ?: return false
        if (p.type == Tetromino.O) return false
        val to = (p.rotation + direction).mod(4)
        val table = if (p.type == Tetromino.I) I_KICKS else JLSTZ_KICKS
        val kicks = table.getValue(p.rotation * 4 + to)
        for (i in kicks.indices step 2) {
            // Kick tables are written y-up, the board is y-down.
            val kx = kicks[i]
            val ky = -kicks[i + 1]
            if (fits(p.type, p.x + kx, p.y + ky, to)) {
                p.x += kx
                p.y += ky
                p.rotation = to
                onManipulated(p)
                return true
            }
        }
        return false
    }

    fun hardDrop() {
        val p = activePiece() ?: return
        val target = ghostY()
        score += 2L * (target - p.y)
        p.y = target
        lockPiece()
    }

    fun hold(): Boolean {
        val p = activePiece() ?: return false
        if (!canHold) return false
        val swap = held
        held = p.type
        canHold = false
        spawn(swap ?: nextFromQueue())
        return true
    }

    /** Row the current piece would land on if hard-dropped. */
    fun ghostY(): Int {
        val p = current ?: return 0
        var y = p.y
        while (fits(p.type, p.x, y + 1, p.rotation)) y++
        return y
    }

    fun gravityIntervalMs(): Float =
        ((0.8 - (level - 1) * 0.007).pow(level - 1) * 1000).toFloat().coerceAtLeast(1f)

    fun fits(type: Tetromino, x: Int, y: Int, rotation: Int): Boolean {
        for (c in type.rotations[rotation]) {
            val bx = x + c.x
            val by = y + c.y
            if (bx !in 0 until COLS || by >= ROWS) return false
            if (by >= 0 && board[by][bx] != 0) return false
        }
        return true
    }

    internal fun spawn(type: Tetromino) {
        val p = ActivePiece(type, (COLS - type.size) / 2, if (type == Tetromino.I) -1 else 0, 0)
        gravityAccum = 0f
        lockTimer = 0f
        lockResets = 0
        lowestY = p.y
        if (!fits(p.type, p.x, p.y, p.rotation)) {
            current = null
            gameOver()
            return
        }
        current = p
    }

    private fun activePiece(): ActivePiece? = if (state == State.PLAYING) current else null

    private fun nextFromQueue(): Tetromino {
        while (queue.size <= PREVIEW) queue.addAll(Tetromino.entries.shuffled(random))
        return queue.removeFirst()
    }

    private fun trackLowest(p: ActivePiece) {
        if (p.y > lowestY) {
            lowestY = p.y
            lockResets = 0
        }
    }

    private fun onManipulated(p: ActivePiece) {
        trackLowest(p)
        if (lockTimer > 0f && lockResets < MAX_LOCK_RESETS) {
            lockTimer = 0f
            lockResets++
        }
    }

    private fun lockPiece() {
        val p = current ?: return
        var toppedOut = false
        for (c in p.cells()) {
            if (c.y < 0) toppedOut = true else board[c.y][c.x] = p.type.ordinal + 1
        }
        current = null
        if (toppedOut) {
            gameOver()
            return
        }

        val full = (0 until ROWS).filter { r -> board[r].all { it != 0 } }
        if (full.isNotEmpty()) {
            val remaining = board.filterIndexed { r, _ -> r !in full }.map { it.copyOf() }
            for (r in 0 until ROWS) {
                board[r] = if (r < full.size) IntArray(COLS) else remaining[r - full.size]
            }
            val points = LINE_SCORES[full.size] * level
            score += points
            lines += full.size
            val newLevel = 1 + lines / 10
            listener?.onLinesCleared(full.size, points)
            if (newLevel > level) {
                level = newLevel
                listener?.onLevelUp(level)
            }
        }

        canHold = true
        spawn(nextFromQueue())
    }

    private fun gameOver() {
        state = State.GAME_OVER
        softDropping = false
        listener?.onGameOver()
    }

    companion object {
        const val COLS = 10
        const val ROWS = 20
        const val PREVIEW = 3
        private const val LOCK_DELAY_MS = 500f
        private const val MAX_LOCK_RESETS = 15
        private val LINE_SCORES = intArrayOf(0, 100, 300, 500, 800)

        // SRS wall kicks keyed by from * 4 + to, as flattened (x, y) pairs with y pointing up.
        private val JLSTZ_KICKS = mapOf(
            0 * 4 + 1 to intArrayOf(0, 0, -1, 0, -1, 1, 0, -2, -1, -2),
            1 * 4 + 0 to intArrayOf(0, 0, 1, 0, 1, -1, 0, 2, 1, 2),
            1 * 4 + 2 to intArrayOf(0, 0, 1, 0, 1, -1, 0, 2, 1, 2),
            2 * 4 + 1 to intArrayOf(0, 0, -1, 0, -1, 1, 0, -2, -1, -2),
            2 * 4 + 3 to intArrayOf(0, 0, 1, 0, 1, 1, 0, -2, 1, -2),
            3 * 4 + 2 to intArrayOf(0, 0, -1, 0, -1, -1, 0, 2, -1, 2),
            3 * 4 + 0 to intArrayOf(0, 0, -1, 0, -1, -1, 0, 2, -1, 2),
            0 * 4 + 3 to intArrayOf(0, 0, 1, 0, 1, 1, 0, -2, 1, -2),
        )
        private val I_KICKS = mapOf(
            0 * 4 + 1 to intArrayOf(0, 0, -2, 0, 1, 0, -2, -1, 1, 2),
            1 * 4 + 0 to intArrayOf(0, 0, 2, 0, -1, 0, 2, 1, -1, -2),
            1 * 4 + 2 to intArrayOf(0, 0, -1, 0, 2, 0, -1, 2, 2, -1),
            2 * 4 + 1 to intArrayOf(0, 0, 1, 0, -2, 0, 1, -2, -2, 1),
            2 * 4 + 3 to intArrayOf(0, 0, 2, 0, -1, 0, 2, 1, -1, -2),
            3 * 4 + 2 to intArrayOf(0, 0, -2, 0, 1, 0, -2, -1, 1, 2),
            3 * 4 + 0 to intArrayOf(0, 0, 1, 0, -2, 0, 1, -2, -2, 1),
            0 * 4 + 3 to intArrayOf(0, 0, -1, 0, 2, 0, -1, 2, 2, -1),
        )
    }
}
