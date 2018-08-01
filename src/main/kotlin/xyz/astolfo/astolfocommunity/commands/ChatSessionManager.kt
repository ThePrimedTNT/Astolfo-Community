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
import net.dv8tion.jda.core.entities.Member
import xyz.astolfo.astolfocommunity.ASTOLFO_GSON
import xyz.astolfo.astolfocommunity.AstolfoProperties
import java.util.*
import java.util.concurrent.TimeUnit

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