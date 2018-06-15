package xyz.astolfo.astolfocommunity

import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.JDA
import org.discordbots.api.client.DiscordBotListAPI
import org.discordbots.api.client.integration.JDAStatPoster
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import java.util.concurrent.TimeUnit

@Controller
@RequestMapping(value = ["/botlist"])
class BotLists(private val astolfoCommunityApplication: AstolfoCommunityApplication,
               private val properties: AstolfoProperties) {

    init {
        val discordBotList = DiscordBotListAPI.Builder().token(properties.discordbotlist_token).build()
        astolfoCommunityApplication.shardManager.addEventListener(JDAStatPoster(discordBotList))
        launch {
            while (isActive) {
                val toRemind = astolfoCommunityApplication.astolfoRepositories.userProfileRepository.findUpvoteReminder()
                for (profile in toRemind) {
                    val user = astolfoCommunityApplication.shardManager.getUserById(profile.userId)
                    if(user == null && astolfoCommunityApplication.shardManager.shards.any { it.status.isInit }) continue
                    user?.openPrivateChannel()?.queue { privateChannel ->
                        privateChannel.sendMessage(message("Looks like you havn't upvoted for a while. Make sure to upvote daily! https://discordbots.org/bot/${privateChannel.jda.selfUser.idLong}")).queue()
                    }
                    profile.userUpvote.remindedUpvote = true
                    astolfoCommunityApplication.astolfoRepositories.userProfileRepository.save(profile)
                }
                delay(10, TimeUnit.MINUTES)
            }
        }
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
                    privateChannel.sendMessage(message("Thank you for upvoting! You now have access to the volume feature for the next two days! You also received 1000 credits for upvoting. Make sure to upvote daily! https://discordbots.org/bot/${body.bot}")).queue()
                }
                val profile = astolfoCommunityApplication.astolfoRepositories.getEffectiveUserProfile(body.user)
                profile.credits += 1000
                profile.userUpvote.lastUpvote = System.currentTimeMillis()
                profile.userUpvote.remindedUpvote = false
                astolfoCommunityApplication.astolfoRepositories.userProfileRepository.save(profile)
            }
            else -> return ResponseEntity(HttpStatus.BAD_REQUEST)
        }
        return ResponseEntity(HttpStatus.OK)
    }

    class DiscordBotListBody(val bot: Long, val user: Long, val type: String)

}