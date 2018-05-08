package xyz.astolfo.astolfocommunity

import net.dv8tion.jda.core.JDAInfo
import java.awt.Color
import java.text.DecimalFormat

fun createInfoModule() = module("Info") {
    command("ping") {
        val format = DecimalFormat("#0.###")
        action {
            val pingStartTime = System.nanoTime()
            messageAction("pinging").queue { message ->
                val pingEndTime = System.nanoTime()
                val pingTimeDifference = pingEndTime - pingStartTime
                val processTime = pingStartTime - timeIssued
                message.editMessage("REST Ping: **${format.format(pingTimeDifference / 1000000.0)}ms**" +
                        "\nDiscord WebSocket: **${message.jda.ping}ms**" +
                        "\nBot Process Time: **${format.format(processTime / 1000000.0)}ms**").queue()
            }
        }
    }
    command("about") {
        action {
            messageAction(embed {
                title("Astolfo Community Info")
                val shardManager = application.shardManager
                val guildCount = shardManager.guildCache.size()
                val totalChannels = shardManager.textChannelCache.size() + shardManager.voiceChannelCache.size()
                val userCount = shardManager.userCache.size()
                field("Stats", "*$guildCount* servers" +
                        "\n*$totalChannels* channels," +
                        "\n*$userCount* users", false)
                field("Library", JDAInfo.VERSION, false)
            }).queue()
        }
    }
    command("avatar") {
        action {
            messageAction(embed {
                val mentionedUser = event.message.mentionedUsers.getOrNull(0) ?: event.message.author

                color(Color(173, 20, 87))
                title("Astolfo Profile Pictures", mentionedUser.avatarUrl)
                description("${mentionedUser.asMention} Profile Picture!")
                image(mentionedUser.avatarUrl)
            }).queue()
        }
    }
}