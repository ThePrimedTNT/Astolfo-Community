package xyz.astolfo.astolfocommunity.commands

import kotlinx.coroutines.*
import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import xyz.astolfo.astolfocommunity.*
import xyz.astolfo.astolfocommunity.messages.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class Command(
        val name: String,
        val alts: List<String>,
        val usage: List<String>,
        val description: String,
        val subCommands: List<Command>,
        val permission: AstolfoPermission,
        val inheritedActions: List<suspend CommandExecution.() -> Boolean>,
        val action: suspend CommandExecution.() -> Unit
)

class CommandBuilder(val path: String, val name: String, val alts: List<String>) {
    private val subCommands = mutableListOf<Command>()
    private var action: suspend CommandExecution.() -> Unit = {
        val (commandName, commandContent) = args.splitFirst(" ")

        val distances = subCommands.map { listOf(it.name, *it.alts.toTypedArray()) }.flatten()
                .map { it to it.levenshteinDistance(commandName, true) }.toMap()
        val bestMatch = distances.keys.sortedBy { distances[it]!! }.firstOrNull()
        val guildPrefix = getGuildSettings().getEffectiveGuildPrefix(application)
        if (bestMatch == null) {
            messageAction(errorEmbed("Unknown command! Type **$guildPrefix$commandPath help** for a list of commands.")).queue()
        } else {
            val recreated = "$guildPrefix$commandPath $bestMatch $commandContent".trim()
            messageAction(errorEmbed("Unknown command! Did you mean **$recreated**?")).queue()
        }
    }
    private var inheritedActions = mutableListOf<suspend CommandExecution.() -> Boolean>()
    private var permission = AstolfoPermission(path, name)
    private var usage = listOf<String>()
    private var description = ""

    fun command(subName: String, vararg alts: String, builder: CommandBuilder.() -> Unit) = apply {
        val commandBuilder = CommandBuilder(this.path, subName, alts.toList())
        builder.invoke(commandBuilder)
        subCommands.add(commandBuilder.build())
    }

    fun action(action: suspend CommandExecution.() -> Unit) = apply { this.action = action }
    fun inheritedAction(inheritedAction: suspend CommandExecution.() -> Boolean) = apply { this.inheritedActions.add(inheritedAction) }
    fun permission(vararg permissionDefaults: Permission) = apply { permission(name, *permissionDefaults) }
    fun description(description: String) = apply { this.description = description }
    fun usage(vararg usage: String) = apply { this.usage = usage.toList() }
    fun permission(node: String, vararg permissionDefaults: Permission) = apply { permission = AstolfoPermission(path, node, *permissionDefaults) }

    // Stage based Actions
    inline fun <reified E> stageActions(block: StageAction<E>.() -> Unit) {
        val clazz = E::class.java
        val stageAction = StageAction { clazz.newInstance()!! }
        block(stageAction)
        action { stageAction.execute(this) }
    }

    fun build(): Command {
        if (subCommands.isNotEmpty()) {
            command("help") {
                action {
                    val guildPrefix = getGuildSettings().getEffectiveGuildPrefix(application)
                    messageAction(embed {
                        title("Astolfo ${this@CommandBuilder.name.capitalize()} Help")
                        val baseCommand = guildPrefix + commandPath.substringBeforeLast(" ").trim()
                        description(this@CommandBuilder.subCommands.filterNot { it.name == "help" }.joinToString(separator = "\n") { subCommand ->
                            val stringBuilder = StringBuffer("$baseCommand **${subCommand.name}**")
                            if (subCommand.description.isNotBlank()) stringBuilder.append(" - ${subCommand.description}")
                            if (subCommand.usage.isNotEmpty())
                                stringBuilder.append("\n*Usages:*\n" + subCommand.usage.joinToString(separator = "\n") { usage -> "- *$usage*" } + "\n")
                            stringBuilder.toString()
                        })
                    }).queue()
                }
            }
        }
        return Command(name, alts, usage, description, subCommands, permission, inheritedActions, action)
    }
}

