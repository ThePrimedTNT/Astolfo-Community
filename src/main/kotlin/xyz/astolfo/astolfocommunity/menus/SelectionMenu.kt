package xyz.astolfo.astolfocommunity.menus

import com.jagrosh.jdautilities.commons.utils.FinderUtil
import kotlinx.coroutines.experimental.CompletableDeferred
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.Role
import net.dv8tion.jda.core.entities.TextChannel
import xyz.astolfo.astolfocommunity.commands.CommandExecution
import xyz.astolfo.astolfocommunity.commands.CommandSession
import xyz.astolfo.astolfocommunity.messages.*
import java.util.concurrent.atomic.AtomicReference

fun CommandExecution.memberSelectionBuilder(query: String) = selectionBuilder<Member>()
        .results(FinderUtil.findMembers(query, event.guild))
        .noResultsMessage("Unknown Member!")
        .resultsRenderer { "**${it.effectiveName} (${it.user.name}#${it.user.discriminator})**" }
        .description("Type the number of the member you want.")

fun CommandExecution.textChannelSelectionBuilder(query: String) = selectionBuilder<TextChannel>()
        .results(FinderUtil.findTextChannels(query, event.guild))
        .noResultsMessage("Unknown Text Channel!")
        .resultsRenderer { "**${it.name} (${it.id})**" }
        .description("Type the number of the text channel you want.")

fun CommandExecution.roleSelectionBuilder(query: String) = selectionBuilder<Role>()
        .results(FinderUtil.findRoles(query, event.guild))
        .noResultsMessage("Unknown Role!")
        .resultsRenderer { "**${it.name} (${it.id})**" }
        .description("Type the number of the role you want.")

fun <E> CommandExecution.selectionBuilder() = SelectionMenuBuilder<E>(this)

class SelectionMenuBuilder<E>(private val execution: CommandExecution) {
    var title = "Selection Menu"
    var results = emptyList<E>()
    var noResultsMessage = "No results!"
    var resultsRenderer: (E) -> String = { it.toString() }
    var description = "Type the number of the selection you want"
    var renderer: Paginator.() -> Message = {
        message {
            embed {
                titleProvider.invoke()?.let { title(it) }
                description("$description\n$providedString")
                footer("Page ${currentPage + 1}/${provider.pageCount}")
            }
        }
    }

    fun title(value: String) = apply { title = value }
    fun results(value: List<E>) = apply { results = value }
    fun noResultsMessage(value: String) = apply { noResultsMessage = value }
    fun resultsRenderer(value: (E) -> String) = apply { resultsRenderer = value }
    fun renderer(value: Paginator.() -> Message) = apply { renderer = value }
    fun description(value: String) = apply { description = value }

    suspend fun execute(): E? = with(execution) {
        if (results.isEmpty()) {
            messageAction(noResultsMessage).queue()
            return null
        }

        if (results.size == 1) return results.first()

        val menu = paginator(title) {
            provider(8, results.map { resultsRenderer.invoke(it) })
            renderer { this@SelectionMenuBuilder.renderer.invoke(this) }
        }
        val response = CompletableDeferred<E?>()
        val errorMessage = AtomicReference<CachedMessage>()
        // Waits for a follow up response for user selection
        responseListener {
            if (menu.isDestroyed) {
                CommandSession.ResponseAction.UNREGISTER_LISTENER
            } else if (args.matches("\\d+".toRegex())) {
                val numSelection = args.toBigInteger().toInt()
                if (numSelection < 1 || numSelection > results.size) {
                    errorMessage.getAndSet(messageAction("Unknown Selection").sendCached())?.delete()
                    return@responseListener CommandSession.ResponseAction.IGNORE_COMMAND
                }
                val selectedMember = results[numSelection - 1]
                response.complete(selectedMember)
                CommandSession.ResponseAction.IGNORE_AND_UNREGISTER_LISTENER
            } else {
                if (event.message.contentRaw == args) {
                    errorMessage.getAndSet(messageAction("Response must be a number!").sendCached())?.delete()
                    return@responseListener CommandSession.ResponseAction.IGNORE_COMMAND
                } else CommandSession.ResponseAction.RUN_COMMAND
            }
        }
        destroyListener { response.complete(null) }
        response.invokeOnCompletion {
            menu.destroy()
            errorMessage.get()?.delete()
        }
        return response.await()
    }
}

fun CommandExecution.chatInput(inputMessage: String) = ChatInputBuilder(this)
        .description(inputMessage)

class ChatInputBuilder(private val execution: CommandExecution) {
    private var title: String = ""
    private var description: String = "Input = Output"
    private var responseValidator: (String) -> Boolean = { true }

    fun title(value: String) = apply { title = value }
    fun description(value: String) = apply { description = value }
    fun responseValidator(value: (String) -> Boolean) = apply { responseValidator = value }

    suspend fun execute(): String? = with(execution) {
        val response = CompletableDeferred<String?>()
        val message = messageAction(embed {
            if (title.isNotBlank()) title(title)
            description(description)
        }).sendCached()
        // Waits for a follow up response for user selection
        responseListener {
            if (message.isDeleted) {
                CommandSession.ResponseAction.UNREGISTER_LISTENER
            } else {
                val result = responseValidator.invoke(args)
                if (result) {
                    // If the validator says its valid
                    response.complete(args)
                    CommandSession.ResponseAction.IGNORE_AND_UNREGISTER_LISTENER
                } else {
                    CommandSession.ResponseAction.IGNORE_COMMAND
                }
            }
        }
        destroyListener { response.complete(null) }
        response.invokeOnCompletion { message.delete() }
        return response.await()
    }
}