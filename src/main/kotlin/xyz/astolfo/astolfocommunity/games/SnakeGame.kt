package xyz.astolfo.astolfocommunity.games

import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
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

class SnakeGame(gameHandler: GameHandler, member: Member, channel: TextChannel) : ReactionGame(gameHandler, member, channel, listOf(LEFT_EMOTE, UP_EMOTE, DOWN_EMOTE, RIGHT_EMOTE)) {

    companion object {
        const val MAP_SIZE = 10
        const val UPDATE_SPEED = 2L
        private val random = Random()

        const val UP_EMOTE = "\uD83D\uDD3C"
        const val DOWN_EMOTE = "\uD83D\uDD3D"
        const val LEFT_EMOTE = "\u2B05"
        const val RIGHT_EMOTE = "\u27A1"
    }

    private var appleLocation = randomPoint()
    private val snake = mutableListOf<Point>()
    private var snakeDirection = SnakeDirection.UP

    private lateinit var updateJob: Job

    override fun onGenericMessageReaction(event: GenericMessageReactionEvent) {
        snakeDirection = when (event.reactionEmote.name) {
            UP_EMOTE -> SnakeDirection.UP
            DOWN_EMOTE -> SnakeDirection.DOWN
            LEFT_EMOTE -> SnakeDirection.LEFT
            RIGHT_EMOTE -> SnakeDirection.RIGHT
            else -> snakeDirection
        }
    }

    enum class SnakeDirection {
        UP, DOWN, LEFT, RIGHT
    }

    override fun start() {
        super.start()

        var startLocation = randomPoint()
        while (startLocation == appleLocation) startLocation = randomPoint()
        snake.add(startLocation)

        updateJob = launch {
            while (isActive) {
                update()
                delay(UPDATE_SPEED, TimeUnit.SECONDS)
            }
        }
    }

    private fun randomPoint() = Point(random.nextInt(MAP_SIZE), random.nextInt(MAP_SIZE))

    private fun update() {
        if (currentMessage != null) {
            val frontSnake = snake.first()

            val newPoint = when (snakeDirection) {
                SnakeDirection.UP -> Point(frontSnake.x, frontSnake.y - 1)
                SnakeDirection.DOWN -> Point(frontSnake.x, frontSnake.y + 1)
                SnakeDirection.LEFT -> Point(frontSnake.x - 1, frontSnake.y)
                SnakeDirection.RIGHT -> Point(frontSnake.x + 1, frontSnake.y)
            }

            snake.add(0, newPoint)

            if (snake.any { it.x < 0 || it.x >= MAP_SIZE || it.y < 0 || it.y >= MAP_SIZE }) {
                snake.removeAt(0)
                setContent(embed { render("Oof your snake went outside its cage!") })
                endGame()
                return
            }

            if (appleLocation == newPoint) {
                var startLocation = randomPoint()
                while (startLocation == appleLocation || snake.contains(startLocation)) startLocation = randomPoint()
                appleLocation = startLocation
            } else {
                snake.removeAt(snake.size - 1)
            }

            if (snake.map { c1 -> snake.filter { c1 == it }.count() }.any { it > 1 }) {
                setContent(embed { render("Oof you ran into yourself!") })
                endGame()
                return
            }
        }

        setContent(embed { render() })
    }

    private fun EmbedBuilder.render(deadReason: String? = null) {
        val dead = deadReason != null
        title("${member.effectiveName}'s Snake Game - Score: ${snake.size}" + if (dead) " - Dead" else "")
        description((0 until MAP_SIZE).joinToString(separator = "\n") { y ->
            (0 until MAP_SIZE).joinToString(separator = "") { x ->
                val point = Point(x, y)
                if (point == appleLocation) {
                    "\uD83C\uDF4E"
                } else if (snake.contains(point)) {
                    val index = snake.indexOf(point)
                    if (index == 0) {
                        if (dead) {
                            "\uD83D\uDCA2"
                        } else {
                            "\uD83D\uDD34"
                        }
                    } else {
                        "\uD83D\uDD35"
                    }
                } else {
                    "\u2B1B"
                }
            }
        } + if (dead) "\n**You have died!**\n$deadReason" else "")
    }

    override fun destroy() {
        updateJob.cancel()
        super.destroy()
    }

}