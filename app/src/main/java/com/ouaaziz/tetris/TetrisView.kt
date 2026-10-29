package com.ouaaziz.tetris

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import java.util.EnumMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws the whole game on a Canvas and turns touches into game actions.
 *
 * Touch input: on-screen buttons at the bottom, plus gestures anywhere above them
 * (drag to move, tap to rotate, flick down to hard drop, flick up to hold).
 */
@SuppressLint("ViewConstructor")
class TetrisView(context: Context) : View(context), TetrisGame.Listener, Choreographer.FrameCallback {

    private enum class Control { HOLD, SOFT_DROP, LEFT, RIGHT, HARD_DROP, ROTATE_CCW, ROTATE_CW, PAUSE }

    private val game = TetrisGame()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var highScore = prefs.getLong(KEY_HIGH_SCORE, 0L)
    private var newRecord = false
    private val dp = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    // Layout, computed in layoutUi().
    private val safeInsets = Rect()
    private var cell = 0f
    private val boardRect = RectF()
    private val holdBox = RectF()
    private val nextBox = RectF()
    private val statsArea = RectF()
    private val gestureArea = RectF()
    private val controls = EnumMap<Control, RectF>(Control::class.java)

    // Drawing helpers.
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val background = Paint()
    private val tmp = RectF()
    private val path = Path()
    private val pieceColors = Tetromino.entries.map { it.color }.toIntArray()

    // Input state.
    private val pressedControls = HashMap<Int, Control>()
    private var repeatControl: Control? = null
    private var repeatElapsed = 0f
    private var nextRepeatAt = 0f
    private var gesturePointer = NO_POINTER
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureAnchorX = 0f
    private var gestureAnchorY = 0f
    private var gestureStartTime = 0L
    private var gestureMoved = false
    private var velocityTracker: VelocityTracker? = null

    // Frame loop and transient effects.
    private var running = false
    private var lastFrameNanos = 0L
    private var gameOverAt = 0L
    private var popupTitle: String? = null
    private var popupSub: String? = null
    private var popupMs = 0f

    init {
        game.listener = this
        isFocusable = true
        isFocusableInTouchMode = true
        Control.entries.forEach { controls[it] = RectF() }
    }

    fun resume() {
        if (!running) {
            running = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
        requestFocus()
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        releaseAllInput()
        game.pause()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrameNanos == 0L) 0f else ((frameTimeNanos - lastFrameNanos) / 1_000_000f).coerceIn(0f, 100f)
        lastFrameNanos = frameTimeNanos
        tickAutoRepeat(dt)
        game.update(dt)
        if (popupMs > 0f) popupMs -= dt
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    // region Game events

    override fun onLinesCleared(count: Int, points: Int) {
        showPopup(LINE_NAMES[count], "+$points")
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    override fun onLevelUp(level: Int) {
        popupSub = "Level $level"
    }

    override fun onGameOver() {
        releaseAllInput()
        gameOverAt = SystemClock.uptimeMillis()
        newRecord = game.score > highScore
        if (newRecord) {
            highScore = game.score
            prefs.edit().putLong(KEY_HIGH_SCORE, highScore).apply()
        }
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun showPopup(title: String, sub: String?) {
        popupTitle = title
        popupSub = sub
        popupMs = POPUP_MS
    }

    // endregion

    // region Layout

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            safeInsets.set(i.left, i.top, i.right, i.bottom)
        } else {
            @Suppress("DEPRECATION")
            safeInsets.set(
                insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom,
            )
        }
        layoutUi()
        return insets
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        background.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP)
        layoutUi()
    }

