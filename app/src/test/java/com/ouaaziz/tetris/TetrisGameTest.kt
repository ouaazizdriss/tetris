package com.ouaaziz.tetris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

class TetrisGameTest {

    private lateinit var game: TetrisGame

    @Before
    fun setUp() {
        game = TetrisGame(Random(42))
        game.start()
    }

    @Test
    fun rotationsReturnToSpawnAfterFourTurns() {
        for (t in Tetromino.entries) {
            val fifth = t.rotations[3].map { Cell(t.size - 1 - it.y, it.x) }
            assertEquals(t.rotations[0].toSet(), fifth.toSet())
            t.rotations.forEach { assertEquals(4, it.size) }
        }
        // T piece pointing right after one clockwise turn.
        assertEquals(setOf(Cell(1, 0), Cell(1, 1), Cell(2, 1), Cell(1, 2)), Tetromino.T.rotations[1].toSet())
    }

    @Test
    fun firstSevenPiecesAreOneOfEach() {
        val seen = mutableSetOf<Tetromino>()
        repeat(7) {
            seen += game.current!!.type
            game.hardDrop()
            game.board.forEach { it.fill(0) }
        }
        assertEquals(Tetromino.entries.toSet(), seen)
    }

    @Test
    fun hardDropLandsOnFloorAndScores() {
        game.spawn(Tetromino.O)
        game.hardDrop()
        assertTrue(game.board[19][4] != 0 && game.board[19][5] != 0)
        assertTrue(game.board[18][4] != 0 && game.board[18][5] != 0)
        assertEquals(2L * 18, game.score)
    }

    @Test
    fun clearingFourLinesScoresTetris() {
        for (r in 16..19) for (c in 0 until TetrisGame.COLS) if (c != 9) game.board[r][c] = 1
        game.spawn(Tetromino.I)
        assertTrue(game.rotate(1)) // vertical, occupies column 5
        repeat(4) { game.move(1) }
        val dropStart = game.current!!.y
        game.hardDrop()
        assertEquals(4, game.lines)
        assertEquals(800L + 2L * (16 - (dropStart + 0)), game.score)
        assertTrue(game.board.all { row -> row.all { it == 0 } })
    }

    @Test
    fun wallKickLetsIPieceRotateAgainstWall() {
        game.spawn(Tetromino.I)
        game.rotate(1)
        repeat(5) { game.move(-1) }
        val p = game.current!!
        assertEquals(0, p.cells().minOf { it.x })
        assertTrue(game.rotate(1))
        assertTrue(game.current!!.cells().all { it.x in 0 until TetrisGame.COLS })
    }

    @Test
    fun holdIsAllowedOncePerPiece() {
        val first = game.current!!.type
        assertTrue(game.hold())
        assertEquals(first, game.held)
        assertFalse(game.hold())
        game.hardDrop()
        assertTrue(game.canHold)
    }

    @Test
    fun pieceLocksAfterGravityAndLockDelay() {
        game.spawn(Tetromino.O)
        repeat(400) { game.update(50f) }
        assertNotNull(game.current)
        assertTrue(game.board[19][4] != 0)
    }

    @Test
    fun stackingToTheTopEndsTheGame() {
        repeat(60) { if (game.state == TetrisGame.State.PLAYING) game.hardDrop() }
        assertEquals(TetrisGame.State.GAME_OVER, game.state)
    }
}
