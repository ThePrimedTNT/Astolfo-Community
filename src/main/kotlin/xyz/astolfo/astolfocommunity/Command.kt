package xyz.astolfo.astolfocommunity

import com.jagrosh.jdautilities.commons.utils.FinderUtil
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import java.util.concurrent.TimeUnit

class Command(
        val name: String,
        val alts: Array<out String>,
        val usage: List<String>,
        val description: String,
        val subCommands: List<Command>,
        val permission: AstolfoPermission,
        val inheritedAction: CommandExecution.() -> Boolean,
        val action: CommandExecution.() -> Unit
)

class CommandBuilder(val path: String, val name: String, private val alts: Array<out String>) {
    val subCommands = mutableListOf<Command>()
    var action: CommandExecution.() -> Unit = {
        val guildSettings = application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong)
        messageAction("Unknown command! Type **${guildSettings.prefix.takeIf { it.isNotBlank() }
                ?: application.properties.default_prefix}$commandPath help** for a list of commands.").queue()
    }
    var inheritedAction: CommandExecution.() -> Boolean = { true }
    var permission = AstolfoPermission(path, name)
    var usage = listOf<String>()
    var description = ""
    fun build(): Command {
        if (subCommands.isNotEmpty()) {
            command("help") {
                action {
                    val guildSettings = application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong)
                    messageAction(embed {
                        title("Astolfo ${this@CommandBuilder.name.capitalize()} Help")
                        val baseCommand = guildSettings.prefix.takeIf { it.isNotBlank() } ?: application.properties.default_prefix+commandPath.substringBeforeLast(" ").trim()
                        description(this@CommandBuilder.subCommands.joinToString(separator = "\n") { subCommand ->
                            val base = "$baseCommand **${subCommand.name}**${if (subCommand.description.isNotBlank()) " - ${subCommand.description}" else ""}"
                            if (subCommand.usage.isNotEmpty()) {
                                "$base\n" +
                                        "*Usages:*\n" + subCommand.usage.joinToString(separator = "\n") { usage -> "- *$usage*" } + "\n"
                            } else base
                        })
                    }).queue()
                }
            }
        }
        return Command(name, alts, usage, description, subCommands, permission, inheritedAction, action)
    }
}

fun CommandBuilder.command(subName: String, vararg alts: String, builder: CommandBuilder.() -> Unit) {
    val commandBuilder = CommandBuilder(this.path, subName, alts)
    builder.invoke(commandBuilder)
    subCommands.add(commandBuilder.build())
}

fun CommandBuilder.action(action: CommandExecution.() -> Unit) {
    this.action = action
}

fun CommandBuilder.inheritedAction(inheritedAction: CommandExecution.() -> Boolean) {
    this.inheritedAction = inheritedAction
}

fun CommandBuilder.permission(vararg permissionDefaults: Permission) {
    permission(name, *permissionDefaults)
}

fun CommandBuilder.description(description: String) {
    this.description = description
}

fun CommandBuilder.usage(vararg usage: String) {
    this.usage = usage.toList()
}

fun CommandBuilder.permission(node: String, vararg permissionDefaults: Permission) {
    permission = AstolfoPermission(path, node, *permissionDefaults)
}

open class CommandExecution(val application: AstolfoCommunityApplication, val event: MessageReceivedEvent, val commandPath: String, val args: String, val timeIssued: Long)

fun CommandExecution.messageAction(text: CharSequence) = event.channel.sendMessage(text)!!
fun CommandExecution.messageAction(embed: MessageEmbed) = event.channel.sendMessage(embed)!!
fun CommandExecution.messageAction(msg: Message) = event.channel.sendMessage(msg)!!

fun <T> CommandExecution.tempMessage(msg: Message, temp: () -> T): T {
    val messageAsync = messageAction(msg).sendAsync()
    val toReturn = temp.invoke()
    messageAsync.delete()
    return toReturn
}

