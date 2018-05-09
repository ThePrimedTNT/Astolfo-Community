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
                description("Astolfo-Community is a open-sourced community version of Astolfo! If you want to contribute or take a look at the source code, visit our [Github](https://github.com/ThePrimedTNT/Astolfo-Community)")
                field("Stats", "*$guildCount* servers" +
                        "\n*$totalChannels* channels," +
                        "\n*$userCount* users" +
                        "\n*${application.shardManager.shards.size}* Shards (*#${event.jda.shardInfo.shardId + 1}*)", true)
                field("Music", "*${application.musicManager.sessionCount}* sessions" +
                        "\n*${application.musicManager.queuedSongCount}* queued songs" +
                        "\n*${application.musicManager.listeningCount}* listening", true)
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
    command("help") {
        action {
            event.author.openPrivateChannel().queue {
                it.sendMessage(embed {
                    title("Astolfo Command Help")
                    description("If you're having  trouble with anything, you can always stop by our support server!" +
                            "\nInvite Link: https://discord.gg/23RB2Wc")
                    for (module in modules) {
                        val commandNames = module.commands.map { "`${it.name}` " }.fold("", { a, b -> "$a $b" })
                        field("${module.name} Commands", commandNames, false)
                    }
                }).queue()
            }
        }
    }
}