package xyz.astolfo.astolfocommunity

import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import java.util.concurrent.TimeUnit

class Command(val name: String, val alts: Array<out String>, val subCommands: List<Command>, val action: CommandExecution.() -> Unit)

class CommandBuilder(private val name: String, private val alts: Array<out String>) {
    val subCommands = mutableListOf<Command>()
    var action: CommandExecution.() -> Unit = { messageAction("Hello! This is a default command!").queue() }
    fun build() = Command(name, alts, subCommands, action)
}

fun CommandBuilder.command(subName: String, vararg alts: String, builder: CommandBuilder.() -> Unit) {
    val commandBuilder = CommandBuilder(subName, alts)
    builder.invoke(commandBuilder)
    subCommands.add(commandBuilder.build())
}

fun CommandBuilder.action(action: CommandExecution.() -> Unit) {
    this.action = action
}

open class CommandExecution(val application: AstolfoCommunityApplication, val event: MessageReceivedEvent, val commandPath: String, val args: String, val timeIssued: Long)

fun CommandExecution.messageAction(text: CharSequence) = event.channel.sendMessage(text)!!
fun CommandExecution.messageAction(embed: MessageEmbed) = event.channel.sendMessage(embed)!!
fun CommandExecution.messageAction(msg: Message) = event.channel.sendMessage(msg)!!

fun <T> CommandExecution.tempMessage(msg: Message, temp: () -> T): T {
    val messageAsync = async { messageAction(msg).complete() }
    val toReturn = temp.invoke()
    launch { messageAsync.await().delete().queue() }
    return toReturn
}

fun CommandExecution.session() = application.commandHandler.commandSessionMap.get(CommandHandler.SessionKey(event.guild.idLong, event.author.idLong, event.channel.idLong), { CommandSession(commandPath) })!!
fun CommandExecution.updatable(rate: Long, unit: TimeUnit = TimeUnit.SECONDS, updater: (CommandSession) -> Unit) = session().updatable(rate, unit, updater)
fun CommandExecution.updatableMessage(rate: Long, unit: TimeUnit = TimeUnit.SECONDS, messageUpdater: () -> MessageEmbed) {
    var messageAsync = async { messageAction(messageUpdater.invoke()).complete() }
    updatable(rate, unit) {
        if (!messageAsync.isCompleted) return@updatable
        val message = messageAsync.getCompleted()!!
        messageAsync = async { message.editMessage(messageUpdater.invoke()).complete() }
    }
}

fun CommandExecution.responseListener(listener: ResponseListener.(CommandExecution) -> Boolean) = session().addReponseListener(listener)
fun CommandExecution.destroyListener(listener: () -> Unit) = session().addDestroyListener(listener)

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
    fun addReponseListener(listener: ResponseListener.(CommandExecution) -> Boolean) = responseListeners.add(listener)
    fun removeReponseListener(listener: ResponseListener.(CommandExecution) -> Boolean) = responseListeners.remove(listener)

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
    fun removeListener() = session.removeReponseListener(listener)
}