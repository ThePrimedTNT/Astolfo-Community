package xyz.astolfo.astolfocommunity.games

import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.events.message.guild.react.GuildMessageReactionAddEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import xyz.astolfo.astolfocommunity.description
import xyz.astolfo.astolfocommunity.embed
import xyz.astolfo.astolfocommunity.title
import java.awt.Point
import java.util.*
import java.util.concurrent.TimeUnit

class TetrisGame(gameHandler: GameHandler, member: Member, channel: TextChannel) : Game(gameHandler, member, channel) {

    companion object {
        const val MAP_WIDTH = 7
        const val MAP_HEIGHT = 14
        const val UPDATE_SPEED = 2L
        private val random = Random()
    }

    private val tetrominos = mutableListOf<Tetromino>()
    private var score = 0

    private var fallingTetromino: Tetromino? = null

    private lateinit var updateJob: Job
    private var currentMessage: Message? = null

    private val listener = object : ListenerAdapter() {
        override fun onGuildMessageReactionAdd(event: GuildMessageReactionAddEvent?) {
            if (currentMessage?.idLong == event!!.messageIdLong && event.user.idLong != event.jda.selfUser.idLong) {
                event.reaction.removeReaction(event.user).queue()
            } else return

            if (event.channel.idLong != channel.idLong || event.user.idLong != member.user.idLong) return

            when (event.reactionEmote.name) {
                "↪" -> rotate(false)
                "↩" -> rotate(true)
                "⬅" -> move(-1)
                "➡" -> move(1)
            }
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
        val rotateAroundWidth = blocks.map { it.x }.average().toInt()
        val rotateAroundHeight = blocks.map { it.y }.average().toInt()
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
            it.x = x + rotateAroundWidth
            it.y = y + rotateAroundHeight
        }
        while (blocks.map { it.x }.max() ?: 0 >= MAP_WIDTH) blocks.forEach { it.x-- }
        while (blocks.map { it.x }.min() ?: 0 < 0) blocks.forEach { it.x++ }

        val canMove = blocks.none { fallingBlock ->
            tetrominos.any { it != fallingTetromino && it.blocks.any { it == fallingBlock } }
        }

        if (canMove) fallingTetromino!!.blocks = blocks.toMutableList()
    }

    override fun start() {
        channel.jda.addEventListener(listener)

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
                currentMessage!!.editMessage(embed { render(true) }).queue()
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

        val messageContent = embed { render() }

        if (currentMessage == null) {
            currentMessage = channel.sendMessage(messageContent).complete()
            currentMessage!!.addReaction("↪").complete()
            currentMessage!!.addReaction("⬅").complete()
            currentMessage!!.addReaction("➡").complete()
            currentMessage!!.addReaction("↩").complete()
        } else {
            currentMessage!!.editMessage(messageContent).complete()
        }
    }

    private fun EmbedBuilder.render(dead: Boolean = false) {
        title("${member.effectiveName}'s Tetris Game - Score: $score" + if (dead) " - Topped out!" else "")
        description((0 until MAP_HEIGHT).joinToString(separator = "\n") { y ->
            (0 until MAP_WIDTH).joinToString(separator = "") { x ->
                val point = Point(x, y)
                val tetromino = tetrominos.find { it.blocks.any { it == point } }
                if (tetromino != null) tetromino.emote
                else "\u2B1B"
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

    private fun checkIncompleteTetromino() {
        tetrominos.toList().forEach { tetromino ->
            if (tetromino == fallingTetromino) return@forEach
            if (tetromino.blocks.isEmpty()) {
                tetrominos.remove(tetromino)
                return@forEach
            }
            if (tetromino.blocks.size == 1) return@forEach
            tetromino.blocks.toList().forEach { toCheck ->
                val shouldBreak = (-1..1).none { x ->
                    (-1..1).none { y ->
                        val atPoint = Point(toCheck.x + x, toCheck.y + y)
                        if ((x == 0 && y == 0) || (x == 1 && y == 1) || (x == -1 && y == 1) || (x == 1 && y == -1) || (x == -1 && y == -1)) false
                        else tetromino.blocks.any { it == atPoint }
                    }
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
            val canFall = tetromino.blocks.none { fallingBlock ->
                val newLocation = Point(fallingBlock.x, fallingBlock.y + 1)
                if (newLocation.y >= MAP_HEIGHT) true
                else tetrominos.any { it != tetromino && it.blocks.any { it == newLocation } }
            }
            if (canFall) {
                tetromino.blocks.forEach { it.y++ }
                return true
            } else if (tetromino == fallingTetromino) {
                fallingTetromino = null
            }
        }
        return false
    }

    override fun destroy() {
        updateJob.cancel()
        channel.jda.removeEventListener(listener)
        currentMessage?.clearReactions()?.queue()
    }

    class Tetromino(var blocks: MutableList<Point>, val emote: String)

}