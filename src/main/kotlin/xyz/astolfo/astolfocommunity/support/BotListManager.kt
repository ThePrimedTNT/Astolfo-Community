package xyz.astolfo.astolfocommunity.support

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.discordbots.api.client.DiscordBotListAPI
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.AstolfoProperties
import xyz.astolfo.astolfocommunity.messages.message
import java.util.concurrent.TimeUnit


@Controller
@RequestMapping(value = ["/botlist"])
class BotListManager(
    private val application: AstolfoCommunityApplication,
    private val properties: AstolfoProperties
) {

    init {
        val discordBotList = DiscordBotListAPI.Builder()
            .botId(properties.bot_user_id)
            .token(properties.discordbotlist_token)
            .build()
        GlobalScope.launch {
            while (application.shardManager.shardsQueued > 0) delay(10)
            launch {
                while (isActive) {
                    val toRemind =
                        application.astolfoRepositories.userProfileRepository.findUpvoteReminder(System.currentTimeMillis())
                    for (profile in toRemind) {
                        val user = application.shardManager.getUserById(profile.userId)
                        if (user == null && application.shardManager.shards.any { it.status.isInit }) continue
                        user?.openPrivateChannel()?.queue { privateChannel ->
                            privateChannel.sendMessage(message("Looks like you havn't upvoted for a while. Make sure to upvote daily! https://discordbots.org/bot/${privateChannel.jda.selfUser.idLong}"))
                                .queue()
                        }
                        profile.userUpvote.remindedUpvote = true
                        application.astolfoRepositories.userProfileRepository.save(profile)
                    }
                    delay(TimeUnit.MINUTES.toMillis(10))
                }
            }
            launch {
                while (isActive) {
                    val shards = application.shardManager.shards.filterNotNull()
                    val jda1 = shards.firstOrNull()
                    if (jda1?.selfUser != null) {
                        val counts = (0 until jda1.shardInfo.shardTotal).map { id ->
                            val jda = shards.find { it.shardInfo.shardId == id } ?: return@map 0
                            jda.guilds.size
                        }
                        discordBotList.setStats(counts)
                        delay(TimeUnit.MINUTES.toMillis(5))
                    } else {
                        delay(TimeUnit.SECONDS.toMillis(5))
                    }
                }
            }
        }
    }

    @RequestMapping(method = [RequestMethod.POST], value = ["/discordbotlist"])
    fun discordBotList(
        @RequestHeader("Authorization") authorization: String,
        @RequestBody body: DiscordBotListBody
    ): ResponseEntity<Any> {
        if (properties.discordbotlist_password != authorization) return ResponseEntity(HttpStatus.UNAUTHORIZED)
        println(body.user)
        when (body.type) {
            "test" -> println("WebHook: Got test from discordbots.org")
            "upvote" -> {
                // Handle upvote
                val user = application.shardManager.getUserById(body.user)
                user?.openPrivateChannel()?.queue { privateChannel ->
                    privateChannel.sendMessage(message("Thank you for upvoting! You also received 1000 credits for upvoting. Make sure to upvote daily! https://discordbots.org/bot/${body.bot}"))
                        .queue()
                }
                val profile = application.astolfoRepositories.getEffectiveUserProfile(body.user)
                profile.credits += 1000
                profile.userUpvote.lastUpvote = System.currentTimeMillis()
                profile.userUpvote.remindedUpvote = false
                application.astolfoRepositories.userProfileRepository.save(profile)
            }
            else -> return ResponseEntity(HttpStatus.BAD_REQUEST)
        }
        return ResponseEntity(HttpStatus.OK)
    }

    class DiscordBotListBody(val bot: Long, val user: Long, val type: String)

}