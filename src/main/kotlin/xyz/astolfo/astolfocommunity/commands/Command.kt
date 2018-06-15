package xyz.astolfo.astolfocommunity.commands

import com.jagrosh.jdautilities.commons.utils.FinderUtil
import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import xyz.astolfo.astolfocommunity.*
import xyz.astolfo.astolfocommunity.menus.Paginator
import xyz.astolfo.astolfocommunity.menus.paginator
import xyz.astolfo.astolfocommunity.menus.provider
import xyz.astolfo.astolfocommunity.menus.renderer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class Command(val name: String, val alts: Array<out String>, val subCommands: List<Command>, val inheritedAction: suspend CommandExecution.() -> Boolean, val action: suspend CommandExecution.() -> Unit)

class CommandBuilder(private val name: String, private val alts: Array<out String>) {
    val subCommands = mutableListOf<Command>()
    var action: suspend CommandExecution.() -> Unit = {
        val guildSettings = application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong)
        messageAction("Unknown command! Type **${guildSettings.prefix.takeIf { it.isNotBlank() }
                ?: application.properties.default_prefix}$commandPath help** for a list of commands.").queue()
    }
    var inheritedAction: suspend CommandExecution.() -> Boolean = { true }
    fun build(): Command {
        if (subCommands.isNotEmpty()) {
            command("help") {
                action {
                    val guildSettings = application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong)
                    messageAction(embed {
                        title("Astolfo ${this@CommandBuilder.name.capitalize()} Help")
                        description(this@CommandBuilder.subCommands.joinToString(separator = "\n") { subCommand ->
                            "${guildSettings.prefix.takeIf { it.isNotBlank() }
                                    ?: application.properties.default_prefix}${commandPath.substringBeforeLast(" ").trim()} **${subCommand.name}**"
                        })
                    }).queue()
                }
            }
        }
        return Command(name, alts, subCommands, inheritedAction, action)
    }
}

inline fun CommandBuilder.command(subName: String, vararg alts: String, builder: CommandBuilder.() -> Unit) {
    val commandBuilder = CommandBuilder(subName, alts)
    builder.invoke(commandBuilder)
    subCommands.add(commandBuilder.build())
}

fun CommandBuilder.action(action: suspend CommandExecution.() -> Unit) {
    this.action = action
}

fun CommandBuilder.inheritedAction(inheritedAction: suspend CommandExecution.() -> Boolean) {
    this.inheritedAction = inheritedAction
}

open class CommandExecution(
        val application: AstolfoCommunityApplication,
        val event: MessageReceivedEvent,
        val session: CommandSession,
        val commandPath: String,
        val args: String,
        val timeIssued: Long
)

fun CommandExecution.messageAction(text: CharSequence) = event.channel.sendMessage(text)!!
fun CommandExecution.messageAction(embed: MessageEmbed) = event.channel.sendMessage(embed)!!
fun CommandExecution.messageAction(msg: Message) = event.channel.sendMessage(msg)!!

suspend fun <T> CommandExecution.tempMessage(msg: Message, temp: suspend () -> T): T {
    val atomicMessage = AtomicReference<AsyncMessage>()
    val future = async(parent = (session as CommandSessionImpl).parentJob) {
        atomicMessage.set(messageAction(msg).sendAsync())
        temp.invoke()
    }
    future.invokeOnCompletion { atomicMessage.get()?.delete() }
    return future.await()
}

fun CommandExecution.updatable(rate: Long, unit: TimeUnit = TimeUnit.SECONDS, updater: (CommandSession) -> Unit) = session.updatable(rate, unit, updater)
fun CommandExecution.updatableMessage(rate: Long, unit: TimeUnit = TimeUnit.SECONDS, messageUpdater: () -> MessageEmbed) {
    val messageAsync = messageAction(messageUpdater.invoke()).sendAsync()
    updatable(rate, unit) {
        messageAsync.editMessage(messageUpdater.invoke())
    }
}

inline fun CommandExecution.responseListener(crossinline listener: CommandExecution.() -> CommandSession.ResponseAction) = session.addListener(object : CommandSession.SessionListener() {
    override fun onMessageReceived(execution: CommandExecution) = listener.invoke(execution)
})

inline fun CommandExecution.destroyListener(crossinline listener: () -> Unit) = session.addListener(object : CommandSession.SessionListener() {
    override fun onSessionDestroyed() = listener.invoke()
})

fun CommandExecution.runWhileSessionActive(block: suspend CommandExecution.() -> Unit){
    val loadJob = launch(parent = (session as CommandSessionImpl).parentJob) {
        block.invoke(this@runWhileSessionActive)
    }
    destroyListener { loadJob.cancel() }
}

suspend fun CommandExecution.getProfile() = async { application.astolfoRepositories.getEffectiveUserProfile(event.author.idLong) }.await()