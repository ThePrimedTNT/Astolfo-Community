package xyz.astolfo.astolfocommunity

import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import java.util.concurrent.TimeUnit

class Command(val name: String, val subCommands: List<Command>, val action: CommandExecution.() -> Unit)

class CommandBuilder(private val name: String) {
    val subCommands = mutableListOf<Command>()
    var action: CommandExecution.() -> Unit = { message("Hello! This is a default command!").queue() }
    fun build() = Command(name, subCommands, action)
}

fun CommandBuilder.command(subName: String, builder: CommandBuilder.() -> Unit) {
    val commandBuilder = CommandBuilder(subName)
    builder.invoke(commandBuilder)
    subCommands.add(commandBuilder.build())
}

fun CommandBuilder.action(action: CommandExecution.() -> Unit) {
    this.action = action
}

open class CommandExecution(val application: AstolfoCommunityApplication, val event: MessageReceivedEvent, val args: String, val timeIssued: Long)

fun CommandExecution.message(text: CharSequence) = event.channel.sendMessage(text)!!
fun CommandExecution.message(embed: MessageEmbed) = event.channel.sendMessage(embed)!!
fun CommandExecution.message(msg: Message) = event.channel.sendMessage(msg)!!

fun CommandExecution.session() = application.commandHandler.commandSessionMap.get(CommandHandler.SessionKey(event.guild.idLong, event.author.idLong, event.channel.idLong), { CommandSession() })!!
fun CommandExecution.updatable(rate: Long, unit: TimeUnit = TimeUnit.SECONDS, updater: (CommandSession) -> Unit) = session().updatable(rate, unit, updater)
fun CommandExecution.updatableMessage(rate: Long, unit: TimeUnit = TimeUnit.SECONDS, messageUpdater: () -> MessageEmbed) {
    var messageAsync = async { message(messageUpdater.invoke()).complete() }
    updatable(rate, unit) {
        if (!messageAsync.isCompleted) return@updatable
        val message = messageAsync.getCompleted()!!
        messageAsync = async { message.editMessage(messageUpdater.invoke()).complete() }
    }
}

class CommandSession {
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

    fun destroy() {
        synchronized(jobs) {
            jobs.forEach { it.cancel() }
            jobs.clear()
        }
    }
}