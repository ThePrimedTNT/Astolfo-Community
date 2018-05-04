package xyz.astolfo.astolfocommunity

import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import org.springframework.stereotype.Component

@Component
class MessageListener(val astolfoCommunityApplication: AstolfoCommunityApplication) : ListenerAdapter() {

    private val messageProcessorContext = newFixedThreadPoolContext(20, "Message Processor")
    private val commandProcessorContext = newFixedThreadPoolContext(20, "Command Processor")

    override fun onMessageReceived(event: MessageReceivedEvent?) {
        launch(messageProcessorContext) {
            val timeIssued = System.nanoTime()

            val rawMessage = event!!.message.contentRaw!!
            val prefix = "c?"

            if (!rawMessage.startsWith(prefix, ignoreCase = true)) return@launch

            launch(commandProcessorContext) {
                val commandMessage = rawMessage.substring(prefix.length)

                modules.find { processCommand(event, timeIssued, it.commands, commandMessage) }
            }
        }
    }

    private fun processCommand(event: MessageReceivedEvent, timeIssued: Long, commands: List<Command>, commandMessage: String): Boolean {
        val commandName = commandMessage.substringBefore(" ")
        val commandContent = commandMessage.substringAfter(" ")

        val command = commands.find { it.name.equals(commandName, ignoreCase = true) } ?: return false

        if (!processCommand(event, timeIssued, command.subCommands, commandContent)) command.action.invoke(CommandExecution(astolfoCommunityApplication.shardManager, event, timeIssued))

        return true
    }

}