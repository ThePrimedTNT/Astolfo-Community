package xyz.astolfo.astolfocommunity.modules

import xyz.astolfo.astolfocommunity.RadioEntry
import xyz.astolfo.astolfocommunity.menus.paginator
import xyz.astolfo.astolfocommunity.menus.provider
import xyz.astolfo.astolfocommunity.messages.description
import xyz.astolfo.astolfocommunity.messages.embed
import xyz.astolfo.astolfocommunity.messages.errorEmbed
import java.net.MalformedURLException
import java.net.URL

fun createStaffModule() = module("Developer", hidden = true) {
    command("dev") {
        inheritedAction {
            if (!application.staffMemberIds.contains(event.author.idLong)) {
                reply(errorEmbed("You're not allowed to use developer commands, please contact a staff member if you want to use them!")).queue()
                false
            } else true
        }
        action {
            reply(embed {
                description(
                    "addRadio [url] [name] - Adds a radio to the database\n" +
                        "removeRadio [id] - Removes a radio from the database"
                )
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
                if (commandContent.contains(" ")) {
                    urlString = commandContent.substringBefore(" ").trim()
                    name = commandContent.substringAfter(" ").trim()
                } else {
                    urlString = commandContent
                    name = ""
                }
                try {
                    URL(urlString)
                } catch (e: MalformedURLException) {
                    reply(errorEmbed("That's not a valid url!")).queue()
                    return@action
                }
                if (name.isBlank()) {
                    reply(errorEmbed("Please give the radio station a name!")).queue()
                    return@action
                }
                val radioEntry =
                    application.astolfoRepositories.radioRepository.save(RadioEntry(name = name, url = urlString))
                reply(embed("Radio station #${radioEntry.id!!} **${radioEntry.name}** has been added!")).queue()
            }
        }
        command("removeRadio") {
            action {
                application.astolfoRepositories.radioRepository.deleteById(commandContent.toLong())
                reply(embed("Deleted!")).queue()
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
            command("give") {
                action {
                    application.donationManager.give(event.member.user.idLong)
                    reply(embed("Done!")).queue()
                }
            }
            command("take") {
                action {
                    application.donationManager.remove(event.member.user.idLong)
                    reply(embed("Done!")).queue()
                }
            }
        }
    }
}