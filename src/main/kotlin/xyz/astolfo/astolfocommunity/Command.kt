package xyz.astolfo.astolfocommunity

import net.dv8tion.jda.bot.sharding.DefaultShardManager
import net.dv8tion.jda.bot.sharding.ShardManager
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.events.message.MessageReceivedEvent

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