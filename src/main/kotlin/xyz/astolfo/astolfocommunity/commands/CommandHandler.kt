package xyz.astolfo.astolfocommunity.commands

import ai.api.AIConfiguration
import ai.api.AIDataService
import ai.api.AIServiceContextBuilder
import ai.api.AIServiceException
import ai.api.model.AIRequest
import ai.api.model.ResponseMessage
import com.github.salomonbrys.kotson.contains
import com.github.salomonbrys.kotson.fromJson
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import io.sentry.Sentry
import io.sentry.event.EventBuilder
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.ChannelType
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import xyz.astolfo.astolfocommunity.*
import xyz.astolfo.astolfocommunity.modules.Module
import xyz.astolfo.astolfocommunity.modules.modules
import java.util.*
import java.util.concurrent.TimeUnit

class CommandHandler(val application: AstolfoCommunityApplication) : ListenerAdapter() {

    private val chatBotManager = ChatBotManager(application.properties)

    private val messageProcessorContext = newFixedThreadPoolContext(20, "Message Processor")
    private val commandProcessorContext = newFixedThreadPoolContext(20, "Command Processor")

    private val rateLimiter = RateLimiter<Long>(4, 6)
    private val commandSessionManager = CommandSessionManager()

    private val mentionPrefixes: Array<String>

    init {
        val botId = application.properties.bot_user_id
        mentionPrefixes = arrayOf("<@$botId>", "<@!$botId>")
    }

    override fun onMessageReceived(event: MessageReceivedEvent?) {
        application.statsDClient.incrementCounter("messages_received")
        val timeIssued = System.nanoTime()
        if (event!!.author.isBot) return
        if (event.channelType != ChannelType.TEXT) return
        if (event.textChannel?.canTalk() != true) return
        launch(messageProcessorContext) {
            val prefix = application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong).getEffectiveGuildPrefix(application)

            val rawMessage = event.message.contentRaw!!

            val effectivePrefixes = listOf(*mentionPrefixes, prefix)
            val prefixedMatched = effectivePrefixes.find { rawMessage.startsWith(it, ignoreCase = true) }

            if (prefixedMatched == null) {
                commandScope@ launch(commandProcessorContext) {
                    val currentSession = commandSessionManager.get(event)
                    // Checks if there is currently a session, if so, check if its a follow up response
                    if (currentSession != null) {
                        if (currentSession.getListeners().isEmpty()) return@commandScope
                        // TODO add rate limit
                        //if (!processRateLimit(event)) return@launch
                        val execution = CommandExecution(application, event, currentSession, currentSession.commandPath, rawMessage, timeIssued)
                        if (currentSession.onMessageReceived(execution) == CommandSession.ResponseAction.RUN_COMMAND) {
                            // If the response listeners return true or all the response listeners removed themselves
                            commandSessionManager.invalidate(event)
                        }
                    }
                }
                return@launch
            }

            if (!processRateLimit(event)) return@launch

            commandScope@ launch(commandProcessorContext) {
                val commandMessage = rawMessage.substring(prefixedMatched.length).trim()

                if (modules.find { processCommand(it, event, timeIssued, it.commands, "", commandMessage) } != null) {
                    application.statsDClient.incrementCounter("commands_executed")
                    return@commandScope
                }
                if (prefixedMatched == prefix) return@commandScope

                if (commandMessage.isEmpty()) {
                    event.channel.sendMessage("Hi :D").queue()
                    return@commandScope
                }

                // Process chat bot stuff
                val response = chatBotManager.process(event.member, commandMessage)
                if (response.type == ChatResponse.ResponseType.COMMAND) {
                    if (modules.find { processCommand(it, event, timeIssued, it.commands, "", response.response) } != null)
                        application.statsDClient.incrementCounter("commands_executed")
                } else {
                    event.channel.sendMessage(response.response).queue()
                }
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

    private suspend fun processCommand(module: Module?, event: MessageReceivedEvent, timeIssued: Long, commands: List<Command>, commandPath: String, commandMessage: String): Boolean {
        val (commandName, commandContent) = commandMessage.splitFirst(" ")

        val command = commands.find { it.name.equals(commandName, ignoreCase = true) || it.alts.any { it.equals(commandName, ignoreCase = true) } }
                ?: return false

        if (!event.guild.selfMember.hasPermission(event.textChannel, Permission.MESSAGE_EMBED_LINKS)) {
            event.channel.sendMessage("Please enable **embed links** to use Astolfo commands.").queue()
            return true
        }

        val newCommandPath = "$commandPath ${command.name}".trim()

        fun createExecution(session: CommandSession) = CommandExecution(
                application,
                event,
                session,
                newCommandPath,
                commandContent,
                timeIssued
        )

        if (module?.let { !it.inheritedActions.all { it.invoke(createExecution(InheritedCommandSession(newCommandPath))) } } == true) return true

        val permission = command.permission

        var hasPermission: Boolean? = if (event.member.hasPermission(Permission.ADMINISTRATOR)) true else null
        // Check discord permission if the member isn't a admin already
        if (hasPermission != true && permission.permissionDefaults.isNotEmpty())
            hasPermission = event.member.hasPermission(event.textChannel, *permission.permissionDefaults)
        // Check Astolfo permission if discord permission didn't already grant permissions
        if (hasPermission != true)
            AstolfoPermissionUtils.hasPermission(event.member, event.textChannel, application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong).permissions, permission)?.let { hasPermission = it }

        if (hasPermission == false) {
            event.channel.sendMessage("You are missing the astolfo **${permission.path}**${if (permission.permissionDefaults.isNotEmpty())
                " or discord ${permission.permissionDefaults.joinToString(", ") { "**${it.getName()}**" }}" else ""} permission(s)")
                    .queue()
            return true
        }

        if (!command.inheritedActions.all { it.invoke(createExecution(InheritedCommandSession(newCommandPath))) }) return true

        if (!processCommand(null, event, timeIssued, command.subCommands, newCommandPath, commandContent)) {
            fun runNewSession() {
                application.statsDClient.incrementCounter("commandExecuteCount", "command:$newCommandPath")
                commandSessionManager.session(event, newCommandPath, { session ->
                    command.action.invoke(createExecution(session))
                })
            }

            val currentSession = commandSessionManager.get(event)

            // Checks if command is the same as the previous, if so, check if its a follow up response
            if (currentSession != null && currentSession.commandPath.equals(newCommandPath, true)) {
                val action = currentSession.onMessageReceived(createExecution(currentSession))
                when (action) {
                    CommandSession.ResponseAction.RUN_COMMAND -> {
                        commandSessionManager.invalidate(event)
                        runNewSession()
                    }
                    CommandSession.ResponseAction.IGNORE_COMMAND -> {
                    }
                    else -> TODO("Invalid action: $action")
                }
            } else {
                commandSessionManager.invalidate(event)
                runNewSession()
            }
        }
        return true
    }

}

class ChatBotManager(properties: AstolfoProperties) {

