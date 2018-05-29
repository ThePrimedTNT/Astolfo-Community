package xyz.astolfo.astolfocommunity

import org.discordbots.api.client.DiscordBotListAPI
import org.discordbots.api.client.integration.JDAStatPoster
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod

@Controller
@RequestMapping(value = ["/botlist"])
class BotLists(private val astolfoCommunityApplication: AstolfoCommunityApplication,
               private val properties: AstolfoProperties) {

    init {
        val discordBotList = DiscordBotListAPI.Builder().token(properties.discordbotlist_token).build()
        astolfoCommunityApplication.shardManager.addEventListener(JDAStatPoster(discordBotList))
    }

    @RequestMapping(method = [RequestMethod.POST], value = "/discordbotlist")
    fun discordBotList(@RequestHeader("Authorization") authorization: String,
                       @RequestBody body: DiscordBotListBody): ResponseEntity<Any> {
        if (properties.discordbotlist_password != authorization) return ResponseEntity(HttpStatus.UNAUTHORIZED)
        println(body.user)
        when (body.type) {
            "test" -> println("WebHook: Got test from discordbots.org")
            "upvote" -> {
                // Handle upvote
                val user = astolfoCommunityApplication.shardManager.getUserById(body.user)
                user?.openPrivateChannel()?.queue { privateChannel ->
                    privateChannel.sendMessage(message("Thank you for upvoting! Make sure to upvote daily! https://discordbots.org/bot/${body.bot}")).queue()
                }
            }
            else -> return ResponseEntity(HttpStatus.BAD_REQUEST)
        }
        return ResponseEntity(HttpStatus.OK)
    }

    class DiscordBotListBody(val bot: Long, val user: Long, val type: String)

}