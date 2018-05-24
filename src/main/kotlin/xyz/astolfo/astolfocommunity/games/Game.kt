package xyz.astolfo.astolfocommunity.games

import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.events.message.MessageDeleteEvent
import net.dv8tion.jda.core.events.message.react.GenericMessageReactionEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import java.util.concurrent.ConcurrentHashMap

class GameHandler {

    private val gameSessionMap = ConcurrentHashMap<GameSessionKey, Game>()

    fun getGame(channelId: Long, userId: Long) = gameSessionMap[GameSessionKey(channelId, userId)]

    fun endGame(channelId: Long, userId: Long) {
        gameSessionMap.remove(GameSessionKey(channelId, userId))?.destroy()
    }

    fun startGame(channelId: Long, userId: Long, game: Game) {
        gameSessionMap[GameSessionKey(channelId, userId)] = game
        game.start()
    }

    data class GameSessionKey(val channelId: Long, val userId: Long)

}

abstract class Game(val gameHandler: GameHandler, val member: Member, val channel: TextChannel) {

    abstract fun start()

    abstract fun destroy()

    fun endGame() = gameHandler.endGame(channel.idLong, member.user.idLong)

}

abstract class ReactionGame(gameHandler: GameHandler, member: Member, channel: TextChannel, private val reactions: List<String>) : Game(gameHandler, member, channel) {

    protected var currentMessage: Message? = null

    private val listener = object : ListenerAdapter() {
        override fun onGenericMessageReaction(event: GenericMessageReactionEvent?) {
            if (event!!.user.idLong == event.jda.selfUser.idLong) return

            if (currentMessage?.idLong != event.messageIdLong || event.user.isBot) return

            if (event.user.idLong != member.user.idLong) {
                event.reaction.removeReaction(event.user).queue()
                return
            }

            this@ReactionGame.onGenericMessageReaction(event)
        }

        override fun onMessageDelete(event: MessageDeleteEvent?) {
            if (currentMessage?.idLong == event!!.messageIdLong) endGame()
        }
    }

    protected fun setContent(messageEmbed: MessageEmbed) {
        if (currentMessage == null) {
            currentMessage = channel.sendMessage(messageEmbed).complete()
            reactions.forEach { currentMessage!!.addReaction(it).complete() }
        } else {
            currentMessage!!.editMessage(messageEmbed).complete()
        }
    }

    abstract fun onGenericMessageReaction(event: GenericMessageReactionEvent)

    override fun start() {
        channel.jda.addEventListener(listener)
    }

    override fun destroy() {
        channel.jda.removeEventListener(listener)
        currentMessage?.clearReactions()?.queue()
    }

}