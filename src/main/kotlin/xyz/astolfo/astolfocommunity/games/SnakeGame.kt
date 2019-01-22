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

class SnakeGame(member: Member, channel: TextChannel) :
    ReactionGame(member, channel, listOf(LEFT_EMOTE, UP_EMOTE, DOWN_EMOTE, RIGHT_EMOTE)) {

    companion object {
        private val snakeContext = newFixedThreadPoolContext(30, "Snake")

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

    override suspend fun onGenericMessageReaction(event: GenericMessageReactionEvent) {
        val newDirection = when (event.reactionEmote.name) {
            UP_EMOTE -> SnakeDirection.UP
            DOWN_EMOTE -> SnakeDirection.DOWN
            LEFT_EMOTE -> SnakeDirection.LEFT
            RIGHT_EMOTE -> SnakeDirection.RIGHT
            else -> return // Ignore new direction if not valid emote
        }
        snakeActor.send(DirectionEvent(newDirection))
    }

    enum class SnakeDirection {
        UP, DOWN, LEFT, RIGHT
    }

    private interface SnakeEvent
    private object StartEvent : SnakeEvent
    private object DestroyEvent : SnakeEvent
    private object UpdateEvent : SnakeEvent
    private class DirectionEvent(val newDirection: SnakeDirection) : SnakeEvent

    private val snakeActor = GlobalScope.actor<SnakeEvent>(context = snakeContext, capacity = Channel.UNLIMITED) {
        for (event in this.channel) {
            if (destroyed) continue
            handleEvent(event)
        }

        handleEvent(DestroyEvent)
    }

    private suspend fun handleEvent(event: SnakeEvent) {
        when (event) {
            is StartEvent -> {
                var startLocation = randomPoint()
                while (startLocation == appleLocation) startLocation = randomPoint()
                snake.add(startLocation)

                updateJob = GlobalScope.launch(snakeContext) {
                    while (isActive && running) {
                        snakeActor.send(UpdateEvent)
                        delay(TimeUnit.SECONDS.toMillis(UPDATE_SPEED))
                    }
                }
            }
            is DirectionEvent -> {
                snakeDirection = event.newDirection
            }
            is UpdateEvent -> {
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
                        while (startLocation == appleLocation || snake.contains(startLocation)) startLocation =
                            randomPoint()
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
            is DestroyEvent -> {
                updateJob.cancel()
            }
        }
    }

    override suspend fun start() {
        super.start()
        snakeActor.send(StartEvent)
    }

    private fun randomPoint() = Point(random.nextInt(MAP_SIZE), random.nextInt(MAP_SIZE))

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

    override suspend fun destroy() {
        super.destroy()
        snakeActor.close()
    }

}