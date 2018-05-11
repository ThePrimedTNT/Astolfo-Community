package xyz.astolfo.astolfocommunity

import net.dv8tion.jda.core.Permission

fun createAdminModule() = module("Admin") {
    command("settings") {
        inheritedAction {
            if (!event.member.hasPermission(Permission.ADMINISTRATOR)) {
                messageAction(embed("You must be a server admin in order to change settings!")).queue()
                return@inheritedAction false
            }
            true
        }
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
    command("prune", "purge", "delete") {
        action {
            val amountToDelete = args.takeIf { it.isNotBlank() }?.let {
                val amountNum = it.toBigIntegerOrNull()?.toInt()
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
            event.textChannel.deleteMessages(messages).queue()

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
}