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

            if (!rawMessage.startsWith(prefix, ignoreCase = true)) {
                val sessionKey = SessionKey(event.guild.idLong, event.author.idLong, event.channel.idLong)
                // Gets previous command ran and checks if its a follow up response
                if (commandSessionMap.getIfPresent(sessionKey)?.let { it.shouldRunCommand(CommandExecution(astolfoCommunityApplication, event, it.commandPath, rawMessage, timeIssued)) } != false)
                    commandSessionMap.invalidate(sessionKey)
                return@launch
            }

            launch(commandProcessorContext) {
                val commandMessage = rawMessage.substring(prefix.length)

                modules.find { processCommand(event, timeIssued, it.commands, "", commandMessage) }
            }
        }
    }

    val commandSessionMap = CacheBuilder.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .removalListener<SessionKey, CommandSession> { it.value.destroy() }
            .build<SessionKey, CommandSession>()

    data class SessionKey(val guildId: Long, val memberId: Long, val channelId: Long)

    private fun processCommand(event: MessageReceivedEvent, timeIssued: Long, commands: List<Command>, commandPath: String, commandMessage: String): Boolean {
        val commandName: String
        val commandContent: String
        if (commandMessage.contains(" ")) {
            commandName = commandMessage.substringBefore(" ").trim()
            commandContent = commandMessage.substringAfter(" ").trim()
        } else {
            commandName = commandMessage
            commandContent = ""
        }

        val command = commands.find { it.name.equals(commandName, ignoreCase = true) || it.alts.any { it.equals(commandName, ignoreCase = true) } }
                ?: return false

        val newCommandPath = "$commandPath ${command.name}"

        if (!processCommand(event, timeIssued, command.subCommands, newCommandPath, commandContent)) {
            val execution = CommandExecution(astolfoCommunityApplication, event, newCommandPath, commandContent, timeIssued)
            val sessionKey = SessionKey(event.guild.idLong, event.author.idLong, event.channel.idLong)
            val currentSession = commandSessionMap.getIfPresent(sessionKey)
            // Checks if command is the same as the previous, if so, check if its a follow up response
            if (currentSession?.takeIf { it.commandPath.equals(newCommandPath, true) }?.shouldRunCommand(execution) != false) {
                commandSessionMap.invalidate(sessionKey)
                command.action.invoke(execution)
            }
        }
        return true
    }

}