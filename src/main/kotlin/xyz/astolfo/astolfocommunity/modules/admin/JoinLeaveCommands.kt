package xyz.astolfo.astolfocommunity.modules.admin

import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.events.guild.member.GuildMemberJoinEvent
import net.dv8tion.jda.core.events.guild.member.GuildMemberLeaveEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.JoinLeaveSetting
import xyz.astolfo.astolfocommunity.commands.CommandExecution
import xyz.astolfo.astolfocommunity.menus.textChannelSelectionBuilder
import xyz.astolfo.astolfocommunity.messages.description
import xyz.astolfo.astolfocommunity.messages.embed
import xyz.astolfo.astolfocommunity.messages.field
import xyz.astolfo.astolfocommunity.messages.title
import xyz.astolfo.astolfocommunity.modules.ModuleBuilder

fun ModuleBuilder.createJoinLeaveCommands() {
    createCommand(true)
    createCommand(false)
}

private fun ModuleBuilder.createCommand(join: Boolean) = command(if (join) "joinmessage" else "leavemessage") {
    permission(Permission.MANAGE_SERVER)
    command("enable") {
        description("Enables the ${if (join) "Join" else "Leave"} message")
        action {
            withJoinLeaveSetting(join) { it.enabled = true }
            messageAction("The ${if (join) "Join" else "Leave"} message has been enabled!").queue()
        }
    }
    command("disable") {
        description("Disables the ${if (join) "Join" else "Leave"} message")
        action {
            withJoinLeaveSetting(join) { it.enabled = false }
            messageAction("The ${if (join) "Join" else "Leave"} message has been disabled!").queue()
        }
    }
    command("info") {
        description("Information about the ${if (join) "Join" else "Leave"} message")
        action {
            withJoinLeaveSetting(join) { setting ->
                messageAction(embed {
                    title("${if (join) "Join" else "Leave"} Message Info")
                    field("State", if (setting.enabled) "\u2705 Enabled" else "\u274C Disabled", true)
                    field("Announce Channel", this@action.event.guild.getTextChannelById(setting.channelId)?.asMention
                            ?: "None", true)
                    field("Message", "```\n${setting.effectiveMessage(join)}\n```", false)
                }).queue()
            }
        }
    }
    command("placeholders") {
        description("Lists the valid placeholders for the message.")
        action {
            messageAction(embed {
                title("${if (join) "Join" else "Leave"} Placeholder Info")
                this@embed.description("List of placeholders you can use in your ${if (join) "join" else "leave"} message. The bot will automatically replace them with the requested information.\n" +
                        "```\n${JoinLeaveManager.Placeholder.values().joinToString(separator = "\n") { "${it.pattern} - ${it.description}" }}\n```")
            }).queue()
        }
    }
    command("settext") {
        description("Sets/resets the ${if (join) "Join" else "Leave"} message")
        action {
            withJoinLeaveSetting(join) {
                it.message = args
                messageAction("The ${if (join) "Join" else "Leave"} message has been changed to: ```\n${it.effectiveMessage(join)}\n```").queue()
            }
        }
    }
    command("setchannel") {
        description("Changes the channel the ${if (join) "Join" else "Leave"} message is announced in")
        action {
            val channel = textChannelSelectionBuilder(args).execute() ?: return@action
            withJoinLeaveSetting(join) { it.channelId = channel.idLong }
            messageAction("The announce channel for ${if (join) "Join" else "Leave"} message has been changed to: **${channel.asMention}**").queue()
        }
    }
}

class JoinLeaveManager(val application: AstolfoCommunityApplication) {

    val listener = object : ListenerAdapter() {
        override fun onGuildMemberJoin(event: GuildMemberJoinEvent) = processEvent(event.guild, event.member, true)
        override fun onGuildMemberLeave(event: GuildMemberLeaveEvent) = processEvent(event.guild, event.member, false)
    }

    private fun processEvent(guild: Guild, member: Member, join: Boolean) {
        val setting = getSetting(guild, join)
        if (!setting.enabled) return
        val textChannel = guild.getTextChannelById(setting.channelId) ?: return
        if(!textChannel.canTalk()) return
        val message = setting.effectiveMessage(join).takeIf { it.isNotBlank() } ?: return
        val processedMessage = processMessage(guild, member, message)
        textChannel.sendMessage(processedMessage).queue()
    }

    private fun processMessage(guild: Guild, member: Member, message: String): String {
        @Suppress("LocalVariableName")
        var message_ = message
        message_ = Placeholder.MENTION.processPlaceholder(message_, member.asMention)
        message_ = Placeholder.USERNAME.processPlaceholder(message_, member.effectiveName)
        message_ = Placeholder.USER_COUNT.processPlaceholder(message_, guild.members.size.toString())
        return message_
    }

    private fun getSetting(guild: Guild, join: Boolean): JoinLeaveSetting {
        val guildSettings = application.astolfoRepositories.getEffectiveGuildSettings(guild.idLong)
        return guildSettings.joinLeaveMessage.getOrDefault(join, JoinLeaveSetting())
    }

    enum class Placeholder(pattern: String, val description: String) {
        USERNAME("username", "Username of the user"),
        MENTION("mention", "Mention of the user"),
        USER_COUNT("usercount", "The amount of users in the guild");

        val pattern = "%$pattern%"
        private val regex = Regex(this.pattern)

        fun processPlaceholder(message: String, value: String) = regex.replace(message, value)
    }

}

fun JoinLeaveSetting.effectiveMessage(join: Boolean): String {
    if (message.isNotBlank()) return message
    return if (join) "%mention% has joined the guild!" else "%mention% has left the guild!"
}

private suspend fun <E> CommandExecution.withJoinLeaveSetting(join: Boolean, block: suspend (JoinLeaveSetting) -> E): E = withGuildSettings { guildSettings ->
    val joinLeaveMap = guildSettings.joinLeaveMessage.toMutableMap()
    val joinLeaveSetting = joinLeaveMap.getOrDefault(join, JoinLeaveSetting())
    val result = block(joinLeaveSetting)
    joinLeaveMap[join] = joinLeaveSetting
    guildSettings.joinLeaveMessage = joinLeaveMap
    result
}