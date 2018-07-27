package xyz.astolfo.astolfocommunity.modules

import net.dv8tion.jda.core.JDAInfo
import xyz.astolfo.astolfocommunity.menus.memberSelectionBuilder
import xyz.astolfo.astolfocommunity.messages.*
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
    command("about", "info") {
        val numberFormatter = DecimalFormat.getIntegerInstance()
        action {
            messageAction(embed {
                title("Astolfo Community Info")
                val shardManager = application.shardManager
                val guildCount = shardManager.guildCache.size()
                val totalChannels = shardManager.textChannelCache.size() + shardManager.voiceChannelCache.size()
                val userCount = shardManager.userCache.size()
                description("Astolfo-Community is an open-sourced community version of Astolfo! If you want to contribute or take a look at the source code, visit our [Github](https://github.com/ThePrimedTNT/Astolfo-Community)!")
                field("Stats", "$guildCount *servers*" +
                        "\n${numberFormatter.format(totalChannels)} *channels*" +
                        "\n${numberFormatter.format(userCount)} *users*" +
                        "\n${application.shardManager.shards.size} *Shards (#${event.jda.shardInfo.shardId + 1})*", true)
                field("Music", "${numberFormatter.format(application.musicManager.sessionCount)} *sessions*" +
                        "\n${numberFormatter.format(application.musicManager.queuedSongCount)} *queued songs*" +
                        "\n${numberFormatter.format(application.musicManager.listeningCount)} *listening*", true)
                field("Version", "v1.0.26", true) // Number of commits? idk
                field("Library", "JDA ${JDAInfo.VERSION}", true)
                field("Our support server", "https://discord.gg/23RB2Wc", true)

            }).queue()
        }
    }
    command("avatar", "pfp") {
        action {
            val selectedMember = memberSelectionBuilder(args).title("Profile Selection").execute() ?: return@action
            messageAction(embed {
                title("Astolfo Profile Pictures", selectedMember.user.avatarUrl)
                description("${selectedMember.asMention} Profile Picture!")
                image(selectedMember.user.effectiveAvatarUrl)
            }).queue()
        }
    }
    command("links", "invite") {
        action {
            messageAction(embed {
                title("Useful links")
                description("**Bot's Website**:   https://astolfo.xyz/" +
                        "\n**GitHub**:                https://www.github.com/theprimedtnt/astolfo-community" +
                        "\n**Commands**:        https://astolfo.xyz/commands" +
                        "\n**Support Server**: https://discord.gg/23RB2Wc" +
                        "\n**Donate**:                https://www.patreon.com/theprimedtnt" +
                        "\n**Invite Astolfo**:    https://discordapp.com/oauth2/authorize?client_id=${event.jda.selfUser.idLong}&scope=bot&permissions=37088334")
            }).queue()
        }
    }
    command("usercount") {
        action {
            messageAction(embed("There are **${event.message.guild.members.size}** members in this guild.")).queue()
        }
    }
    command("donate", "patreon", "patron") {
        action {
            messageAction(embed {
                title("As Astolfo grows, it needs to upgrade its servers.")
                field("PS: You get rewards and perks for donating!", "Donate here: https://www.patreon.com/theprimedtnt", false)
                field("Did you know you can upvote 20 times per month and receive free \$5 supporter status for the next 30 days?",
                        "Upvote here: https://discordbots.org/bot/astolfo/vote", false)
            }).queue()
        }
    }
    command("help") {
        action {
            messageAction(embed(":mailbox: I have private messaged you a list of commands!")).queue()
            event.author.openPrivateChannel().queue {
                it.sendMessage(embed {
                    title("Astolfo Command Help")
                    description("If you're having  trouble with anything, you can always stop by our support server!" +
                            "\nInvite Link: https://discord.gg/23RB2Wc")
                    for (module in modules) {
                        if ((module.hidden) || (module.nsfw && !this@action.event.channel.isNSFW)) continue
                        val commandNames = module.commands.joinToString(" ") { "`${it.name}` " }
                        field("${module.name} Commands", commandNames, false)
                    }
                }).queue()
            }
        }
    }
}