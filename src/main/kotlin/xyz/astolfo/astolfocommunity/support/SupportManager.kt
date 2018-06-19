package xyz.astolfo.astolfocommunity.support

import xyz.astolfo.astolfocommunity.commands.CommandBuilder
import java.util.concurrent.TimeUnit


fun CommandBuilder.supportBuilder(block: SupportBuilder.() -> Unit) {
    val supportBuilder = SupportBuilder(this)
    block(supportBuilder)
    supportBuilder.build()
}

// TODO make this less messy
class SupportBuilder(private val commandBuilder: CommandBuilder) {
    private var upvoteDays = -1L
    private var longTermUpvoteMessage = "You must upvote the bot to use this feature!"
    private var shortTermUpvoteMessage: (Long) -> String = { days -> "You havn't upvoted in the past $days days! Upvote to continue using this feature." }
    private var supportLevel: SupportLevel? = null

    fun upvote(days: Long) = UpvoteBuilder().days(days)
    fun supportLevel(value: SupportLevel) = apply { supportLevel = value }

    fun build() = commandBuilder.inheritedAction {
        if (supportLevel != null) {
            // Check if user has valid support role
            val donationEntry = application.astolfoRepositories.donationRepository.findByDiscordId(event.author.idLong)
            if (donationEntry != null && donationEntry.supportLevel.ordinal >= supportLevel!!.ordinal) return@inheritedAction true
        }
        if (upvoteDays > 0) {
            // Check upvote status if they havent donated
            val profile = application.astolfoRepositories.getEffectiveUserProfile(event.author.idLong)
            val upvoteInfo = profile.userUpvote
            val message = when {
                upvoteInfo.lastUpvote <= 0 || upvoteInfo.timeSinceLastUpvote >= TimeUnit.DAYS.toMillis(upvoteDays + 3) -> longTermUpvoteMessage
                upvoteInfo.timeSinceLastUpvote >= TimeUnit.DAYS.toMillis(upvoteDays) ->  shortTermUpvoteMessage.invoke(upvoteDays)
                else -> null
            } ?: return@inheritedAction true
            val stringBuilder = StringBuilder(message)
            stringBuilder.append(" Upvote here: <https://discordbots.org/bot/${event.jda.selfUser.idLong}>")
            if(supportLevel != null){
                stringBuilder.append(" Instead of upvoting you can always become a patron (<https://www.patreon.com/theprimedtnt>) and unlock even more features!" +
                        " To unlock this command by donating you need at least the ${supportLevel!!.rewardName} Reward.")
            }
            messageAction(stringBuilder).queue()
            return@inheritedAction false
        }
        true
    }

    inner class UpvoteBuilder {
        fun days(value: Long) = apply { this@SupportBuilder.upvoteDays = value }
        fun longTermUpvoteMessage(value: String) = apply { this@SupportBuilder.longTermUpvoteMessage = value }
        fun shortTermUpvoteMessage(value: (Long) -> String) = apply { this@SupportBuilder.shortTermUpvoteMessage = value }
    }
}