    private val dataService = AIDataService(AIConfiguration(properties.dialogflow_token))

    private val chats = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build(object : CacheLoader<ChatSessionKey, ChatSession>() {
                @Throws(Exception::class)
                override fun load(key: ChatSessionKey): ChatSession {
                    return ChatSession(genSessionId())
                }
            })

    private fun genSessionId(): String {
        while (true) {
            val id = UUID.randomUUID().toString()
            if (chats.asMap().none { it.value.sessionId == id })
                return id
        }
    }

    fun process(member: Member, line: String) = getChat(member).process(dataService, line)

    private fun getChat(member: Member) = chats.get(ChatSessionKey(guildId = member.guild.idLong, userId = member.user.idLong))!!

    private data class ChatSessionKey(val guildId: Long, val userId: Long)

}

class ChatSession constructor(id: String) {
    private val context = AIServiceContextBuilder.buildFromSessionId(id)

    internal val sessionId: String
        get() = context.sessionId

    fun process(dataService: AIDataService, line: String): ChatResponse {
        val request = AIRequest(line)

        val response = try {
            dataService.request(request, context)
        } catch (e: AIServiceException) {
            Sentry.capture(e)
            e.printStackTrace()
            return ChatResponse(ChatResponse.ResponseType.MESSAGE,
                    if (e.message?.startsWith("Authorization failed") == true) "Chat Bot Authorization Invalid (Contact bot owner if you see this message)"
                    else "Internal Chat Bot Exception")
        }
        if (response.status.code != 200) error(response.status.errorDetails)

        val data = response.result.parameters
        for (r in response.result.fulfillment.messages) {
            if (r is ResponseMessage.ResponsePayload && r.payload.contains("discord")) {
                val discordPayload = ASTOLFO_GSON.fromJson<DiscordPayload>(r.payload.getAsJsonObject("discord"))

                var message = discordPayload.msg
                data.entries.forEach { entry ->
                    val key = entry.key
                    val value = entry.value

                    if (value.isJsonObject) {
                        for ((_, value2) in value.asJsonObject.entrySet())
                            message = message.replace(("@$key").toRegex(), value2.asString)
                    } else if (value.isJsonPrimitive)
                        message = message.replace(("@$key").toRegex(), value.asString)
                }
                val responseType =
                        if (discordPayload.type.equals("command", true)) ChatResponse.ResponseType.COMMAND
                        else ChatResponse.ResponseType.MESSAGE

                return ChatResponse(responseType, message)
            }
        }
        return ChatResponse(ChatResponse.ResponseType.MESSAGE, response.result.fulfillment.speech)
    }

    class DiscordPayload(val type: String, val msg: String)

}

data class ChatResponse(var type: ResponseType, var response: String) {
    enum class ResponseType {
        MESSAGE, COMMAND
    }
}