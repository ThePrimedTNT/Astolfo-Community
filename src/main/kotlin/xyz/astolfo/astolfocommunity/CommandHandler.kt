package xyz.astolfo.astolfocommunity

import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import xyz.astolfo.astolfocommunity.modules.modules
import java.util.concurrent.TimeUnit

class CommandHandler(val astolfoCommunityApplication: AstolfoCommunityApplication) : ListenerAdapter() {

    private val messageProcessorContext = newFixedThreadPoolContext(20, "Message Processor")
    private val commandProcessorContext = newFixedThreadPoolContext(20, "Command Processor")

    private val rateLimiter = RateLimiter<Long>(4, 6)

    override fun onMessageReceived(event: MessageReceivedEvent?) {
        val timeIssued = System.nanoTime()
        if (event!!.author.isBot) return
        if (event.textChannel?.canTalk() != true) return
        launch(messageProcessorContext) {

            val rawMessage = event.message.contentRaw!!
            val prefix = astolfoCommunityApplication.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong).prefix.takeIf { it.isNotBlank() }
                    ?: astolfoCommunityApplication.properties.default_prefix

            if (!rawMessage.startsWith(prefix, ignoreCase = true)) {
                val sessionKey = SessionKey(event.guild.idLong, event.author.idLong, event.channel.idLong)
                val currentSession = commandSessionMap.getIfPresent(sessionKey)
                // Checks if there is currently a session, if so, check if its a follow up response
                if (currentSession != null && currentSession.hasResponseListeners() && processRateLimit(event)) {
                    val execution = CommandExecution(astolfoCommunityApplication, event, currentSession.commandPath, rawMessage, timeIssued)
                    if (currentSession.shouldRunCommand(execution) || !currentSession.hasResponseListeners()) {
                        // If the response listeners return true or all the response listeners removed themselves
                        commandSessionMap.invalidate(sessionKey)
                    }
                }
                return@launch
            }

            if (processRateLimit(event))
                launch(commandProcessorContext) {
                    val commandMessage = rawMessage.substring(prefix.length)

                    modules.find { processCommand(event, timeIssued, it.commands, "", commandMessage) }
                }
        }
    }

    private fun processRateLimit(event: MessageReceivedEvent): Boolean {
        val user = event.author.idLong
        val wasLimited = rateLimiter.isLimited(user)
        rateLimiter.add(user)
        if (wasLimited) return false
        if (rateLimiter.isLimited(user)) {
            event.channel.sendMessage("${event.member.asMention} You have been ratelimited! Please wait a little and try again!").queue()
            return false
        }
        return true
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

        val execution = CommandExecution(astolfoCommunityApplication, event, newCommandPath, commandContent, timeIssued)

        if (!command.inheritedAction.invoke(execution)) return true

        if (!processCommand(event, timeIssued, command.subCommands, newCommandPath, commandContent)) {
            val sessionKey = SessionKey(event.guild.idLong, event.author.idLong, event.channel.idLong)
            val currentSession = commandSessionMap.getIfPresent(sessionKey)
            // Checks if command is the same as the previous, if so, check if its a follow up response
            if (currentSession != null && currentSession.hasResponseListeners() && currentSession.commandPath.equals(newCommandPath, true)) {
                if (currentSession.shouldRunCommand(execution)) {
                    // If the response listeners return true
                    commandSessionMap.invalidate(sessionKey)
                    command.action.invoke(execution)
                } else if (!currentSession.hasResponseListeners()) {
                    // If the response listeners all ran and removed themselves
                    commandSessionMap.invalidate(sessionKey)
                }
            } else {
                commandSessionMap.invalidate(sessionKey)
                command.action.invoke(execution)
            }
        }
        return true
    }

}