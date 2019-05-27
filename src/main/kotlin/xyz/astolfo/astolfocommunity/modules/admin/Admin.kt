package xyz.astolfo.astolfocommunity.modules.admin

import net.dv8tion.jda.core.Permission
import xyz.astolfo.astolfocommunity.menus.memberSelectionBuilder
import xyz.astolfo.astolfocommunity.messages.*
import xyz.astolfo.astolfocommunity.modules.module

fun createAdminModule() = module("Admin") {
    createJoinLeaveCommands()
    settingsCommand()
//    permissionCommand()
    command("prune", "purge", "delete") {
        //        permission(Permission.MESSAGE_MANAGE)
        action {
            val amountToDelete = commandContent.takeIf { it.isNotBlank() }?.let {
                val amountNum = it.toIntOrNull()
                if (amountNum == null) {
                    reply(errorEmbed("The amount to delete must be a whole number!")).queue()
                    return@action
                }
                if (amountNum < 1) {
                    reply(errorEmbed("The amount to delete must be at least 1!")).queue()
                    return@action
                }
                if (amountNum > 100) {
                    reply(errorEmbed("The amount to delete must be no more than 100!")).queue()
                    return@action
                }
                amountNum
            } ?: 2
            val messages = event.channel.history.retrievePast(amountToDelete).complete()
            try {
                event.channel.deleteMessages(messages).queue()
            } catch (e: Exception) {
                reply(errorEmbed("You cannot delete messages that are more than 2 weeks old!")).queue()
                return@action
            }

            val authors = messages.map { it.author!! }.toSet()
            val nameLength = authors.map { it.name.length }.max()!!
            val messageCounts =
                authors.map { author -> author to messages.filter { it.author.idLong == author.idLong }.count() }
                    .toMap()

            reply(embed {
                title("Astolfo Bot Prune")
                description("${event.message.author.asMention} has pruned the chat! Here are the results:")
                field("Total Messages Deleted:", "```$amountToDelete```", false)
                field(
                    "Messages Deleted:", "```Prolog" +
                        "\n${messageCounts.map { entry -> "${entry.key.name.padStart(nameLength)} : ${entry.value}" }.joinToString(
                            "\n"
                        )}" +
                        "\n```", false
                )
            }).queue()
        }
    }
    command("kick") {
        //        permission(Permission.KICK_MEMBERS)
        action {
            if (!event.guild.selfMember.hasPermission(Permission.KICK_MEMBERS)) {
                reply(embed("I need the `Kick Members` path in order to kick people!")).queue()
                return@action
            }
            val query: String
            val reason: String
            if (commandContent.contains(" ")) {
                query = commandContent.substringBefore(" ").trim()
                reason = commandContent.substringAfter(" ").trim()
            } else {
                query = commandContent
                reason = ""
            }
            val selectedMember = memberSelectionBuilder(query).title("Kick Selection").execute() ?: return@action
            if (!event.guild.selfMember.canInteract(selectedMember)) {
                reply(errorEmbed("I cannot kick that member!")).queue()
                return@action
            }
            val guildController = event.guild.controller
            val userString =
                "**${selectedMember.effectiveName}** (**${selectedMember.user.name}#${selectedMember.user.discriminator} ${selectedMember.user.id}**)"
            if (reason.isBlank()) {
                guildController.kick(selectedMember).queue()
                reply(embed("User $userString has been kicked!")).queue()
            } else {
                guildController.kick(selectedMember, reason).queue()
                reply(embed("User $userString has been kicked with reason **$reason**!")).queue()
            }
        }
    }
    command("ban") {
        //        permission(Permission.BAN_MEMBERS)
        action {
            if (!event.guild.selfMember.hasPermission(Permission.BAN_MEMBERS)) {
                reply(embed("I need the `Ban Members` path in order to ban people!")).queue()
                return@action
            }
            val query: String
            val reason: String
            if (commandContent.contains(" ")) {
                query = commandContent.substringBefore(" ").trim()
                reason = commandContent.substringAfter(" ").trim()
            } else {
                query = commandContent
                reason = ""
            }
            val selectedMember = memberSelectionBuilder(query).title("Ban Selection").execute() ?: return@action
            if (!event.guild.selfMember.canInteract(selectedMember)) {
                reply(errorEmbed("I cannot ban that member!")).queue()
                return@action
            }
            val guildController = event.guild.controller
            val userString =
                "**${selectedMember.effectiveName}** (**${selectedMember.user.name}#${selectedMember.user.discriminator} ${selectedMember.user.id}**)"
            if (reason.isBlank()) {
                guildController.ban(selectedMember, 0).queue()
                reply(embed("User $userString has been banned!")).queue()
            } else {
                guildController.ban(selectedMember, 0, reason).queue()
                reply(embed("User $userString has been banned with reason **$reason**!")).queue()
            }
        }
    }
}