package xyz.astolfo.astolfocommunity

import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import java.util.concurrent.TimeUnit

class CommandHandler(val astolfoCommunityApplication: AstolfoCommunityApplication) : ListenerAdapter() {

    private val messageProcessorContext = newFixedThreadPoolContext(20, "Message Processor")
    private val commandProcessorContext = newFixedThreadPoolContext(20, "Command Processor")

    override fun onMessageReceived(event: MessageReceivedEvent?) {
        launch(messageProcessorContext) {
            val timeIssued = System.nanoTime()

            val rawMessage = event!!.message.contentRaw!!
            val prefix = "c?"

            if (!rawMessage.startsWith(prefix, ignoreCase = true)) return@launch

            launch(commandProcessorContext) {
                val commandMessage = rawMessage.substring(prefix.length)

                modules.find { processCommand(event, timeIssued, it.commands, commandMessage) }
            }
        }
    }

    val commandSessionMap = CacheBuilder.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .removalListener<SessionKey, CommandSession> { it.value.destroy() }
            .build<SessionKey, CommandSession>()

    data class SessionKey(val guildId: Long, val memberId: Long, val channelId: Long)

    private fun processCommand(event: MessageReceivedEvent, timeIssued: Long, commands: List<Command>, commandMessage: String): Boolean {
        val commandName: String
        val commandContent: String
        if (commandMessage.contains(" ")) {
            commandName = commandMessage.substringBefore(" ").trim()
            commandContent = commandMessage.substringAfter(" ").trim()
        } else {
            commandName = commandMessage
            commandContent = ""
        }

        val command = commands.find { it.name.equals(commandName, ignoreCase = true) } ?: return false

        if (!processCommand(event, timeIssued, command.subCommands, commandContent)) {
            // TODO: Make it so the sessions would transfer to continuous commands
            commandSessionMap.invalidate(SessionKey(event.guild.idLong, event.author.idLong, event.channel.idLong))
            command.action.invoke(CommandExecution(astolfoCommunityApplication, event, commandContent, timeIssued))
        }

        return true
    }

}