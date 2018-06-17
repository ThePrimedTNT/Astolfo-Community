package xyz.astolfo.astolfocommunity.modules

import xyz.astolfo.astolfocommunity.RadioEntry
import xyz.astolfo.astolfocommunity.description
import xyz.astolfo.astolfocommunity.embed
import java.net.MalformedURLException
import java.net.URL

fun createStaffModule() = module("Developer", true) {
    command("staff") {
        inheritedAction {
            if (!application.staffMemberIds.contains(event.author.idLong)) {
                messageAction("You're not allowed to use staff commands, please contact a staff member if you want to use them!").queue()
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
                    messageAction("That's not a valid url!").queue()
                    return@action
                }
                if (name.isBlank()) {
                    messageAction("Please give the radio station a name!").queue()
                    return@action
                }
                val radioEntry = application.astolfoRepositories.radioRepository.save(RadioEntry(name = name, url = urlString))
                messageAction("Radio station #${radioEntry.id!!} **${radioEntry.name}** has been added!").queue()
            }
        }
        command("removeRadio") {
            action {
                application.astolfoRepositories.radioRepository.deleteById(args.toLong())
                messageAction("Deleted!").queue()
            }
        }
    }
}