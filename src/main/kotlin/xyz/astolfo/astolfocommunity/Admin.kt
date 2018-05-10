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
}