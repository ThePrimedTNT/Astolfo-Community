package xyz.astolfo.astolfocommunity

import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.events.message.react.GenericMessageReactionEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import kotlin.math.max
import kotlin.math.min


fun CommandExecution.paginator(titleProvider: String? = "", builder: PaginatorBuilder.() -> Unit) = paginator({ titleProvider }, builder)
fun CommandExecution.paginator(titleProvider: () -> String? = { null }, builder: PaginatorBuilder.() -> Unit): Paginator {
    val paginatorBuilder = PaginatorBuilder(this, titleProvider, PaginatorProvider(0, { listOf() }), {
        message {
            embed {
                titleProvider.invoke()?.let { title(it) }
                description(providedString)
                footer("Page ${currentPage + 1}/${provider.pageCount}")
            }
        }
    })
    builder.invoke(paginatorBuilder)
    return paginatorBuilder.build()
}

fun PaginatorBuilder.provider(perPage: Int, provider: List<String>) = provider(perPage, { provider })
fun PaginatorBuilder.provider(perPage: Int, provider: () -> List<String>) {
    this.provider = PaginatorProvider(perPage, provider)
}

fun PaginatorBuilder.renderer(renderer: Paginator.() -> Message) {
    this.renderer = renderer
}

class PaginatorBuilder(private val commandExecution: CommandExecution, var titleProvider: () -> String?, var provider: PaginatorProvider, var renderer: Paginator.() -> Message) {
    fun build() = Paginator(commandExecution, titleProvider, provider, renderer)
}

class PaginatorProvider(val perPage: Int, provider: () -> List<String>) {
    val provider = {
        val provided = provider.invoke()
        pageCount = Math.ceil(provided.size.toDouble() / perPage).toInt()
        provided
    }
    var pageCount: Int = -1
        get() {
            if (field == -1) provider.invoke()
            return field
        }
        private set
}

class Paginator(private val commandExecution: CommandExecution, val titleProvider: () -> String?, val provider: PaginatorProvider, val renderer: Paginator.() -> Message) {
    var currentPage = 0
        set(value) {
            val oldPage = field
            field = max(0, min(value, provider.pageCount - 1))
            if (field != oldPage) render()
        }

    val providedContent: List<String>
        get() {
            val content = provider.provider.invoke()
            return content.subList(max(0, currentPage * provider.perPage), min(content.size, (currentPage + 1) * provider.perPage))
        }

    val providedString: String
        get() {
            val indexOffset = currentPage * provider.perPage
            return providedContent.mapIndexed { index, s -> "`${index + 1 + indexOffset}` $s" }.fold("", { a, b -> "$a\n$b" })
        }

    private var message: Deferred<Message>? = null

    private val listener = object : ListenerAdapter() {
        override fun onGenericMessageReaction(event: GenericMessageReactionEvent?) {
            if (event!!.user.idLong != commandExecution.event.author.idLong) return
            if (message!!.isActive || message!!.getCompleted().idLong != event.messageIdLong) return
            val name = event.reactionEmote.name
            if (name == SELECT) {
                destroy()
            } else {
                val newPage = when (name) {
                    START_ARROW -> 0
                    BACK_ARROW -> currentPage - 1
                    FORWARD_ARROW -> currentPage + 1
                    END_ARROW -> provider.pageCount
                    else -> -1
                }
                currentPage = newPage
            }
        }
    }

    val destroyListener = { destroy() }

    var isDestroyed = false
        private set

    init {
        commandExecution.event.jda.addEventListener(listener)
        render()
        commandExecution.session().addDestroyListener(destroyListener)
    }

    fun destroy() {
        // Clean Up
        isDestroyed = true
        commandExecution.session().removeDestroyListener(destroyListener)
        commandExecution.event.jda.removeEventListener(listener)
        launch {
            message?.await()?.delete()?.queue()
            message = null
        }
    }

    companion object {
        val START_ARROW = "\u23EE"
        val BACK_ARROW = "\u25C0"
        val SELECT = "\u23F9"
        val FORWARD_ARROW = "\u25B6"
        val END_ARROW = "\u23ED"
    }

    fun render() {
        val lastMessage = message
        message = async {
            val currentMessage = lastMessage?.await()
            val newMessage = renderer.invoke(this@Paginator)
            if (currentMessage == null) commandExecution.messageAction(newMessage).complete().apply {

                val pageCount = provider.pageCount
                val emotes = mutableListOf<String>()
                if (pageCount > 2) emotes.add(START_ARROW)
                if (pageCount > 1) emotes.add(BACK_ARROW)
                emotes.add(SELECT)
                if (pageCount > 1) emotes.add(FORWARD_ARROW)
                if (pageCount > 2) emotes.add(END_ARROW)

                emotes.forEach { addReaction(it).queue() }
            }
            else currentMessage.editMessage(newMessage).complete()
        }
    }

}