    private fun layoutUi() {
        if (width == 0 || height == 0) return
        val pad = 12 * dp
        val gap = 8 * dp
        val left = safeInsets.left + pad
        val right = width - safeInsets.right - pad
        val top = safeInsets.top + pad
        val bottom = height - safeInsets.bottom - pad
        val availW = right - left
        val availH = bottom - top

        // Two rows of four buttons at the bottom.
        val btnW = (availW - 3 * gap) / 4
        val btnH = min(btnW * 0.8f, (availH * 0.26f - gap) / 2)
        val controlsTop = bottom - (2 * btnH + gap)

        val cellByWidth = (availW - 2 * gap) / (TetrisGame.COLS + 2 * PANEL_CELLS)
        val cellByHeight = (controlsTop - gap - top) / TetrisGame.ROWS
        cell = floor(min(cellByWidth, cellByHeight))

        val boardW = cell * TetrisGame.COLS
        val boardH = cell * TetrisGame.ROWS
        val panelW = cell * PANEL_CELLS
        val totalW = boardW + 2 * (panelW + gap)
        val boardLeft = left + (availW - totalW) / 2 + panelW + gap
        // Bias leftover height above the board so it sits closer to the thumbs.
        val boardTop = top + (controlsTop - gap - top - boardH) * 0.7f
        boardRect.set(boardLeft, boardTop, boardLeft + boardW, boardTop + boardH)

        val labelH = cell * 0.9f
        val leftPanel = boardRect.left - gap - panelW
        val rightPanel = boardRect.right + gap
        holdBox.set(leftPanel, boardTop + labelH, leftPanel + panelW, boardTop + labelH + cell * 3f)
        statsArea.set(leftPanel, holdBox.bottom + cell * 0.6f, leftPanel + panelW, boardRect.bottom)
        nextBox.set(
            rightPanel, boardTop + labelH,
            rightPanel + panelW, boardTop + labelH + cell * (0.4f + NEXT_SLOT_CELLS * TetrisGame.PREVIEW),
        )
        val pauseSize = min(panelW * 0.6f, 56 * dp)
        controls.getValue(Control.PAUSE).set(
            rightPanel + (panelW - pauseSize) / 2, boardRect.bottom - pauseSize,
            rightPanel + (panelW + pauseSize) / 2, boardRect.bottom,
        )

        gestureArea.set(0f, 0f, width.toFloat(), controlsTop - gap / 2)

        // Left thumb: hold, soft drop, left, right. Right thumb: hard drop, rotate CCW, rotate CW (wide).
        fun place(c: Control, col: Int, row: Int, span: Int = 1) {
            val x = left + col * (btnW + gap)
            val y = controlsTop + row * (btnH + gap)
            controls.getValue(c).set(x, y, x + btnW * span + gap * (span - 1), y + btnH)
        }
        place(Control.HOLD, 0, 0)
        place(Control.SOFT_DROP, 1, 0)
        place(Control.LEFT, 0, 1)
        place(Control.RIGHT, 1, 1)
        place(Control.HARD_DROP, 2, 0)
        place(Control.ROTATE_CCW, 3, 0)
        place(Control.ROTATE_CW, 2, 1, span = 2)
    }

    // endregion

