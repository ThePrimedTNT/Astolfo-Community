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

class SnakeGame(gameHandler: GameHandler, member: Member, channel: TextChannel) : Game(gameHandler, member, channel) {

    companion object {
        const val MAP_SIZE = 10
        const val UPDATE_SPEED = 2L
        private val random = Random()
    }

    private var appleLocation = randomPoint()
    private val snake = mutableListOf<Point>()
    private var snakeDirection = SnakeDirection.UP

    private lateinit var updateJob: Job
    private var currentMessage: Message? = null

    private val listener = object : ListenerAdapter() {
        override fun onGuildMessageReactionAdd(event: GuildMessageReactionAddEvent?) {
            if (currentMessage?.idLong == event!!.messageIdLong && event.user.idLong != event.jda.selfUser.idLong) {
                event.reaction.removeReaction(event.user).queue()
            } else return

            if (event.channel.idLong != channel.idLong || event.user.idLong != member.user.idLong) return

            snakeDirection = when (event.reactionEmote.name) {
                "\uD83D\uDD3C" -> SnakeDirection.UP
                "\uD83D\uDD3D" -> SnakeDirection.DOWN
                "\u2B05" -> SnakeDirection.LEFT
                "\u27A1" -> SnakeDirection.RIGHT
                else -> snakeDirection
            }

        }
    }

    enum class SnakeDirection {
        UP, DOWN, LEFT, RIGHT
    }

    override fun start() {
        var startLocation = randomPoint()
        while (startLocation == appleLocation) startLocation = randomPoint()
        snake.add(startLocation)

        channel.jda.addEventListener(listener)

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

            if (snake.map { c1 -> snake.filter { c1 == it }.count() }.any { it > 1 }) {
                currentMessage!!.editMessage(embed { render("Oof you ran into yourself!") }).queue()
                endGame()
                return
            }
            if (snake.any { it.x < 0 || it.x >= MAP_SIZE || it.y < 0 || it.y >= MAP_SIZE }) {
                snake.removeAt(0)
                currentMessage!!.editMessage(embed { render("Oof your snake went outside its cage!") }).queue()
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
        }

        val messageContent = embed { render() }

        if (currentMessage == null) {
            currentMessage = channel.sendMessage(messageContent).complete()
            currentMessage!!.addReaction("\uD83D\uDD3C").complete()
            currentMessage!!.addReaction("\uD83D\uDD3D").complete()
            currentMessage!!.addReaction("⬅").complete()
            currentMessage!!.addReaction("➡").complete()
        } else {
            currentMessage!!.editMessage(messageContent).complete()
        }
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
        channel.jda.removeEventListener(listener)
        currentMessage?.clearReactions()?.queue()
    }

}