package xyz.astolfo.astolfocommunity.modules

import net.dv8tion.jda.core.JDAInfo
import net.dv8tion.jda.core.entities.PrivateChannel
import xyz.astolfo.astolfocommunity.await
import xyz.astolfo.astolfocommunity.commands.Command
import xyz.astolfo.astolfocommunity.commands.CommandContext
import xyz.astolfo.astolfocommunity.menus.memberSelectionBuilder
import xyz.astolfo.astolfocommunity.messages.*
import java.text.DecimalFormat

object InfoModule : Module("info", Type.GENERIC) {

    init {
        +PingCommand
        +AboutCommand
        +AvatarCommand
        +LinksCommand
        +UserCountCommand
        +DonateCommand
        +HelpCommand
    }

    object PingCommand : Command("ping") {
        override suspend fun executeDefault(context: CommandContext) {
            val restPingStart = System.currentTimeMillis()
            val botProcessDuration = restPingStart - context.timeIssued

            val sentPingingMessage = context.reply(message("Pinging...")).await()

            val restPingEnd = System.currentTimeMillis()
            val restPing = restPingEnd - restPingStart

            sentPingingMessage.editMessage(
                message(
                    "**REST Ping:** ${restPing}ms\n" +
                        "**Discord Websocket Ping:** ${context.jda.ping}ms\n" +
                        "**Bot Process Duration:** ${botProcessDuration}ms"
                )
            ).queue()
        }
    }

    object AboutCommand : Command("about") {
        private val numberFormatter = DecimalFormat.getIntegerInstance()
        override suspend fun executeDefault(context: CommandContext) {
            context.reply(
                embed {
                    setTitle("Astolfo » About")
                    description("Astolfo Bot was developed based on the popular anime character Astolfo.")
                    val shardManager = context.application.shardManager
                    field(
                        "Stats",
                        "${numberFormatter.format(shardManager.guildCache.size())} *servers*\n" +
                            "${numberFormatter.format(shardManager.textChannelCache.size() + shardManager.voiceChannelCache.size())} *channels*\n" +
                            "${numberFormatter.format(shardManager.userCache.size())} *users*\n" +
                            "${numberFormatter.format(shardManager.shardCache.size())} *shards (#${numberFormatter.format(
                                context.jda.shardInfo.shardId
                            )})*",
                        true
                    )
                    val musicManager = context.application.musicManager
                    field(
                        "Music",
                        "${numberFormatter.format(musicManager.sessionCount)} *sessions*\n" +
                            "${numberFormatter.format(musicManager.queuedSongCount)} *queued songs*\n" +
                            "${numberFormatter.format(musicManager.listeningCount)} *listening*",
                        true
                    )
                    field("Version", "v2.0.0", true) // TODO make this a constant somewhere
                    field("Library", "JDA ${JDAInfo.VERSION}", true)
                    field("Our support server", "https://discord.gg/23RB2Wc", true)
                }
            ).queue()
        }
    }

    object AvatarCommand : Command("avatar") {
        override suspend fun executeDefault(context: CommandContext) {
            val selectedMember = context.memberSelectionBuilder(context.commandContent)
                .title("Profile Selection")
                .execute() ?: return
            context.reply(embed {
                title("Astolfo » Profile Pictures", selectedMember.user.avatarUrl)
                description("${selectedMember.asMention} Profile Picture!")
                image(selectedMember.user.effectiveAvatarUrl)
            }).queue()
        }
    }

    object LinksCommand : Command("links") {
        override suspend fun executeDefault(context: CommandContext) {
            context.reply(embed {
                title("Astolfo » Useful links")
                description(
                    "**Bot's Website**:   https://astolfo.xyz/" +
                        "\n**GitHub**:                https://www.github.com/theprimedtnt/astolfo-community" +
                        "\n**Commands**:        https://astolfo.xyz/commands" +
                        "\n**Support Server**: https://astolfo.xyz/support" +
                        "\n**Donate**:                https://astolfo.xyz/donate" +
                        "\n**Invite Astolfo**:    https://astolfo.xyz/invite"
                )
            }).queue()
        }
    }

    object UserCountCommand : Command("usercount") {
        override suspend fun executeDefault(context: CommandContext) {
            context.reply(
                embed("There are **${context.event.message.guild.members.size}** members in this guild.")
            ).queue()
        }
    }

    object DonateCommand : Command("donate") {
        override suspend fun executeDefault(context: CommandContext) {
            context.reply(embed {
                title("As Astolfo grows, it needs to upgrade its servers.")
                field(
                    "PS: You get rewards and perks for donating!",
                    "Donate here: https://www.patreon.com/theprimedtnt",
                    false
                )
            }).queue()
        }
    }

    object HelpCommand : Command("help") {
        override suspend fun executeDefault(context: CommandContext) {
            val privateChannel: PrivateChannel = context.executor.openPrivateChannel().await()

            privateChannel.sendMessage(embed {
                title("Astolfo CommandData Help")
                description(
                    "If you're having  trouble with anything, you can always stop by our support server!" +
                        "\nInvite Link: https://discord.gg/23RB2Wc"
                )
                for (module in modules) {
                    if (module.type == Type.ADMIN || (module.type == Type.NSFW && !context.event.channel.isNSFW)) continue
                    val commandNames = module.children.joinToString(" ") { "`${it.name}` " }
                    field("${module.name} Commands", commandNames, false)
                }
            }).await()

            context.reply(embed(":mailbox: I have private messaged you a list of commands!")).queue()
        }
    }
}