fun CommandExecution.session() = application.commandHandler.commandSessionMap.get(CommandHandler.SessionKey(event.guild.idLong, event.author.idLong, event.channel.idLong), { CommandSession(commandPath) })!!
fun CommandExecution.updatable(rate: Long, unit: TimeUnit = TimeUnit.SECONDS, updater: (CommandSession) -> Unit) = session().updatable(rate, unit, updater)
fun CommandExecution.updatableMessage(rate: Long, unit: TimeUnit = TimeUnit.SECONDS, messageUpdater: () -> MessageEmbed) {
    val messageAsync = messageAction(messageUpdater.invoke()).sendAsync()
    updatable(rate, unit) {
        messageAsync.editMessage(messageUpdater.invoke())
    }
}

fun CommandExecution.responseListener(listener: ResponseListener.(CommandExecution) -> Boolean) = session().addResponseListener(listener)
fun CommandExecution.destroyListener(listener: () -> Unit) = session().addDestroyListener(listener)

fun CommandExecution.getProfile() = application.astolfoRepositories.getEffectiveUserProfile(event.author.idLong)

fun CommandExecution.selectMember(title: String, query: String, response: CommandExecution.(Member) -> Unit) {
    val results = FinderUtil.findMembers(query, event.guild)

    if (results.isEmpty()) {
        messageAction("Unknown Member!").queue()
        return
    }

    if (results.size == 1) {
        response.invoke(this, results.first())
        return
    }

    val menu = paginator(title) {
        provider(8, results.map { "**${it.effectiveName} (${it.user.name}#${it.user.discriminator})**" })
        renderer {
            message {
                embed {
                    titleProvider.invoke()?.let { title(it) }
                    description("Type the number of the member you want.\n$providedString")
                    footer("Page ${currentPage + 1}/${provider.pageCount}")
                }
            }
        }
    }
    // Waits for a follow up response for user selection
    responseListener {
        if (menu.isDestroyed) {
            removeListener()
            true
        } else if (it.args.matches("\\d+".toRegex())) {
            val numSelection = it.args.toBigInteger().toInt()
            if (numSelection < 1 || numSelection > results.size) {
                messageAction("Unknown Selection").queue()
                return@responseListener false
            }
            val selectedMember = results[numSelection - 1]
            response.invoke(this@selectMember, selectedMember)
            removeListener()
            menu.destroy()
            false // Don't run the command since member was selected
        } else {
            messageAction(embed { description("Please type the # of the member you want") }).queue()
            false // Still waiting for valid response
        }
    }
}

class CommandSession(val commandPath: String) {
    private var responseListeners = mutableListOf<ResponseListener.(CommandExecution) -> Boolean>()
    private var destroyListener = mutableListOf<() -> Unit>()
    private val jobs = mutableListOf<Job>()
    fun updatable(rate: Long, unit: TimeUnit = TimeUnit.SECONDS, updater: (CommandSession) -> Unit) {
        synchronized(jobs) {
            jobs.add(launch {
                while (isActive) {
                    updater.invoke(this@CommandSession)
                    delay(rate, unit)
                }
            })
        }
    }

    fun addDestroyListener(listener: () -> Unit) = destroyListener.add(listener)
    fun removeDestroyListener(listener: () -> Unit) = destroyListener.remove(listener)
    fun addResponseListener(listener: ResponseListener.(CommandExecution) -> Boolean) = responseListeners.add(listener)
    fun removeResponseListener(listener: ResponseListener.(CommandExecution) -> Boolean) = responseListeners.remove(listener)
    fun hasResponseListeners() = responseListeners.isNotEmpty()

    fun shouldRunCommand(execution: CommandExecution): Boolean {
        if (responseListeners.isEmpty()) return true
        return responseListeners.toList().any { it.invoke(ResponseListener(this, it), execution) }
    }

    fun destroy() {
        synchronized(jobs) {
            jobs.forEach { it.cancel() }
            jobs.clear()
            destroyListener.toList().forEach { it.invoke() }
        }
    }
}

class ResponseListener(val session: CommandSession, val listener: ResponseListener.(CommandExecution) -> Boolean) {
    fun removeListener() = session.removeResponseListener(listener)
}