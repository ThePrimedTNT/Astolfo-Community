package xyz.astolfo.astolfocommunity.modules

import xyz.astolfo.astolfocommunity.RadioEntry
import xyz.astolfo.astolfocommunity.menus.paginator
import xyz.astolfo.astolfocommunity.menus.provider
import xyz.astolfo.astolfocommunity.messages.description
import java.net.MalformedURLException
import java.net.URL

fun createStaffModule() = module("Developer", hidden = true) {
    command("dev") {
        inheritedAction {
            if (!application.staffMemberIds.contains(event.author.idLong)) {
                messageAction(errorEmbed("You're not allowed to use developer commands, please contact a staff member if you want to use them!")).queue()
                false
            } else true
        }
        action {
            messageAction(embed {
                description("addRadio [url] [name] - Adds a radio to the database\n" +
                        "removeRadio [id] - Removes a radio from the database")
            }).queue()
        }
        command("stop") {
            action {
                System.exit(0)
            }
        }
        command("addRadio") {
            action {
                val urlString: String
                val name: String
                if (args.contains(" ")) {
                    urlString = args.substringBefore(" ").trim()
                    name = args.substringAfter(" ").trim()
                } else {
                    urlString = args
                    name = ""
                }
                try {
                    URL(urlString)
                } catch (e: MalformedURLException) {
                    messageAction(errorEmbed("That's not a valid url!")).queue()
                    return@action
                }
                if (name.isBlank()) {
                    messageAction(errorEmbed("Please give the radio station a name!")).queue()
                    return@action
                }
                val radioEntry = application.astolfoRepositories.radioRepository.save(RadioEntry(name = name, url = urlString))
                messageAction(embed("Radio station #${radioEntry.id!!} **${radioEntry.name}** has been added!")).queue()
            }
        }
        command("removeRadio") {
            action {
                application.astolfoRepositories.radioRepository.deleteById(args.toLong())
                messageAction(embed("Deleted!")).queue()
            }
        }
        command("patreon") {
            action {
                paginator("Astolfo Patreon") {
                    provider(8, application.donationManager.entries().map {
                        val discordId = it.discord_id
                        val user = if (discordId != null) application.shardManager.getUserById(discordId) else null
                        val name = if (user != null) "${user.name}#${user.discriminator}" else "Unknown"
                        "$name *- ${it.supportLevel.rewardName}*"
                    })
                }
            }
        }
    }
}