open class CommandExecution(
        val application: AstolfoCommunityApplication,
        val event: GuildMessageReceivedEvent,
        val session: CommandSession,
        val commandPath: String,
        val args: CommandArgs,
        val timeIssued: Long
) {
    @Deprecated("Create a message and then send it instead", ReplaceWith("messageAction(message(text))", "xyz.astolfo.astolfocommunity.messages.message"))
    fun messageAction(text: String) = messageAction(message(text))

    fun messageAction(embed: MessageEmbed) = event.channel.sendMessage(embed)!!
    fun messageAction(msg: Message) = event.channel.sendMessage(msg)!!

    suspend fun <T> tempMessage(embed: MessageEmbed, temp: suspend () -> T) = tempMessage(message { setEmbed(embed) }, temp)
    suspend fun <T> tempMessage(msg: Message, temp: suspend () -> T): T {
        val message = messageAction(msg).sendCached()
        val job = GlobalScope.async { temp() }
        val dispose = {
            synchronized(message) {
                if (message.isDeleted) return@synchronized // discard if already deleted
                message.delete()
            }
        }
        return suspendCancellableCoroutine { cont ->
            // Clean up and resume when the temp is finished
            val handle = job.invokeOnCompletion { t ->
                dispose()
                if (t == null) cont.resume(job.getCompleted())
                else cont.resumeWithException(t)
            }
            // Clean up if this gets cancelled
            cont.invokeOnCancellation {
                dispose()
                handle.dispose()
            }
        }
    }

    fun updatable(rate: Long, unit: TimeUnit = TimeUnit.SECONDS, updater: (CommandSession) -> Unit) = session.updatable(rate, unit, updater)

    inline fun responseListener(crossinline listener: CommandExecution.() -> CommandSession.ResponseAction) =
            listener(messageListener = listener)

    @Deprecated("Use suspending functions instead", ReplaceWith("listener(destroyListener = listener)"))
    inline fun destroyListener(crossinline listener: () -> Unit) = listener(destroyListener = listener)

    inline fun listener(
            crossinline messageListener: CommandExecution.() -> CommandSession.ResponseAction = { CommandSession.ResponseAction.NOTHING },
            crossinline destroyListener: () -> Unit = {}) = session.addListener(object : CommandSession.SessionListener() {
        override fun onMessageReceived(execution: CommandExecution) = messageListener(execution)
        override fun onSessionDestroyed() = destroyListener()
    })

    // TODO this seems odd to have here
    suspend fun getProfile() = withContext(Dispatchers.Default) { application.astolfoRepositories.getEffectiveUserProfile(event.author.idLong) }

    suspend fun getGuildSettings() = withContext(Dispatchers.Default) { application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong) }
    suspend fun setGuildSettings(guildSettings: GuildSettings) =
            withContext(Dispatchers.Default) { application.astolfoRepositories.guildSettingsRepository.save(guildSettings) }

    suspend inline fun <E> withGuildSettings(block: (GuildSettings) -> E): E {
        val guildSettings = getGuildSettings()
        val result = block.invoke(guildSettings)
        setGuildSettings(guildSettings)
        return result
    }

    fun embed(text: String) = embed { description(text) }
    inline fun embed(crossinline builder: EmbedBuilder.() -> Unit) = embed0 {
        footer("Requested by ${event.author.name}")
        builder(this)
    }

    fun errorEmbed(text: String) = errorEmbed { description(text) }
    inline fun errorEmbed(crossinline builder: EmbedBuilder.() -> Unit) = errorEmbed0 {
        footer("Requested by ${event.author.name}")
        builder(this)
    }

}

class StageAction<E>(private val newData: () -> E) {
    private val actions = mutableListOf<StageActionEntry<E>>()

    fun basicAction(block: suspend CommandExecution.(E) -> Unit) = action {
        block(it)
        true
    }

    fun action(block: suspend CommandExecution.(E) -> Boolean) = actions.add(StageActionEntry(block))

    suspend fun execute(event: CommandExecution) {
        val data = newData.invoke()
        for (action in actions) {
            val response = action.block.invoke(event, data)
            if (!response) break
        }
    }

    class StageActionEntry<E>(val block: suspend CommandExecution.(E) -> Boolean)
}

typealias CommandArgs = String
typealias ArgsIterator = ListIterator<String>

//TODO add support for strings ?command "arg one here" "arg two here"
fun CommandArgs.argsIterator(): ArgsIterator = words().listIterator()

fun ArgsIterator.next(default: String) = if (hasNext()) next() else default

fun Iterable<Command>.findByName(name: String) = find { it.name.equals(name, ignoreCase = true) || it.alts.any { it.equals(name, ignoreCase = true) } }