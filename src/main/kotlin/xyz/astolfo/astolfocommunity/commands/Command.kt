package xyz.astolfo.astolfocommunity.commands

import kotlinx.coroutines.experimental.DefaultDispatcher
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.withContext
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import xyz.astolfo.astolfocommunity.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
        val guildPrefix = getGuildSettings().getEffectiveGuildPrefix(application)
        messageAction("Unknown command! Type **$guildPrefix$commandPath help** for a list of commands.").queue()
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

private const val DEFAULT_LONG_TERM_UPVOTE_MESSAGE = "You must upvote the bot to use this feature!"
private fun defaultShortTermUpvoteMessage(days: Long) = "You havn't upvoted in the past $days days! Upvote to continue using this feature."

fun CommandBuilder.upvote(days: Long, longTermReason: String = DEFAULT_LONG_TERM_UPVOTE_MESSAGE,
                          shortTermReason: String = defaultShortTermUpvoteMessage(days)) = inheritedAction { upvote(days, longTermReason, shortTermReason) }

fun CommandExecution.upvote(days: Long, longTermReason: String = DEFAULT_LONG_TERM_UPVOTE_MESSAGE,
                            shortTermReason: String = defaultShortTermUpvoteMessage(days)): Boolean {
    val profile = application.astolfoRepositories.getEffectiveUserProfile(event.author.idLong)
    val upvoteInfo = profile.userUpvote
    return when {
        upvoteInfo.lastUpvote <= 0 || upvoteInfo.timeSinceLastUpvote >= TimeUnit.DAYS.toMillis(days + 3) -> {
            messageAction("$longTermReason Upvote here: <https://discordbots.org/bot/${event.jda.selfUser.idLong}>").queue()
            false
        }
        upvoteInfo.timeSinceLastUpvote >= TimeUnit.DAYS.toMillis(days) -> {
            messageAction("$shortTermReason Upvote here: <https://discordbots.org/bot/${event.jda.selfUser.idLong}>").queue()
            false
        }
        else -> true
    }
}

open class CommandExecution(
        val application: AstolfoCommunityApplication,
        val event: MessageReceivedEvent,
        val session: CommandSession,
        val commandPath: String,
        val args: CommandArgs,
        val timeIssued: Long
) {
    fun messageAction(text: CharSequence) = event.channel.sendMessage(text)!!
    fun messageAction(embed: MessageEmbed) = event.channel.sendMessage(embed)!!
    fun messageAction(msg: Message) = event.channel.sendMessage(msg)!!
    suspend fun <T> tempMessage(msg: Message, temp: suspend () -> T): T {
        val atomicMessage = AtomicReference<AsyncMessage>()
        val future = async(parent = (session as CommandSessionImpl).parentJob) {
            atomicMessage.set(messageAction(msg).sendAsync())
            temp.invoke()
        }
        future.invokeOnCompletion { atomicMessage.get()?.delete() }
        return future.await()
    }

    fun updatable(rate: Long, unit: TimeUnit = TimeUnit.SECONDS, updater: (CommandSession) -> Unit) = session.updatable(rate, unit, updater)

    inline fun responseListener(crossinline listener: CommandExecution.() -> CommandSession.ResponseAction) =
            listener(messageListener = listener)

    inline fun destroyListener(crossinline listener: () -> Unit) = listener(destroyListener = listener)
    inline fun listener(
            crossinline messageListener: CommandExecution.() -> CommandSession.ResponseAction = { CommandSession.ResponseAction.NOTHING },
            crossinline destroyListener: () -> Unit = {}) = session.addListener(object : CommandSession.SessionListener() {
        override fun onMessageReceived(execution: CommandExecution) = messageListener(execution)
        override fun onSessionDestroyed() = destroyListener()
    })

    fun runWhileSessionActive(block: suspend CommandExecution.() -> Unit) {
        val loadJob = launch(parent = (session as CommandSessionImpl).parentJob) {
            block.invoke(this@CommandExecution)
        }
        destroyListener { loadJob.cancel() }
    }

    // TODO this seems odd to have here
    suspend fun getProfile() = withContext(DefaultDispatcher) { application.astolfoRepositories.getEffectiveUserProfile(event.author.idLong) }

    suspend fun getGuildSettings() = withContext(DefaultDispatcher) { application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong) }
    suspend fun setGuildSettings(guildSettings: GuildSettings) =
            withContext(DefaultDispatcher) { application.astolfoRepositories.guildSettingsRepository.save(guildSettings) }

    suspend inline fun <E> withGuildSettings(block: (GuildSettings) -> E): E {
        val guildSettings = getGuildSettings()
        val result = block.invoke(guildSettings)
        setGuildSettings(guildSettings)
        return result
    }
}

class StageAction<E>(val newData: () -> E) {
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