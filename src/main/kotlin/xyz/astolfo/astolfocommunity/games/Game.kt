package xyz.astolfo.astolfocommunity.games

import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.TextChannel
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