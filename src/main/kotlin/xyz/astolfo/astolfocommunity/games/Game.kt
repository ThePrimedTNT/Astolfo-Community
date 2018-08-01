package xyz.astolfo.astolfocommunity.games

import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.channels.sendBlocking
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.events.message.MessageDeleteEvent
import net.dv8tion.jda.core.events.message.react.GenericMessageReactionEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import xyz.astolfo.astolfocommunity.hasPermission
import xyz.astolfo.astolfocommunity.messages.CachedMessage
import xyz.astolfo.astolfocommunity.messages.message
import xyz.astolfo.astolfocommunity.messages.sendCached
import xyz.astolfo.astolfocommunity.value
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object GameHandler {

    private val gameHandlerContext = newFixedThreadPoolContext(10, "Game Handler")
    private val gameSessionMap = ConcurrentHashMap<GameSessionKey, Game>()
    private val sessionMutex = Mutex()

    private interface GameEvent
    private class StartGame(val sessionKey: GameSessionKey, val game: Game) : GameEvent
    private class EndGame(val sessionKey: GameSessionKey) : GameEvent

    private val gameActor = actor<GameEvent>(context = gameHandlerContext, capacity = Channel.UNLIMITED) {
        for (event in channel) {
            sessionMutex.withLock {
                handleEvent(event)
            }
        }
    }

    private suspend fun handleEvent(event: GameEvent) {
        when (event) {
            is StartGame -> {
                // Incase somehow a game is still running
                handleEvent(EndGame(event.sessionKey))
                // Start the game
                gameSessionMap[event.sessionKey] = event.game
                event.game.start()
            }
            is EndGame -> {
                gameSessionMap.remove(event.sessionKey)?.destroy()
            }
        }
    }

    suspend fun get(channelId: Long, userId: Long) = sessionMutex.withLock {
        gameSessionMap[GameSessionKey(channelId, userId)]
    }

    suspend fun getAll(channelId: Long): List<Game> = sessionMutex.withLock {
        gameSessionMap.values.filter { it.channel.idLong == channelId }
    }

    suspend fun end(channelId: Long, userId: Long) = gameActor.send(EndGame(GameSessionKey(channelId, userId)))

    suspend fun start(channelId: Long, userId: Long, game: Game) = gameActor.send(StartGame(GameSessionKey(channelId, userId), game))

    data class GameSessionKey(val channelId: Long, val userId: Long)

}

abstract class Game(val member: Member, val channel: TextChannel) {

    var running = false
        private set
    var destroyed = false
        private set

    open suspend fun start() {
        running = true
    }

    open suspend fun destroy() {
        running = false
        destroyed = true
    }

    suspend fun endGame() = GameHandler.end(channel.idLong, member.user.idLong)

}

abstract class ReactionGame(member: Member, channel: TextChannel, private val reactions: List<String>) : Game(member, channel) {

    companion object {
        private val reactionGameContext = newFixedThreadPoolContext(30, "Reaction Game")
    }

    protected var currentMessage: CachedMessage? = null

    private interface ReactionGameEvent
    private object StartEvent : ReactionGameEvent
    private object DestroyEvent : ReactionGameEvent
    private class ReactionEvent(val event: GenericMessageReactionEvent) : ReactionGameEvent
    private class DeleteEvent(val event: MessageDeleteEvent) : ReactionGameEvent

    private val reactionGameActor = actor<ReactionGameEvent>(context = reactionGameContext, capacity = Channel.UNLIMITED) {
        for (event in this.channel) {
            if (destroyed) continue
            handleEvent(event)
        }

        handleEvent(DestroyEvent)
    }

    private class MessageEvent(val newContent: Message)

    // TODO this works perfectly, maybe make it a separate thing I can reuse elsewhere?
    private val messageActor = actor<MessageEvent>(context = reactionGameContext, capacity = Channel.UNLIMITED) {
        var contentDeferred = CompletableDeferred<Message?>()

        val contentMutex = Mutex()
        var stopJob = false
        launch(reactionGameContext) {
            while (isActive) {
                contentMutex.withLock {
                    // don't await if job is stopped and nothing is left to change in message
                    // (If coroutine was delayed when actor was cancelled)
                    if (stopJob && !contentDeferred.isCompleted) return@launch
                }
                val newContent = contentDeferred.await()
                contentMutex.withLock {
                    // cancel job if null content is received (If the completable was awaiting when actor was cancelled)
                    if (newContent == null) return@launch
                    contentDeferred = CompletableDeferred()
                }
                // Create or edit the message with new content
                if (currentMessage == null) {
                    currentMessage = channel.sendMessage(newContent).sendCached()
                    reactions.forEach { currentMessage!!.addReaction(it) }
                } else {
                    currentMessage!!.editMessage(newContent!!)
                }
                // Delay for 2 seconds so we don't spam discord
                delay(2, TimeUnit.SECONDS)
            }
        }

        for (event in this.channel) {
            contentMutex.withLock {
                // Create new if already completed
                if (contentDeferred.isCompleted) contentDeferred = CompletableDeferred()
                // Send the new content
                contentDeferred.complete(event.newContent)
            }
        }
        stopJob = true
        contentMutex.withLock {
            // Send a null if thread is awaiting
            if (!contentDeferred.isCompleted) contentDeferred.complete(null)
        }
    }

    private suspend fun handleEvent(event: ReactionGameEvent) {
        when (event) {
            is StartEvent -> {
                channel.jda.addEventListener(listener)
            }
            is DestroyEvent -> {
                channel.jda.removeEventListener(listener)
                currentMessage?.clearReactions()
            }
            is ReactionEvent -> {
                val reactionEvent = event.event
                if (currentMessage == null || reactionEvent.user.idLong == reactionEvent.jda.selfUser.idLong) return

                if (currentMessage!!.idLong.value != reactionEvent.messageIdLong || reactionEvent.user.isBot) return

                if (reactionEvent.user.idLong != member.user.idLong) {
                    if (reactionEvent.textChannel.hasPermission(Permission.MESSAGE_MANAGE)) reactionEvent.reaction.removeReaction(reactionEvent.user).queue()
                    return
                }

                onGenericMessageReaction(reactionEvent)
            }
            is DeleteEvent -> {
                if (currentMessage?.idLong?.value == event.event.messageIdLong) endGame()
            }
        }
    }

    private val listener = object : ListenerAdapter() {
        override fun onGenericMessageReaction(event: GenericMessageReactionEvent) {
            if (event.channel.idLong != channel.idLong) return

            reactionGameActor.sendBlocking(ReactionEvent(event))
        }

        override fun onMessageDelete(event: MessageDeleteEvent) {
            reactionGameActor.sendBlocking(DeleteEvent(event))
        }
    }

    protected suspend fun setContent(messageEmbed: MessageEmbed) = setContent(message { setEmbed(messageEmbed) })
    @Suppress("MemberVisibilityCanBePrivate")
    protected suspend fun setContent(message: Message) {
        messageActor.send(MessageEvent(message))
    }

    abstract suspend fun onGenericMessageReaction(event: GenericMessageReactionEvent)

    override suspend fun start() {
        super.start()
        reactionGameActor.send(StartEvent)
    }

    override suspend fun destroy() {
        super.destroy()
        reactionGameActor.close()
        messageActor.close()
    }

}