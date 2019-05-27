package xyz.astolfo.astolfocommunity.modules

import net.dv8tion.jda.core.entities.MessageEmbed
import xyz.astolfo.astolfocommunity.Emotes
import xyz.astolfo.astolfocommunity.RateLimiter
import xyz.astolfo.astolfocommunity.Utils
import xyz.astolfo.astolfocommunity.menus.paginator
import xyz.astolfo.astolfocommunity.menus.provider
import xyz.astolfo.astolfocommunity.messages.*
import java.awt.Color
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.roundToLong

fun createCasinoModule() = module("Casino") {
    command("credits") {
        action {
            reply(embed("${Emotes.BANK} You have **${getProfile().credits} credits**!")).queue()
        }
    }
    command("dailies") {
        val random = Random()
        val day1 = TimeUnit.DAYS.toMillis(1)
        val minute30 = TimeUnit.MINUTES.toMillis(30)

        val minDaily = 100
        val maxDaily = 1000
        val rangeDaily = maxDaily - minDaily

        action {
            val profile = getProfile()
            val userDaily = profile.daily

            val currentTime = System.currentTimeMillis()
            val timeLeft = day1 - (currentTime - userDaily.lastDaily)
            if (timeLeft > minute30) {
                reply(embed("You can receive your next daily in **${Utils.formatDuration(timeLeft)}**. If you haven't upvoted already, you can get your daily bonus of 1000 credits! https://discordbots.org/bot/${event.jda.selfUser.idLong}")).queue()
                return@action
            }

            val creditsEarned = (minDaily + rangeDaily * random.nextDouble().pow(2.0)).roundToLong()
            profile.credits += creditsEarned
            userDaily.lastDaily = currentTime

            application.astolfoRepositories.userProfileRepository.save(profile)

            reply(embed("${Emotes.MONEY_BAG} Congrats, you got your daily bonus of ***$creditsEarned credits***! Total: **${profile.credits}**")).queue()
        }
    }
    command("leaderboard") {
        action {
            paginator("Astolfo Leaderboards") {
                provider(
                    8,
                    application.astolfoRepositories.userProfileRepository.findTop50ByOrderByCreditsDesc().map { profile ->
                        val user =
                            application.shardManager.getUserById(profile.userId)?.let { "${it.name}#${it.discriminator}" }
                                ?: "UNKNOWN " + profile.userId
                        "**$user** - *${profile.credits}*"
                    })
            }
        }
    }
    command("slots") {
        val random = Random()
        val slotsRateLimiter = RateLimiter<Long>(1, 6)

        val symbols = listOf(
            "\uD83C\uDF4F",
            "\uD83C\uDF4E",
            "\uD83C\uDF50",
            "\uD83C\uDF4A",
            "\uD83C\uDF4B",
            "\uD83C\uDF52",
            "\uD83C\uDF51"
        )
        val SLOT_COUNT = 5

        action {
            if (slotsRateLimiter.isLimited(event.author.idLong)) {
                reply(errorEmbed("Please cool down! (**${Utils.formatDuration(slotsRateLimiter.remainingTime(event.author.idLong)!!)}** seconds left)")).queue()
                return@action
            }
            slotsRateLimiter.add(event.author.idLong)

            val userProfile = getProfile()

            val bidAmount = commandContent.takeIf { it.isNotBlank() }?.let {
                val amountNum = it.toBigIntegerOrNull()?.toLong()
                if (amountNum == null) {
                    reply(errorEmbed("The bid amount must be a whole number!")).queue()
                    return@action
                }
                if (amountNum < 10) {
                    reply(errorEmbed("The bid amount must be at least 10 credits!")).queue()
                    return@action
                }
                amountNum
            } ?: 10
            if (bidAmount > userProfile.credits) {
                reply(errorEmbed("You don't have enough credits to bid this amount!")).queue()
                return@action
            }

            val slotResults = (1..SLOT_COUNT).map { symbols[random.nextInt(symbols.size)] }
            val matches = symbols.map { type -> slotResults.count { it.equals(type, ignoreCase = true) } }.max() ?: 0

            val result = when {
                matches == 1 -> -bidAmount
                matches == 2 -> 0
                matches >= 3 -> ((matches - 2.0).pow(2.0) * (50.0 / 100.0) * bidAmount).toLong()
                else -> TODO("Um what")
            }

            userProfile.credits += result
            application.astolfoRepositories.userProfileRepository.save(userProfile)

            var slotsToShow = 0

            fun isFinished() = slotsToShow >= SLOT_COUNT

            fun createMessage(): MessageEmbed {
                return embed {
                    var description = "**Bid Amount:** $bidAmount credits\n\n${slotResults.mapIndexed { index, value ->
                        if (index <= slotsToShow) value
                        else symbols[random.nextInt(symbols.size)]
                    }.joinToString(separator = "")}"
                    if (isFinished()) {
                        description += when {
                            result < 0 -> {
                                color(Color.RED)
                                "\n\n\u274C Sorry, you lost ***${result.absoluteValue} credits***... You now have **${userProfile.credits} credits**"
                            }
                            result == 0L -> {
                                color(Color.YELLOW)
                                "\n\n Sorry, you didnt win anything this time!"
                            }
                            result > 0 -> "\n\n\uD83D\uDCB0 Congrats, you won a total of ***${result.absoluteValue} credits!*** You now have **${userProfile.credits} credits**"
                            else -> TODO("Um what")
                        }
                    }
                    description(description)
                }
            }

            val message = reply(createMessage()).sendCached()
            var editDelay = 1L
            while (!isFinished()) {
                slotsToShow += 3
                message.editMessage(createMessage(), editDelay++)
            }
        }
    }
}