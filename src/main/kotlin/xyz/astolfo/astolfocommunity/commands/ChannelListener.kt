package xyz.astolfo.astolfocommunity.commands

import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.TextChannel
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import java.util.concurrent.TimeUnit

class ChannelListener(
        val application: AstolfoCommunityApplication,
        val guildListener: GuildListener,
        val guild: Guild,
        val channel: TextChannel
) {

    private val cleanUpJob = launch(MessageListener.messageProcessorContext) {
        while (isActive && !destroyed) {
            // Clean up every 5 minutes
            delay(5, TimeUnit.MINUTES)
            channelActor.send(CleanUp)
        }
    }

    private var destroyed = false

    suspend fun addMessage(guildMessageData: GuildListener.GuildMessageData) = channelActor.send(MessageEvent(guildMessageData))
    suspend fun addCommand(guildMessageData: GuildListener.GuildMessageData) = channelActor.send(CommandEvent(guildMessageData))

    private interface ChannelEvent
    private class CommandEvent(val guildMessageData: GuildListener.GuildMessageData) : ChannelEvent
    private class MessageEvent(val guildMessageData: GuildListener.GuildMessageData) : ChannelEvent
    private object CleanUp : ChannelEvent

    private val channelActor = actor<ChannelEvent>(context = MessageListener.messageProcessorContext, capacity = Channel.UNLIMITED) {
        for (event in channel) {
            if (destroyed) continue
            handleEvent(event)
        }
        sessionListeners.forEach { it.value.listener.dispose() }
    }

    private val sessionListeners = mutableMapOf<Long, CacheEntry>()

    private suspend fun handleEvent(event: ChannelEvent) {
        when (event) {
            is CleanUp -> {
                val currentTime = System.currentTimeMillis()
                val expiredListeners = mutableListOf<Long>()
                for ((key, value) in sessionListeners) {
                    val deltaTime = currentTime - value.lastUsed
                    // Remove if its older then 10 minutes
                    if (deltaTime > TimeUnit.MINUTES.toSeconds(10)) {
                        value.listener.dispose()
                        //println("REMOVE SESSIONLISTENER: ${guild.idLong}/${channel.idLong}/$key")
                        expiredListeners.add(key)
                    }
                }
                expiredListeners.forEach { sessionListeners.remove(it) }
            }
            is MessageEvent -> {
                // forward to session listener
                val guildMessageData = event.guildMessageData
                val user = guildMessageData.messageReceivedEvent.author!!
                val sessionEntry = sessionListeners[user.idLong] ?: return // Ignore if session is invalid
                sessionEntry.lastUsed = System.currentTimeMillis()
                sessionEntry.listener.addMessage(guildMessageData)
            }
            is CommandEvent -> {
                // forward to and create session listener
                val guildMessageData = event.guildMessageData
                val member = guildMessageData.messageReceivedEvent.member!!

                val entry = sessionListeners.computeIfAbsent(member.user.idLong) {
                    // Create session listener if it doesn't exist
                    //println("CREATE SESSIONLISTENER: ${guild.idLong}/${channel.idLong}/${member.user.idLong}")
                    CacheEntry(SessionListener(application, this, guild, channel, member), System.currentTimeMillis())
                }
                entry.lastUsed = System.currentTimeMillis()
                entry.listener.addCommand(guildMessageData)
            }
        }
    }

    fun dispose() {
        destroyed = true
        cleanUpJob.cancel()
        channelActor.close()
    }

    private class CacheEntry(val listener: SessionListener, var lastUsed: Long)

}