package xyz.astolfo.astolfocommunity.commands

import net.dv8tion.jda.core.JDA
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.entities.User
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import net.dv8tion.jda.core.requests.restaction.MessageAction
import xyz.astolfo.astolfocommunity.*
import xyz.astolfo.astolfocommunity.messages.description
import xyz.astolfo.astolfocommunity.messages.embed
import xyz.astolfo.astolfocommunity.messages.errorEmbed
import xyz.astolfo.astolfocommunity.messages.title

class CommandBuilder(val path: String, val name: String, val alts: List<String>) {
    private val subCommands = mutableListOf<Command>()
    private var action: suspend CommandContext.() -> Unit = {
        val (commandName, commandContent) = commandContent.splitFirst(" ")

        val distances = subCommands.map { it.name }
            .map { it to it.levenshteinDistance(commandName, true) }.toMap()
        val bestMatch = distances.keys.sortedBy { distances[it] }.firstOrNull()
        reply(
            if (bestMatch == null) {
                errorEmbed("Unknown command! Type **$prefix$commandPath help** for a list of commands.")
            } else {
                val recreated = "$prefix$commandPath $bestMatch $commandContent".trim()
                errorEmbed("Unknown command! Did you mean **$recreated**?")
            }
        ).queue()
    }
    private var inheritedActions = mutableListOf<suspend CommandContext.() -> Boolean>()
    private var usage = listOf<String>()
    private var description = ""

    fun command(subName: String, vararg alts: String, builder: CommandBuilder.() -> Unit) = apply {
        val commandBuilder = CommandBuilder(this.path, subName, alts.toList())
        builder.invoke(commandBuilder)
        subCommands.add(commandBuilder.build())
    }

    fun action(action: suspend CommandContext.() -> Unit) = apply { this.action = action }
    fun inheritedAction(inheritedAction: suspend CommandContext.() -> Boolean) =
        apply { this.inheritedActions.add(inheritedAction) }

    fun description(description: String) = apply { this.description = description }
    fun usage(vararg usage: String) = apply { this.usage = usage.toList() }

    fun build(): Command {
        if (subCommands.isNotEmpty()) {
            command("help") {
                action {
                    reply(embed {
                        title("Astolfo ${this@CommandBuilder.name.capitalize()} Help")
                        val baseCommand = prefix + commandPath.substringBeforeLast(" ").trim()
                        description(
                            this@CommandBuilder.subCommands.filterNot { it.name == "help" }.joinToString(
                                separator = "\n"
                            ) { subCommand ->
                                val stringBuilder = StringBuffer("$baseCommand **${subCommand.name}**")
//                                if (subCommand.description.isNotBlank()) stringBuilder.append(" - ${subCommand.description}")
//                                if (subCommand.usage.isNotEmpty())
//                                    stringBuilder.append("\n*Usages:*\n" + subCommand.usage.joinToString(separator = "\n") { usage -> "- *$usage*" } + "\n")
                                stringBuilder.toString()
                            })
                    }).queue()
                }
            }
        }
//        return CommandData(name, alts, usage, description, subCommands, permission, inheritedActions, action)
        return object : Command(name) {

            override suspend fun executeDefault(context: CommandContext) {
                action(context)
            }

            override suspend fun executeInherited(context: CommandContext): Boolean {
                return super.executeInherited(context)
            }
        }
    }
}

typealias CommandArgs = String
typealias ArgsIterator = ListIterator<String>

//TODO add support for strings ?command "arg one here" "arg two here"
fun CommandArgs.argsIterator(): ArgsIterator = words().listIterator()

fun ArgsIterator.next(default: String) = if (hasNext()) next() else default

// Command structure

abstract class Command(
    val name: String
) {

    private val _children = mutableListOf<Command>()
    val children: List<Command> = _children
    var parent: Command? = null
        private set

//    var permission = CommandPermission(name, emptyList())
//        private set

    suspend fun execute(context: CommandContext) {
        if (!executeInherited(context)) return

        val (commandName, commandContent) = context.commandContent.splitFirst(" ")

        val subCommand = _children.findByName(commandName)

        if (subCommand != null) {
            // TODO add permissions support

            subCommand.execute(
                CommandContext(
                    context.application,
                    "${context.commandPath} $commandName".trim(),
                    commandContent.trim(),
                    context.data
                )
            )
        } else {
            executeDefault(context)
        }
    }

    protected open suspend fun executeInherited(context: CommandContext): Boolean = true
    protected open suspend fun executeDefault(context: CommandContext) {
        context.reply(
            if (_children.isEmpty()) {
                errorEmbed("Oops! It appears this command doesn't have any children or default condition!")
            } else {
                errorEmbed("That's an unknown command! For a list of valid commands type **${context.commandPath} help**")
            }
        ).queue()
    }

}

fun Iterable<Command>.findByName(name: String) =
    find { command ->
        command.name.equals(name, true)
        // TODO check alts
    }

data class CommandPermission(
    val path: String,
    val discordOverrides: List<Permission>
)

class CommandContext(
    val application: AstolfoCommunityApplication,
    val commandPath: String,
    val commandContent: String,
    val data: Map<String, Any>
) {

    val prefix get() = data[DATA_KEY_PREFIX] as String
    val event get() = data[DATA_KEY_EVENT] as GuildMessageReceivedEvent
    val timeIssued get() = data[DATA_KEY_ISSUED] as Long

    val jda: JDA get() = event.jda
    val executor: User get() = event.author

    fun reply(message: String): MessageAction = event.channel.sendMessage(message)
    fun reply(message: Message) = event.channel.sendMessage(message)
    fun reply(messageEmbed: MessageEmbed): MessageAction = event.channel.sendMessage(messageEmbed)

    @Deprecated("This will be replaced")
    fun getProfile() = application.astolfoRepositories.getEffectiveUserProfile(event.author.idLong)

    @Deprecated("This will be replaced")
    fun getGuildSettings() = application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong)

    @Deprecated("This will be replaced")
    fun setGuildSettings(guildSettings: GuildSettings) =
        application.astolfoRepositories.guildSettingsRepository.save(guildSettings)

    @Deprecated("This will be replaced")
    inline fun <E> withGuildSettings(block: (GuildSettings) -> E): E {
        val guildSettings = getGuildSettings()
        val result = block.invoke(guildSettings)
        setGuildSettings(guildSettings)
        return result
    }

    suspend inline fun <R> tempMessage(messageAction: MessageAction, block: () -> R): R {
        val message = messageAction.await()
        return try {
            block()
        } finally {
            message.delete().queue()
        }
    }

    companion object {
        const val DATA_KEY_PREFIX = "prefix"
        const val DATA_KEY_EVENT = "event"
        const val DATA_KEY_ISSUED = "issued"
    }

}