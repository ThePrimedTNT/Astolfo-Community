package xyz.astolfo.astolfocommunity.commands

import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import java.util.concurrent.TimeUnit

class GuildListener(
        val application: AstolfoCommunityApplication,
        val messageListener: MessageListener,
        val guild: Guild
) {

    private val cleanUpJob = launch(MessageListener.messageProcessorContext) {
        while (isActive && !destroyed) {
            // Clean up every 5 minutes
            delay(5, TimeUnit.MINUTES)
            messageActor.send(CleanUp)
        }
    }

    private var destroyed = false

    suspend fun addMessage(messageData: MessageListener.MessageData) = messageActor.send(GuildMessage(messageData))

    private interface GuildMessageEvent
    private class GuildMessage(val messageData: MessageListener.MessageData) : GuildMessageEvent
    private object CleanUp : GuildMessageEvent

    class GuildMessageData(
            val prefixMatched: String,
            messageReceivedEvent: GuildMessageReceivedEvent,
            timeIssued: Long
    ) : MessageListener.MessageData(messageReceivedEvent, timeIssued)

    private val messageActor = actor<GuildMessageEvent>(context = MessageListener.messageProcessorContext, capacity = Channel.UNLIMITED) {
        for (event in channel) {
            if (destroyed) continue
            handleEvent(event)
        }
        channelListeners.forEach { it.value.listener.dispose() }
    }

    private val channelListeners = mutableMapOf<Long, CacheEntry>()

    private suspend fun handleEvent(event: GuildMessageEvent) {
        when (event) {
            is CleanUp -> {
                val currentTime = System.currentTimeMillis()
                val expiredListeners = mutableListOf<Long>()
                for ((key, value) in channelListeners) {
                    val deltaTime = currentTime - value.lastUsed
                    // Remove if its older then 10 minutes
                    if (deltaTime > TimeUnit.MINUTES.toSeconds(10)) {
                        value.listener.dispose()
                       // println("REMOVE CHANNELLISTENER: ${guild.idLong}/$key")
                        expiredListeners.add(key)
                    }
                }
                expiredListeners.forEach { channelListeners.remove(it) }
            }
            is GuildMessage -> {
                val messageData = event.messageData
                val botId = event.messageData.messageReceivedEvent.jda.selfUser.idLong
                val prefix = application.astolfoRepositories.getEffectiveGuildSettings(guild.idLong).getEffectiveGuildPrefix(application)
                val channel = messageData.messageReceivedEvent.channel!!

                val rawMessage = messageData.messageReceivedEvent.message.contentRaw!!
                val validPrefixes = listOf(prefix, "<@$botId>", "<@!$botId>")

                val matchedPrefix = validPrefixes.find { rawMessage.startsWith(it, true) }

                val guildMessageData = GuildMessageData(matchedPrefix ?: "",
                        messageData.messageReceivedEvent, messageData.timeIssued)

                // This only is true when a user says a normal message
                if (matchedPrefix == null) {
                    val channelEntry = channelListeners[channel.idLong]
                            ?: return // Ignore if channel listener is invalid
                    channelEntry.lastUsed = System.currentTimeMillis()
                    channelEntry.listener.addMessage(guildMessageData)
                    return
                }
                // Process the message as if it was a command
                val entry = channelListeners.computeIfAbsent(channel.idLong) {
                    // Create channel listener if it doesn't exist
                    //println("CREATE CHANNELLISTENER: ${guild.idLong}/${channel.idLong}")
                    CacheEntry(ChannelListener(application, this, guild, channel), System.currentTimeMillis())
                }
                entry.lastUsed = System.currentTimeMillis()
                entry.listener.addCommand(guildMessageData)
            }
        }
    }

    fun dispose() {
        destroyed = true
        cleanUpJob.cancel()
        messageActor.close()
    }

    private class CacheEntry(val listener: ChannelListener, var lastUsed: Long)

}