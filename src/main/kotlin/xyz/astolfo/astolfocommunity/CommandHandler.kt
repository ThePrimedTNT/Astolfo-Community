package xyz.astolfo.astolfocommunity

import ai.api.AIConfiguration
import ai.api.AIDataService
import ai.api.AIServiceContextBuilder
import ai.api.model.AIRequest
import ai.api.model.ResponseMessage
import com.github.salomonbrys.kotson.contains
import com.github.salomonbrys.kotson.fromJson
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import xyz.astolfo.astolfocommunity.modules.modules
import java.util.*
import java.util.concurrent.TimeUnit

class CommandHandler(val astolfoCommunityApplication: AstolfoCommunityApplication) : ListenerAdapter() {

    private val chatBotManager = ChatBotManager(astolfoCommunityApplication.properties)

    private val messageProcessorContext = newFixedThreadPoolContext(20, "Message Processor")
    private val commandProcessorContext = newFixedThreadPoolContext(20, "Command Processor")

    private val rateLimiter = RateLimiter<Long>(4, 6)

    private val mentionPrefixes: Array<String>

    init {
        val botId = astolfoCommunityApplication.properties.bot_user_id
        mentionPrefixes = arrayOf("<@$botId>", "<@!$botId>")
    }

    override fun onMessageReceived(event: MessageReceivedEvent?) {
        val timeIssued = System.nanoTime()
        if (event!!.author.isBot) return
        if (event.textChannel?.canTalk() != true) return
        launch(messageProcessorContext) {

            val rawMessage = event.message.contentRaw!!
            val prefix = astolfoCommunityApplication.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong).prefix.takeIf { it.isNotBlank() }
                    ?: astolfoCommunityApplication.properties.default_prefix

            val effectivePrefixes = listOf(*mentionPrefixes, prefix)
            val prefixedMatched = effectivePrefixes.find { rawMessage.startsWith(it, ignoreCase = true) }

            if (prefixedMatched == null) {
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

            if (!processRateLimit(event)) return@launch

            launch(commandProcessorContext) {
                val commandMessage = rawMessage.substring(prefixedMatched.length).trim()

                if (modules.find { processCommand(event, timeIssued, it.commands, "", commandMessage) } != null || prefixedMatched == prefix) return@launch

                // Process chat bot stuff
                val response = chatBotManager.process(event.member, commandMessage)
                if (response.type == ChatResponse.ResponseType.COMMAND) {
                    modules.find { processCommand(event, timeIssued, it.commands, "", response.response) }
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

        val response = dataService.request(request, context)
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