    // region Input

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> pointerDown(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                val i = if (gesturePointer == NO_POINTER) -1 else e.findPointerIndex(gesturePointer)
                if (i >= 0) gestureMove(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointerUp(e, e.actionIndex)
            MotionEvent.ACTION_CANCEL -> releaseAllInput()
        }
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            tracker.recycle()
            velocityTracker = null
        }
        return true
    }

    private fun pointerDown(e: MotionEvent, index: Int) {
        val id = e.getPointerId(index)
        val x = e.getX(index)
        val y = e.getY(index)
        when (game.state) {
            TetrisGame.State.READY -> startGame()
            TetrisGame.State.PAUSED -> game.resume()
            TetrisGame.State.GAME_OVER ->
                if (SystemClock.uptimeMillis() - gameOverAt > RESTART_GUARD_MS) startGame()
            TetrisGame.State.PLAYING -> {
                val control = controlAt(x, y)
                if (control != null) {
                    pressedControls[id] = control
                    press(control)
                } else if (gesturePointer == NO_POINTER && gestureArea.contains(x, y)) {
                    gesturePointer = id
                    gestureStartX = x
                    gestureStartY = y
                    gestureAnchorX = x
                    gestureAnchorY = y
                    gestureStartTime = SystemClock.uptimeMillis()
                    gestureMoved = false
                }
            }
        }
    }

    private fun pointerUp(e: MotionEvent, index: Int) {
        val id = e.getPointerId(index)
        pressedControls.remove(id)?.let { release(it) }
        if (id == gesturePointer) {
            gestureEnd(id, e.getX(index), e.getY(index))
            gesturePointer = NO_POINTER
        }
    }

    private fun gestureMove(x: Float, y: Float) {
        if (abs(x - gestureStartX) > touchSlop || abs(y - gestureStartY) > touchSlop) gestureMoved = true
        while (x - gestureAnchorX >= cell) {
            game.move(1)
            gestureAnchorX += cell
        }
        while (gestureAnchorX - x >= cell) {
            game.move(-1)
            gestureAnchorX -= cell
        }
        while (y - gestureAnchorY >= cell) {
            game.softStep()
            gestureAnchorY += cell
        }
        if (y < gestureAnchorY) gestureAnchorY = y
    }

    private fun gestureEnd(id: Int, x: Float, y: Float) {
        if (game.state != TetrisGame.State.PLAYING) return
        val tracker = velocityTracker ?: return
        tracker.computeCurrentVelocity(1000)
        val vx = tracker.getXVelocity(id)
        val vy = tracker.getYVelocity(id)
        val fling = FLING_DP_PER_SEC * dp
        val vertical = abs(vy) > abs(vx) * 1.5f
        when {
            vertical && vy > fling -> game.hardDrop()
            vertical && vy < -fling && gestureStartY - y > cell -> game.hold()
            !gestureMoved && SystemClock.uptimeMillis() - gestureStartTime < TAP_MS ->
                if (holdBox.contains(x, y)) game.hold() else game.rotate(1)
        }
    }

    private fun controlAt(x: Float, y: Float): Control? {
        val slack = 4 * dp
        return controls.entries.firstOrNull { (_, r) ->
            x >= r.left - slack && x <= r.right + slack && y >= r.top - slack && y <= r.bottom + slack
        }?.key
    }

    private fun press(control: Control) {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        when (control) {
            Control.HOLD -> game.hold()
            Control.ROTATE_CCW -> game.rotate(-1)
            Control.ROTATE_CW -> game.rotate(1)
            Control.HARD_DROP -> game.hardDrop()
            Control.PAUSE -> {
                releaseAllInput()
                game.pause()
            }
            Control.SOFT_DROP -> game.softDropping = true
            Control.LEFT, Control.RIGHT -> {
                game.move(if (control == Control.LEFT) -1 else 1)
                repeatControl = control
                repeatElapsed = 0f
                nextRepeatAt = DAS_MS
            }
        }
    }

    private fun release(control: Control) {
        if (repeatControl == control) repeatControl = pressedControls.values.lastOrNull { it == Control.LEFT || it == Control.RIGHT }
        if (control == Control.SOFT_DROP && Control.SOFT_DROP !in pressedControls.values) game.softDropping = false
    }

    private fun tickAutoRepeat(dt: Float) {
        val control = repeatControl ?: return
        repeatElapsed += dt
        while (repeatElapsed >= nextRepeatAt) {
            game.move(if (control == Control.LEFT) -1 else 1)
            nextRepeatAt += ARR_MS
        }
    }

    private fun releaseAllInput() {
        pressedControls.clear()
        repeatControl = null
        gesturePointer = NO_POINTER
        game.softDropping = false
    }

    private fun startGame() {
        newRecord = false
        popupMs = 0f
        releaseAllInput()
        game.start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (game.state != TetrisGame.State.PLAYING) {
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_SPACE) {
                if (game.state == TetrisGame.State.PAUSED) game.resume() else startGame()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> game.move(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> game.move(1)
            KeyEvent.KEYCODE_DPAD_DOWN -> game.softStep()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_X -> game.rotate(1)
            KeyEvent.KEYCODE_Z -> game.rotate(-1)
            KeyEvent.KEYCODE_SPACE -> game.hardDrop()
            KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_SHIFT_LEFT -> game.hold()
            KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_ESCAPE -> game.pause()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    // endregion

    // region Drawing

    override fun onDraw(canvas: Canvas) {
        if (cell <= 0f) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
        drawBoard(canvas)
        drawHoldAndStats(canvas)
        drawNext(canvas)
        Control.entries.forEach { drawControl(canvas, it) }
        drawPopup(canvas)
        drawOverlay(canvas)
    }

    private fun drawBoard(canvas: Canvas) {
        fill.color = BOARD_BG
        canvas.drawRoundRect(boardRect, 6 * dp, 6 * dp, fill)
        stroke.color = GRID
        stroke.strokeWidth = 1f
        for (c in 1 until TetrisGame.COLS) {
            val x = boardRect.left + c * cell
            canvas.drawLine(x, boardRect.top, x, boardRect.bottom, stroke)
        }
        for (r in 1 until TetrisGame.ROWS) {
            val y = boardRect.top + r * cell
            canvas.drawLine(boardRect.left, y, boardRect.right, y, stroke)
        }

        for (r in 0 until TetrisGame.ROWS) {
            for (c in 0 until TetrisGame.COLS) {
                val v = game.board[r][c]
                if (v != 0) drawBlock(canvas, boardRect.left + c * cell, boardRect.top + r * cell, cell, pieceColors[v - 1])
            }
        }

        val piece = game.current ?: return
        if (game.state == TetrisGame.State.PLAYING || game.state == TetrisGame.State.PAUSED) {
            val ghostY = game.ghostY()
            for (c in piece.type.rotations[piece.rotation]) {
                val gy = ghostY + c.y
                if (gy >= 0) drawGhost(canvas, boardRect.left + (piece.x + c.x) * cell, boardRect.top + gy * cell, piece.type.color)
            }
        }
        canvas.save()
        canvas.clipRect(boardRect)
        for (c in piece.cells()) {
            drawBlock(canvas, boardRect.left + c.x * cell, boardRect.top + c.y * cell, cell, piece.type.color)
        }
        canvas.restore()
    }

    private fun drawBlock(canvas: Canvas, left: Float, top: Float, size: Float, color: Int) {
        val inset = maxOf(1f, size * 0.05f)
        val radius = size * 0.14f
        tmp.set(left + inset, top + inset, left + size - inset, top + size - inset)
        fill.color = shade(color, 0.62f)
        canvas.drawRoundRect(tmp, radius, radius, fill)
        val bevel = size * 0.13f
        tmp.set(tmp.left + bevel * 0.6f, tmp.top + bevel * 0.6f, tmp.right - bevel, tmp.bottom - bevel)
        fill.color = color
        canvas.drawRoundRect(tmp, radius * 0.7f, radius * 0.7f, fill)
        fill.color = 0x40FFFFFF
        canvas.drawRect(tmp.left + bevel, tmp.top + bevel * 0.4f, tmp.left + bevel * 2.2f, tmp.top + bevel * 1.6f, fill)
    }

    private fun drawGhost(canvas: Canvas, left: Float, top: Float, color: Int) {
        val inset = maxOf(1.5f, cell * 0.08f)
        tmp.set(left + inset, top + inset, left + cell - inset, top + cell - inset)
        fill.color = (color and 0x00FFFFFF) or 0x22000000
        canvas.drawRoundRect(tmp, cell * 0.12f, cell * 0.12f, fill)
        stroke.color = (color and 0x00FFFFFF) or (0x99 shl 24)
        stroke.strokeWidth = maxOf(1.5f, cell * 0.06f)
        canvas.drawRoundRect(tmp, cell * 0.12f, cell * 0.12f, stroke)
    }

    private fun drawPanelBox(canvas: Canvas, rect: RectF, label: String) {
        text.typeface = bold
        text.textSize = cell * 0.45f
        text.color = LABEL
        text.letterSpacing = 0.12f
        canvas.drawText(label, rect.centerX(), rect.top - cell * 0.28f, text)
        text.letterSpacing = 0f
        fill.color = PANEL_BG
        canvas.drawRoundRect(rect, 8 * dp, 8 * dp, fill)
        stroke.color = PANEL_EDGE
        stroke.strokeWidth = 1.5f * dp
        canvas.drawRoundRect(rect, 8 * dp, 8 * dp, stroke)
    }

    private fun drawMiniPiece(canvas: Canvas, type: Tetromino, cx: Float, cy: Float, size: Float, color: Int) {
        val cells = type.rotations[0]
        val minX = cells.minOf { it.x }
        val maxX = cells.maxOf { it.x }
        val minY = cells.minOf { it.y }
        val maxY = cells.maxOf { it.y }
        val ox = cx - (maxX - minX + 1) * size / 2 - minX * size
        val oy = cy - (maxY - minY + 1) * size / 2 - minY * size
        for (c in cells) drawBlock(canvas, ox + c.x * size, oy + c.y * size, size, color)
    }

    private fun drawHoldAndStats(canvas: Canvas) {
        drawPanelBox(canvas, holdBox, "HOLD")
        game.held?.let {
            val color = if (game.canHold) it.color else DISABLED
            drawMiniPiece(canvas, it, holdBox.centerX(), holdBox.centerY(), cell * 0.55f, color)
        }

        val stats = listOf(
            "SCORE" to game.score.toString(),
            "BEST" to maxOf(highScore, game.score).toString(),
            "LEVEL" to game.level.toString(),
            "LINES" to game.lines.toString(),
        )
        var y = statsArea.top + cell * 0.6f
        for ((label, value) in stats) {
            text.typeface = bold
            text.color = LABEL
            text.textSize = cell * 0.42f
            text.letterSpacing = 0.12f
            canvas.drawText(label, statsArea.centerX(), y, text)
            text.letterSpacing = 0f
            text.color = VALUE
            drawFittedText(canvas, value, statsArea.centerX(), y + cell * 0.95f, cell * 0.8f, statsArea.width() * 0.92f)
            y += cell * 1.9f
        }
    }

    private fun drawNext(canvas: Canvas) {
        drawPanelBox(canvas, nextBox, "NEXT")
        game.nextPieces.forEachIndexed { i, type ->
            val cy = nextBox.top + cell * (0.2f + NEXT_SLOT_CELLS * (i + 0.5f))
            drawMiniPiece(canvas, type, nextBox.centerX(), cy, cell * (if (i == 0) 0.55f else 0.45f), type.color)
        }
    }

    private fun drawControl(canvas: Canvas, control: Control) {
        val r = controls.getValue(control)
        val pressed = control in pressedControls.values
        val radius = min(r.width(), r.height()) * 0.22f
        fill.color = when {
            pressed -> BUTTON_PRESSED
            control == Control.ROTATE_CW || control == Control.HARD_DROP -> BUTTON_ACCENT
            else -> BUTTON
        }
        canvas.drawRoundRect(r, radius, radius, fill)
        stroke.color = BUTTON_EDGE
        stroke.strokeWidth = 1.5f * dp
        canvas.drawRoundRect(r, radius, radius, stroke)

        val cx = r.centerX()
        val cy = r.centerY()
        val s = min(r.width(), r.height()) * 0.32f
        fill.color = ICON
        path.reset()
        when (control) {
            Control.LEFT -> triangle(cx - s * 0.6f, cy, cx + s * 0.5f, cy - s * 0.7f, cx + s * 0.5f, cy + s * 0.7f)
            Control.RIGHT -> triangle(cx + s * 0.6f, cy, cx - s * 0.5f, cy - s * 0.7f, cx - s * 0.5f, cy + s * 0.7f)
            Control.SOFT_DROP -> triangle(cx, cy + s * 0.6f, cx - s * 0.7f, cy - s * 0.5f, cx + s * 0.7f, cy - s * 0.5f)
            Control.HARD_DROP -> {
                triangle(cx, cy + s * 0.3f, cx - s * 0.7f, cy - s * 0.7f, cx + s * 0.7f, cy - s * 0.7f)
                path.addRect(cx - s * 0.75f, cy + s * 0.5f, cx + s * 0.75f, cy + s * 0.72f, Path.Direction.CW)
            }
            Control.PAUSE -> {
                path.addRect(cx - s * 0.5f, cy - s * 0.6f, cx - s * 0.15f, cy + s * 0.6f, Path.Direction.CW)
                path.addRect(cx + s * 0.15f, cy - s * 0.6f, cx + s * 0.5f, cy + s * 0.6f, Path.Direction.CW)
            }
            Control.ROTATE_CW, Control.ROTATE_CCW -> drawRotateIcon(canvas, cx, cy, s, control == Control.ROTATE_CW)
            Control.HOLD -> {
                text.typeface = bold
                text.color = ICON
                text.textSize = s * 0.62f
                text.letterSpacing = 0.1f
                canvas.drawText("HOLD", cx, cy + text.textSize * 0.35f, text)
                text.letterSpacing = 0f
            }
        }
        canvas.drawPath(path, fill)
    }

    private fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.close()
    }

    private fun drawRotateIcon(canvas: Canvas, cx: Float, cy: Float, s: Float, clockwise: Boolean) {
        canvas.save()
        if (!clockwise) canvas.scale(-1f, 1f, cx, cy)
        val r = s * 0.7f
        tmp.set(cx - r, cy - r, cx + r, cy + r)
        stroke.color = ICON
        stroke.strokeWidth = s * 0.2f
        stroke.strokeCap = Paint.Cap.ROUND
        val start = 150f
        val sweep = 250f
        canvas.drawArc(tmp, start, sweep, false, stroke)
        stroke.strokeCap = Paint.Cap.BUTT

        val end = Math.toRadians((start + sweep).toDouble())
        val nx = cos(end).toFloat()
        val ny = sin(end).toFloat()
        val px = cx + r * nx
        val py = cy + r * ny
        val h = s * 0.42f
        val arrow = Path().apply {
            moveTo(px - ny * h, py + nx * h)
            lineTo(px + nx * h * 0.75f, py + ny * h * 0.75f)
            lineTo(px - nx * h * 0.75f, py - ny * h * 0.75f)
            close()
        }
        canvas.drawPath(arrow, fill)
        canvas.restore()
    }

    private fun drawFittedText(canvas: Canvas, value: String, x: Float, y: Float, size: Float, maxWidth: Float) {
        text.textSize = size
        val w = text.measureText(value)
        if (w > maxWidth) text.textSize = size * maxWidth / w
        canvas.drawText(value, x, y, text)
    }

    private fun drawPopup(canvas: Canvas) {
        val title = popupTitle ?: return
        if (popupMs <= 0f || game.state != TetrisGame.State.PLAYING) return
        val alpha = ((popupMs / 300f).coerceAtMost(1f) * 255).toInt()
        val rise = (1f - popupMs / POPUP_MS) * cell
        val y = boardRect.top + boardRect.height() * 0.3f - rise
        text.typeface = bold
        text.color = (alpha shl 24) or (POPUP and 0x00FFFFFF)
        drawFittedText(canvas, title, boardRect.centerX(), y, cell * 1.2f, boardRect.width() * 0.9f)
        popupSub?.let {
            text.color = (alpha shl 24) or (VALUE and 0x00FFFFFF)
            text.textSize = cell * 0.65f
            canvas.drawText(it, boardRect.centerX(), y + cell * 1.1f, text)
        }
    }

    private fun drawOverlay(canvas: Canvas) {
        val lines: List<Pair<String, Float>> = when (game.state) {
            TetrisGame.State.PLAYING -> return
            TetrisGame.State.READY -> listOf(
                "TETRIS" to 1.6f, "" to 0.6f, "Tap to start" to 0.65f, "" to 0.8f,
                "Drag to move" to 0.45f, "Tap to rotate" to 0.45f,
                "Flick down to drop" to 0.45f, "Flick up to hold" to 0.45f,
            )
            TetrisGame.State.PAUSED -> listOf("Paused" to 1.2f, "" to 0.5f, "Tap to resume" to 0.65f)
            TetrisGame.State.GAME_OVER -> listOfNotNull(
                "Game over" to 1.2f, "" to 0.4f, "Score ${game.score}" to 0.7f,
                if (newRecord) "New best!" to 0.6f else null,
                "" to 0.6f, "Tap to play again" to 0.6f,
            )
        }
        fill.color = OVERLAY
        canvas.drawRoundRect(boardRect, 6 * dp, 6 * dp, fill)

        val total = lines.sumOf { (it.second * cell * 1.35f).toDouble() }.toFloat()
        var y = boardRect.centerY() - total / 2
        lines.forEachIndexed { i, (line, scale) ->
            y += scale * cell * 1.35f
            if (line.isEmpty()) return@forEachIndexed
            text.typeface = if (i == 0 || line == "New best!") bold else Typeface.DEFAULT
            text.color = when {
                i == 0 -> VALUE
                line == "New best!" -> POPUP
                scale < 0.5f -> LABEL
                else -> VALUE
            }
            drawFittedText(canvas, line, boardRect.centerX(), y, scale * cell, boardRect.width() * 0.9f)
        }
    }

    private fun shade(color: Int, factor: Float): Int {
        val r = ((color shr 16 and 0xFF) * factor).toInt()
        val g = ((color shr 8 and 0xFF) * factor).toInt()
        val b = ((color and 0xFF) * factor).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // endregion

    private companion object {
        const val PREFS = "tetris"
        const val KEY_HIGH_SCORE = "high_score"
        const val NO_POINTER = -1
        const val PANEL_CELLS = 3.6f
        const val NEXT_SLOT_CELLS = 2.3f
        const val DAS_MS = 170f
        const val ARR_MS = 50f
        const val TAP_MS = 250L
        const val FLING_DP_PER_SEC = 1200f
        const val RESTART_GUARD_MS = 700L
        const val POPUP_MS = 1100f
        val LINE_NAMES = arrayOf("", "Single", "Double", "Triple", "TETRIS!")

        const val BG_TOP = 0xFF0B0D14.toInt()
        const val BG_BOTTOM = 0xFF161B2B.toInt()
        const val BOARD_BG = 0xFF10131C.toInt()
        const val GRID = 0xFF1B2030.toInt()
        const val PANEL_BG = 0xFF141927.toInt()
        const val PANEL_EDGE = 0xFF262D40.toInt()
        const val LABEL = 0xFF8B93A7.toInt()
        const val VALUE = 0xFFF1F5F9.toInt()
        const val DISABLED = 0xFF4B5263.toInt()
        const val BUTTON = 0xFF1A2032.toInt()
        const val BUTTON_ACCENT = 0xFF26244A.toInt()
        const val BUTTON_PRESSED = 0xFF3A4566.toInt()
        const val BUTTON_EDGE = 0xFF2C3550.toInt()
        const val ICON = 0xFFE5E7EB.toInt()
        const val POPUP = 0xFFFACC15.toInt()
        const val OVERLAY = 0xD90B0D14.toInt()
    }
}
