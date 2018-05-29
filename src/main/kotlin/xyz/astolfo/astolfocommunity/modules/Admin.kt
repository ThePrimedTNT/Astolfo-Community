package xyz.astolfo.astolfocommunity.modules

import com.jagrosh.jdautilities.commons.utils.FinderUtil
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Role
import net.dv8tion.jda.core.entities.TextChannel
import xyz.astolfo.astolfocommunity.*

fun createAdminModule() = module("Admin") {
    command("settings") {
        permission(Permission.ADMINISTRATOR)
        action {
            messageAction(embed {
                title("Astolfo Guild Settings")
                description("**prefix**  -  prefix of the bot in the guild")
            }).queue()
        }
        command("prefix") {
            action {
                val data = application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong)
                if (args.isBlank()) {
                    messageAction(embed {
                        title("Astolfo Guild Settings - Prefix")
                        description("Current Prefix: `${data.prefix.takeIf { it.isNotBlank() }
                                ?: application.properties.default_prefix}`")
                    }).queue()
                } else {
                    val oldPrefix = data.prefix
                    if (args.equals("reset", true)) data.prefix = ""
                    else data.prefix = args

                    application.astolfoRepositories.guildSettingsRepository.save(data)

                    messageAction(embed {
                        title("Astolfo Guild Settings - Prefix")
                        description("Old Prefix: `${oldPrefix.takeIf { it.isNotBlank() }
                                ?: application.properties.default_prefix}`" +
                                "\nNew Prefix: `${data.prefix.takeIf { it.isNotBlank() }
                                        ?: application.properties.default_prefix}`")
                    }).queue()
                }
            }
        }
    }
    command("permissions") {
        permission(Permission.ADMINISTRATOR)

        fun CommandExecution.parseScope(args: String, includePermission: Boolean): Triple<Role, TextChannel?, String>? {
            val (scopeIn, afterScope) = args.splitFirst(" ")
            val channelScope = scopeIn.let {
                if (it.isBlank()) {
                    messageAction("Please provide a scope.").queue()
                    return null
                }
                return@let when (it.toLowerCase()) {
                    "guild" -> false
                    "channel" -> true
                    else -> {
                        messageAction("Unknown scope! Valid scopes: **guild**,**channel**").queue()
                        null
                    }
                }
            } ?: return null

            val (channel, afterScopeData) = if (channelScope) {
                val (channelIn, afterScopeData) = afterScope.splitFirst(" ")

                val channel: TextChannel = channelIn.let {
                    if (it.isBlank()) {
                        messageAction("Provide a channel for the scope.").queue()
                        return null
                    }
                    // TODO: open up a menu for channel selection
                    val foundChannel = FinderUtil.findTextChannels(it, event.guild).firstOrNull()
                    if (foundChannel == null) {
                        messageAction("Unknown text channel.").queue()
                        return null
                    }
                    foundChannel
                }

                channel to afterScopeData
            } else {
                null to afterScope
            }

            val (permission, afterPermission) = if (includePermission) {
                val (permission, afterPermission) = afterScopeData.splitFirst(" ")

                if (permission.isBlank()) {
                    messageAction("Provide a permission.").queue()
                    return null
                }
                permission to afterPermission
            } else "" to afterScopeData

            val (roleIn, _) = afterPermission.splitFirst(" ")

            val role: Role = roleIn.let {
                if (it.isBlank()) {
                    messageAction("Provide a role for the permission.").queue()
                    return null
                }
                // TODO: open up a menu for role selection
                val foundRole = FinderUtil.findRoles(it, event.guild).firstOrNull()
                if (foundRole == null) {
                    messageAction("Unknown role.").queue()
                    return null
                }
                foundRole
            }
            return Triple(role, channel, permission)
        }

        command("grant") {
            usage("channel <channel> <permission> <role>", "guild <permission> <role>")
            description("Grants permission to a role in a guild/channel")
            action {
                val (role, channel, permission) = parseScope(args, true) ?: return@action

                val guildSettings = application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong)
                val permissions = guildSettings.permissions.toMutableMap()
                permissions[PermissionSetting(role.idLong, channel?.idLong ?: 0, permission)] = true
                guildSettings.permissions = permissions
                application.astolfoRepositories.guildSettingsRepository.save(guildSettings)
                messageAction("You have granted the permission **$permission** to the role **${role.name.let { if (it.startsWith("@")) it.substring(1) else it }}** under the ${if (channel == null) "guild" else "channel ${channel.asMention}"} scope").queue()
            }
        }

        command("deny") {
            usage("channel <channel> <permission> <role>", "guild <permission> <role>")
            description("Denies permission to a role in a guild/channel")
            action {
                val (role, channel, permission) = parseScope(args, true) ?: return@action

                val guildSettings = application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong)
                val permissions = guildSettings.permissions.toMutableMap()
                permissions[PermissionSetting(role.idLong, channel?.idLong ?: 0, permission)] = false
                guildSettings.permissions = permissions
                application.astolfoRepositories.guildSettingsRepository.save(guildSettings)
                messageAction("You have denied the permission **$permission** to the role **${role.name.let { if (it.startsWith("@")) it.substring(1) else it }}** under the ${if (channel == null) "guild" else "channel ${channel.asMention}"} scope").queue()
            }
        }

        command("reset") {
            usage("channel <channel> <permission> <role>", "guild <permission> <role>")
            description("Defaults permission to a role in a guild/channel")
            action {
                val (role, channel, permission) = parseScope(args, true) ?: return@action

                val guildSettings = application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong)
                val permissions = guildSettings.permissions.toMutableMap()
                permissions.remove(PermissionSetting(role.idLong, channel?.idLong ?: 0, permission))
                guildSettings.permissions = permissions
                application.astolfoRepositories.guildSettingsRepository.save(guildSettings)
                messageAction("You have defaulted the permission **$permission** to the role **${role.name.let { if (it.startsWith("@")) it.substring(1) else it }}** under the ${if (channel == null) "guild" else "channel ${channel.asMention}"} scope").queue()
            }
        }

        command("info") {
            usage("channel <channel> <role>", "guild <role>")
            description("Displays info about a role in a guild/channel scope")
            action {
                val (role, channel, _) = parseScope(args, false) ?: return@action

                val guildSettings = application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong)
                val permissions = guildSettings.permissions.filter {
                    if (it.key.role != role.idLong) false
                    else if (channel != null) it.key.channel == channel.idLong
                    else true
                }

                paginator("Permission Info - ${if (channel != null) "channel/${channel.name}" else "guild"} - ${role.name}") {
                    provider(10, permissions.map { entry ->
                        val setting = entry.key
                        val allow = entry.value
                        "${if (allow) "✅" else "❌"} ${setting.node}"
                    })
                }
            }
        }

    }
    command("prune", "purge", "delete") {
        permission(Permission.MESSAGE_MANAGE)
        action {
            val amountToDelete = args.takeIf { it.isNotBlank() }?.let {
                val amountNum = it.toIntOrNull()
                if (amountNum == null) {
                    messageAction("The amount to delete must be a whole number!").queue()
                    return@action
                }
                if (amountNum < 1) {
                    messageAction("The amount to delete must be at least 1!").queue()
                    return@action
                }
                if (amountNum > 100) {
                    messageAction("The amount to delete must be no more than 100!").queue()
                    return@action
                }
                amountNum
            } ?: 2
            val messages = event.textChannel.history.retrievePast(amountToDelete).complete()
            try {
                event.textChannel.deleteMessages(messages).queue()
            } catch (e: Exception) {
                messageAction("You cannot delete messages that are more than 2 weeks old!").queue()
                return@action
            }

            val authors = messages.map { it.author!! }.toSet()
            val nameLength = authors.map { it.name.length }.max()!!
            val messageCounts = authors.map { author -> author to messages.filter { it.author.idLong == author.idLong }.count() }.toMap()

            messageAction(embed {
                title("Astolfo Bot Prune")
                description("${event.message.author.asMention} has pruned the chat! Here are the results:")
                field("Total Messages Deleted:", "```$amountToDelete```", false)
                field("Messages Deleted:", "```Prolog" +
                        "\n${messageCounts.map { entry -> "${entry.key.name.padStart(nameLength)} : ${entry.value}" }.joinToString("\n")}" +
                        "\n```", false)
            }).queue()
        }
    }
    command("kick") {
        permission(Permission.KICK_MEMBERS)
        action {
            if (!event.guild.selfMember.hasPermission(Permission.KICK_MEMBERS)) {
                messageAction(embed("I need the `Kick Members` permission in order to kick people!")).queue()
                return@action
            }
            val query: String
            val reason: String
            if (args.contains(" ")) {
                query = args.substringBefore(" ").trim()
                reason = args.substringAfter(" ").trim()
            } else {
                query = args
                reason = ""
            }
            selectMember("Kick Selection", query) { selectedMember ->
                if (!event.guild.selfMember.canInteract(selectedMember)) {
                    messageAction("I cannot kick that member!").queue()
                    return@selectMember
                }
                val guildController = event.guild.controller
                val userString = "**${selectedMember.effectiveName}** (**${selectedMember.user.name}#${selectedMember.user.discriminator} ${selectedMember.user.id}**)"
                if (reason.isBlank()) {
                    guildController.kick(selectedMember).queue()
                    messageAction(embed("User $userString has been kicked!")).queue()
                } else {
                    guildController.kick(selectedMember, reason).queue()
                    messageAction(embed("User $userString has been kicked with reason **$reason**!")).queue()
                }
            }
        }
    }
    command("ban") {
        permission(Permission.BAN_MEMBERS)
        action {
            if (!event.guild.selfMember.hasPermission(Permission.BAN_MEMBERS)) {
                messageAction(embed("I need the `Ban Members` permission in order to ban people!")).queue()
                return@action
            }
            val query: String
            val reason: String
            if (args.contains(" ")) {
                query = args.substringBefore(" ").trim()
                reason = args.substringAfter(" ").trim()
            } else {
                query = args
                reason = ""
            }
            selectMember("Ban Selection", query) { selectedMember ->
                if (!event.guild.selfMember.canInteract(selectedMember)) {
                    messageAction("I cannot ban that member!").queue()
                    return@selectMember
                }
                val guildController = event.guild.controller
                val userString = "**${selectedMember.effectiveName}** (**${selectedMember.user.name}#${selectedMember.user.discriminator} ${selectedMember.user.id}**)"
                if (reason.isBlank()) {
                    guildController.ban(selectedMember, 0).queue()
                    messageAction(embed("User $userString has been banned!")).queue()
                } else {
                    guildController.ban(selectedMember, 0, reason).queue()
                    messageAction(embed("User $userString has been banned with reason **$reason**!")).queue()
                }
            }
        }
    }
}