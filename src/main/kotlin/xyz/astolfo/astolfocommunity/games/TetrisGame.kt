package xyz.astolfo.astolfocommunity.games

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.events.message.react.GenericMessageReactionEvent
import xyz.astolfo.astolfocommunity.messages.description
import xyz.astolfo.astolfocommunity.messages.embed
import xyz.astolfo.astolfocommunity.messages.title
import java.awt.Point
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class TetrisGame(member: Member, channel: TextChannel) : ReactionGame(
    member,
    channel,
    listOf(ROTATE_ANTICLOCKWISE_EMOTE, LEFT_EMOTE, QUICK_FALL_EMOTE, RIGHT_EMOTE, ROTATE_CLOCKWISE_EMOTE)
) {

    companion object {
        private const val MAP_WIDTH = 7
        private const val MAP_HEIGHT = 14
        private const val UPDATE_SPEED = 2L
        private val random = Random()

        private const val ROTATE_ANTICLOCKWISE_EMOTE = "↪"
        private const val ROTATE_CLOCKWISE_EMOTE = "↩"
        private const val LEFT_EMOTE = "⬅"
        private const val RIGHT_EMOTE = "➡"
        private const val QUICK_FALL_EMOTE = "⬇"

        private val tetrisContext = newFixedThreadPoolContext(30, "Tetris")

        private val pieces = listOf(
            mutableListOf(Point(0, 0), Point(1, 0), Point(2, 0), Point(3, 0)) to "\uD83D\uDCD8",
            mutableListOf(Point(0, 0), Point(1, 0), Point(2, 0), Point(2, 1)) to "\uD83D\uDCD4",
            mutableListOf(Point(0, 1), Point(0, 0), Point(1, 0), Point(2, 0)) to "\uD83D\uDCD9",
            mutableListOf(Point(0, 0), Point(0, 1), Point(1, 0), Point(1, 1)) to "\uD83D\uDCD2",
            mutableListOf(Point(0, 1), Point(1, 1), Point(1, 0), Point(2, 0)) to "\uD83D\uDCD7",
            mutableListOf(Point(0, 0), Point(1, 0), Point(1, 1), Point(2, 0)) to "\uD83D\uDCD3",
            mutableListOf(Point(0, 0), Point(1, 0), Point(1, 1), Point(2, 1)) to "\uD83D\uDCD5"
        )
    }

    private val stationaryBlocks = mutableListOf<Block>()
    private var score = 0

    private lateinit var fallingTetromino: Tetromino

    private lateinit var updateJob: Job

    private var gameEnded = false

    private interface TetrisEvent
    private object StartEvent : TetrisEvent
    private object DestroyEvent : TetrisEvent
    private object UpdateEvent : TetrisEvent
    private class PlaceEvent(val first: Boolean) : TetrisEvent
    private class MoveEvent(val moveType: String) : TetrisEvent

    private val tetrisActor = GlobalScope.actor<TetrisEvent>(context = tetrisContext, capacity = Channel.UNLIMITED) {
        for (event in this.channel) {
            if (destroyed) continue
            handleEvent(event)
        }

        handleEvent(DestroyEvent)
    }

    private suspend fun handleEvent(event: TetrisEvent) {
        when (event) {
            is StartEvent -> {
                handleEvent(PlaceEvent(true))
                updateJob = GlobalScope.launch {
                    while (isActive) {
                        tetrisActor.send(UpdateEvent)
                        delay(TimeUnit.SECONDS.toMillis(UPDATE_SPEED))
                    }
                }
            }
            is UpdateEvent -> {
                if (currentMessage != null) {
                    if (!fallingTetromino.canMove(0, 1)) {
                        handleEvent(PlaceEvent(false))
                    } else {
                        fallingTetromino.move(0, 1)
                    }
                    val rowsCleared = checkFullRows()

                    val amountOfTetris = rowsCleared / 4
                    val normalLines = rowsCleared % 4

                    score += normalLines * 100

                    if (amountOfTetris == 1) {
                        score += 800
                    } else if (amountOfTetris > 1) {
                        score += amountOfTetris * 1200
                    }
                }
                if (!gameEnded) setContent(embed { render(false) })
            }
            is MoveEvent -> {
                when (event.moveType) {
                    ROTATE_ANTICLOCKWISE_EMOTE -> fallingTetromino.rotate(false)
                    ROTATE_CLOCKWISE_EMOTE -> fallingTetromino.rotate(true)
                    LEFT_EMOTE -> fallingTetromino.move(-1, 0)
                    RIGHT_EMOTE -> fallingTetromino.move(1, 0)
                    QUICK_FALL_EMOTE -> while (fallingTetromino.move(0, 1));
                    else -> return
                }
                if (!gameEnded) setContent(embed { render(false) })
            }
            is PlaceEvent -> {
                // Check if valid
                if (!event.first) {
                    // if the tetromino isnt at bottom yet
                    if (fallingTetromino.canMove(0, 1)) return

                    stationaryBlocks.addAll(fallingTetromino.blocks)
                }

                val configurations = pieces.map { p ->
                    p to (0 until (MAP_WIDTH - p.first.map { it.x }.max()!!)).filter { x ->
                        p.first.all { pb ->
                            val pointToCheck = Point(x + pb.x, pb.y)
                            stationaryBlocks.none { it.location == pointToCheck }
                        }
                    }
                }.filter { it.second.isNotEmpty() }

                if (configurations.isEmpty()) {
                    setContent(embed { render(true) })
                    gameEnded = true
                    endGame()
                    return
                }

                val piece = configurations[random.nextInt(configurations.size)]
                val (tetrominoPoints, tetrominoEmote) = piece.first
                val validLocations = piece.second

                val spawnLocation = validLocations[random.nextInt(validLocations.size)]
                fallingTetromino =
                    Tetromino(tetrominoPoints.map { Block(Point(it.x + spawnLocation, it.y), tetrominoEmote) })
            }
            is DestroyEvent -> {
                updateJob.cancel()
            }
        }
    }

    override suspend fun onGenericMessageReaction(event: GenericMessageReactionEvent) {
        tetrisActor.send(MoveEvent(event.reactionEmote.name))
    }

    override suspend fun start() {
        super.start()
        tetrisActor.send(StartEvent)
    }

    private fun EmbedBuilder.render(dead: Boolean = false) {
        val quickFallTetromino = fallingTetromino.let {
            val newTetromino = Tetromino(it.blocks.map { Block(Point(it.location), "❌") })
            while (newTetromino.move(0, 1));
            newTetromino
        }
        title("${member.effectiveName}'s Tetris Game - Score: $score" + if (dead) " - Topped out!" else "")
        description((0 until MAP_HEIGHT).joinToString(separator = "\n") { y ->
            (0 until MAP_WIDTH).joinToString(separator = "") { x ->
                val point = Point(x, y)
                val block = stationaryBlocks.find { it.location == point }
                    ?: fallingTetromino.blocks.find { it.location == point }
                    ?: quickFallTetromino.blocks.find { it.location == point }
                block?.emote ?: "\u2B1B"
            }
        } + if (dead) "\n**You have topped out!**" else "")
    }

    private fun checkFullRows(): Int {
        var rowsCleared = 0
        for (y in 0 until MAP_HEIGHT) {
            val rowFull = (0 until MAP_WIDTH).all { x ->
                val point = Point(x, y)
                stationaryBlocks.any { it.location == point }
            }
            if (!rowFull) continue

            rowsCleared++
            // Remove row
            stationaryBlocks.removeIf { it.location.y == y }
            // Move all rows down
            stationaryBlocks.filter { it.location.y < y }.forEach {
                it.location.y++
            }
        }
        return rowsCleared
    }

    override suspend fun destroy() {
        super.destroy()
        tetrisActor.close()
    }

    class Block(val location: Point, val emote: String)

    inner class Tetromino(blocks: List<Block>) {

        var blocks = blocks
            private set

        fun move(xa: Int, ya: Int): Boolean {
            if (!canMove(xa, ya)) return false

            blocks.forEach {
                it.location.x += xa
                it.location.y += ya
            }
            return true
        }

        fun rotate(clockwise: Boolean): Boolean {
            val newBlocks = blocks.map { Block(Point(it.location), it.emote) }

            val rotateX = newBlocks.map { it.location.x }.average()
            val rotateY = newBlocks.map { it.location.y }.average()
            newBlocks.forEach {
                var x = it.location.x - rotateX
                var y = it.location.y - rotateY
                val x2 = x
                if (clockwise) {
                    x = -y
                    y = x2
                } else {
                    x = y
                    y = -x2
                }
                it.location.x = (x + rotateX).roundToInt()
                it.location.y = (y + rotateY).roundToInt()
            }
            while (newBlocks.any { it.location.x >= MAP_WIDTH }) newBlocks.forEach { it.location.x-- }
            while (newBlocks.any { it.location.x < 0 }) newBlocks.forEach { it.location.x++ }

            if (!canMove(newBlocks.map { it.location })) return false

            blocks = newBlocks
            return true
        }

        fun canMove(xa: Int, ya: Int): Boolean {
            for (xi in min(xa, 0)..max(xa, 0)) {
                for (yi in min(ya, 0)..max(ya, 0)) {
                    val canMove = canMove(blocks.map { Point(it.location.x + xi, it.location.y + yi) })
                    if (!canMove) return false
                }
            }
            return true
        }

        private fun canMove(points: List<Point>) = points.none { point ->
            if (point.x < 0 || point.x >= MAP_WIDTH ||/* point.y < 0 || */point.y >= MAP_HEIGHT) true
            else stationaryBlocks.any { it.location == point }
        }
    }

}