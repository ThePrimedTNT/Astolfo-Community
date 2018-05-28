package xyz.astolfo.astolfocommunity.games

import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.events.message.react.GenericMessageReactionEvent
import xyz.astolfo.astolfocommunity.description
import xyz.astolfo.astolfocommunity.embed
import xyz.astolfo.astolfocommunity.title
import java.awt.Point
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class TetrisGame(gameHandler: GameHandler, member: Member, channel: TextChannel) : ReactionGame(gameHandler, member, channel, listOf(ROTATE_ANTICLOCKWISE_EMOTE, LEFT_EMOTE, QUICK_FALL_EMOTE, RIGHT_EMOTE, ROTATE_CLOCKWISE_EMOTE)) {

    companion object {
        const val MAP_WIDTH = 7
        const val MAP_HEIGHT = 14
        const val UPDATE_SPEED = 2L
        private val random = Random()

        const val ROTATE_ANTICLOCKWISE_EMOTE = "↪"
        const val ROTATE_CLOCKWISE_EMOTE = "↩"
        const val LEFT_EMOTE = "⬅"
        const val RIGHT_EMOTE = "➡"
        const val QUICK_FALL_EMOTE = "⬇"
    }

    private val tetrominos = mutableListOf<Tetromino>()
    private var score = 0

    private var fallingTetromino: Tetromino? = null

    private lateinit var updateJob: Job

    override fun onGenericMessageReaction(event: GenericMessageReactionEvent) {
        when (event.reactionEmote.name) {
            ROTATE_ANTICLOCKWISE_EMOTE -> rotate(false)
            ROTATE_CLOCKWISE_EMOTE -> rotate(true)
            LEFT_EMOTE -> move(-1)
            RIGHT_EMOTE -> move(1)
            QUICK_FALL_EMOTE -> while (fallingTetromino != null && checkGravity(fallingTetromino!!, false));
        }
    }

    private fun move(amount: Int) {
        val canMove = fallingTetromino!!.blocks.none { fallingBlock ->
            val newLocation = Point(fallingBlock.x + amount, fallingBlock.y)
            if (newLocation.x < 0 || newLocation.x == MAP_WIDTH) true
            else tetrominos.any { it != fallingTetromino && it.blocks.any { it == newLocation } }
        }
        if (canMove) fallingTetromino!!.blocks.forEach { it.x += amount }
    }

    private fun rotate(clockwise: Boolean) {
        val blocks = fallingTetromino?.blocks?.map { Point(it.x, it.y) } ?: return
        val rotateAroundWidth = blocks.map { it.x }.average()
        val rotateAroundHeight = blocks.map { it.y }.average()
        blocks.forEach {
            var x = it.x - rotateAroundWidth
            var y = it.y - rotateAroundHeight
            val x2 = x
            if (clockwise) {
                x = -y
                y = x2
            } else {
                x = y
                y = -x2
            }
            it.x = (x + rotateAroundWidth).roundToInt()
            it.y = (y + rotateAroundHeight).roundToInt()
        }
        while (blocks.map { it.x }.max() ?: 0 >= MAP_WIDTH) blocks.forEach { it.x-- }
        while (blocks.map { it.x }.min() ?: 0 < 0) blocks.forEach { it.x++ }

        val canMove = blocks.none { fallingBlock ->
            tetrominos.any { it != fallingTetromino && it.blocks.any { it == fallingBlock } }
        }

        if (canMove) fallingTetromino!!.blocks = blocks.toMutableList()
    }

    override fun start() {
        super.start()

        updateJob = launch {
            while (isActive) {
                update()
                delay(UPDATE_SPEED, TimeUnit.SECONDS)
            }
        }
    }

    private fun update() {
        if (currentMessage != null) {
            checkGravity(true)
            var rowsCleared = 0
            while (checkFullRows()) rowsCleared++

            val amountOfTetris = rowsCleared / 4
            val normalLines = rowsCleared % 4

            score += normalLines * 100

            if (amountOfTetris == 1) {
                score += 800
            } else if (amountOfTetris > 1) {
                score += amountOfTetris * 1200
            }

            checkIncompleteTetromino()
            while (checkGravity(false));
        }

        if (fallingTetromino == null) {
            val pieces = listOf(
                    mutableListOf(Point(0, 0), Point(1, 0), Point(2, 0), Point(3, 0)) to "\uD83D\uDCD8",
                    mutableListOf(Point(0, 0), Point(1, 0), Point(2, 0), Point(2, 1)) to "\uD83D\uDCD4",
                    mutableListOf(Point(0, 1), Point(0, 0), Point(1, 0), Point(2, 0)) to "\uD83D\uDCD9",
                    mutableListOf(Point(0, 0), Point(0, 1), Point(1, 0), Point(1, 1)) to "\uD83D\uDCD2",
                    mutableListOf(Point(0, 1), Point(1, 1), Point(1, 0), Point(2, 0)) to "\uD83D\uDCD7",
                    mutableListOf(Point(0, 0), Point(1, 0), Point(1, 1), Point(2, 0)) to "\uD83D\uDCD3",
                    mutableListOf(Point(0, 0), Point(1, 0), Point(1, 1), Point(2, 1)) to "\uD83D\uDCD5"
            ).map { p ->
                p to (0 until (MAP_WIDTH - p.first.map { it.x }.max()!!)).filter { x ->
                    p.first.all { pb ->
                        val pointToCheck = Point(x + pb.x, pb.y)
                        tetrominos.none { it.blocks.any { it == pointToCheck } }
                    }
                }
            }.filter { it.second.isNotEmpty() }

            if (pieces.isEmpty()) {
                setContent(embed { render(true) })
                endGame()
                return
            }

            val piece = pieces[random.nextInt(pieces.size)]
            val (tetrominoPoints, tetrominoEmote) = piece.first
            val validLocations = piece.second

            val spawnLocation = validLocations[random.nextInt(validLocations.size)]
            tetrominoPoints.forEach { it.x += spawnLocation }
            fallingTetromino = Tetromino(tetrominoPoints, tetrominoEmote)
            tetrominos.add(fallingTetromino!!)
        }

        setContent(embed { render() })
    }

    private fun EmbedBuilder.render(dead: Boolean = false) {
        val quickFallTetromino = fallingTetromino?.let {
            val newTetromino = Tetromino(it.blocks.map { Point(it.x, it.y) }.toMutableList(), "❌")
            while (checkGravity(newTetromino, true));
            newTetromino
        }
        title("${member.effectiveName}'s Tetris Game - Score: $score" + if (dead) " - Topped out!" else "")
        description((0 until MAP_HEIGHT).joinToString(separator = "\n") { y ->
            (0 until MAP_WIDTH).joinToString(separator = "") { x ->
                val point = Point(x, y)
                val tetromino = tetrominos.find { it.blocks.any { it == point } } ?: quickFallTetromino?.takeIf { it.blocks.any { it == point } }
                tetromino?.emote ?: "\u2B1B"
            }
        } + if (dead) "\n**You have topped out!**" else "")
    }

    private fun checkFullRows(): Boolean {
        (0 until MAP_HEIGHT).reversed().forEach { y ->
            val shouldRemove = (0 until MAP_WIDTH).all { x ->
                val point = Point(x, y)
                tetrominos.find { it != fallingTetromino && it.blocks.any { it == point } } != null
            }
            if (shouldRemove) {
                (0 until MAP_WIDTH).forEach { x ->
                    val point = Point(x, y)
                    tetrominos.forEach { it.blocks.remove(point) }
                }
                return true
            }
        }
        return false
    }

    private val checkLocations = listOf(Point(0, 1), Point(0, -1), Point(1, 0), Point(-1, 0))

    private fun checkIncompleteTetromino() {
        tetrominos.toList().forEach { tetromino ->
            if (tetromino == fallingTetromino) return@forEach
            if (tetromino.blocks.isEmpty()) {
                tetrominos.remove(tetromino)
                return@forEach
            }
            if (tetromino.blocks.size == 1) return@forEach
            tetromino.blocks.toList().forEach { toCheck ->
                val shouldBreak = checkLocations.none { relativePoint ->
                    val atPoint = Point(toCheck.x + relativePoint.x, toCheck.y + relativePoint.y)
                    tetromino.blocks.any { it == atPoint }
                }
                if (shouldBreak) {
                    tetrominos.add(Tetromino(mutableListOf(toCheck), tetromino.emote))
                    tetromino.blocks.remove(toCheck)
                }
            }
            if (tetromino.blocks.isEmpty()) tetrominos.remove(tetromino)
        }
    }

    private fun checkGravity(checkFalling: Boolean): Boolean {
        tetrominos.forEach { tetromino ->
            if (!checkFalling && tetromino == fallingTetromino) return@forEach
            if (checkGravity(tetromino, false)) return true
        }
        return false
    }

    private fun checkGravity(tetromino: Tetromino, ignoreFalling: Boolean): Boolean {
        val canFall = tetromino.blocks.none { fallingBlock ->
            val newLocation = Point(fallingBlock.x, fallingBlock.y + 1)
            if (newLocation.y >= MAP_HEIGHT) true
            else tetrominos.any {
                if (ignoreFalling && it == fallingTetromino) false
                else it != tetromino && it.blocks.any { it == newLocation }
            }
        }
        if (canFall) {
            tetromino.blocks.forEach { it.y++ }
            return true
        } else if (tetromino == fallingTetromino) {
            fallingTetromino = null
        }
        return false
    }

    override fun destroy() {
        updateJob.cancel()
        super.destroy()
    }

    class Tetromino(var blocks: MutableList<Point>, val emote: String)

}