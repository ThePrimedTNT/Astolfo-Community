package xyz.astolfo.astolfocommunity.commands

import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.channels.sendBlocking
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.RateLimiter
import java.util.concurrent.TimeUnit

class MessageListener(val application: AstolfoCommunityApplication) {

    companion object {
        internal val messageProcessorContext = newFixedThreadPoolContext(50, "Message Processor")
    }

    val listener = object : ListenerAdapter() {
        override fun onGuildMessageReceived(event: GuildMessageReceivedEvent) {
            application.statsDClient.incrementCounter("messages_received")
            val timeIssued = System.nanoTime()
            if (event.author.isBot || !event.channel.canTalk()) return
            messageActor.sendBlocking(GuildMessageReceived(MessageData(event, timeIssued)))
        }
    }

    init {
        launch(messageProcessorContext) {
            while (isActive) {
                // Clean up every 5 minutes
                delay(5, TimeUnit.MINUTES)
                messageActor.send(CleanUp)
            }
        }
    }

    internal val commandRateLimiter = RateLimiter<Long>(4, 6)
    internal val chatBotManager = ChatBotManager(application.properties)

    open class MessageData(val messageReceivedEvent: GuildMessageReceivedEvent, val timeIssued: Long)

    private interface MessageEvent
    private class GuildMessageReceived(val messageData: MessageData) : MessageEvent
    private object CleanUp : MessageEvent

    private val messageActor = actor<MessageEvent>(context = messageProcessorContext, capacity = Channel.UNLIMITED) {
        for (event in channel) {
            handleEvent(event)
        }
    }

    private val guildListeners = mutableMapOf<Long, CacheEntry>()

    private suspend fun handleEvent(event: MessageEvent) {
        when (event) {
            is CleanUp -> {
                val currentTime = System.currentTimeMillis()
                val expiredListeners = mutableListOf<Long>()
                for ((key, value) in guildListeners) {
                    val deltaTime = currentTime - value.lastUsed
                    // Remove if its older then 10 minutes
                    if (deltaTime > TimeUnit.MINUTES.toSeconds(10)) {
                        value.listener.dispose()
                        println("REMOVE GUILDLISTENER: $key")
                        expiredListeners.add(key)
                    }
                }
                expiredListeners.forEach { guildListeners.remove(it) }
            }
            is GuildMessageReceived -> {
                val messageData = event.messageData
                val guildId = messageData.messageReceivedEvent.guild.idLong
                val entry = guildListeners.computeIfAbsent(guildId) {
                    // Create if it doesn't exist
                    println("CREATE GUILDLISTENER: $guildId")
                    CacheEntry(GuildListener(application, this, messageData.messageReceivedEvent.guild), System.currentTimeMillis())
                }
                entry.lastUsed = System.currentTimeMillis()
                entry.listener.addMessage(messageData)
            }
        }
    }

    private class CacheEntry(val listener: GuildListener, var lastUsed: Long)

}