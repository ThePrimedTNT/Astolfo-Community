package xyz.astolfo.astolfocommunity

import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.entities.Message
import kotlin.math.max
import kotlin.math.min


fun CommandExecution.paginator(titleProvider: String? = "", builder: PaginatorBuilder.() -> Unit) = paginator({ titleProvider }, builder)
fun CommandExecution.paginator(titleProvider: () -> String? = { null }, builder: PaginatorBuilder.() -> Unit): Paginator {
    val paginatorBuilder = PaginatorBuilder(this, titleProvider, PaginatorProvider(0, { listOf() }), {
        message {
            embed {
                titleProvider.invoke()?.let { title(it) }
                description(providedString)
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

class PaginatorProvider(val perPage: Int, val provider: () -> List<String>)

class Paginator(private val commandExecution: CommandExecution, val titleProvider: () -> String?, val provider: PaginatorProvider, val renderer: Paginator.() -> Message) {
    var currentPage = 0

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

    init {
        render()
        commandExecution.session().addDestroyListener {
            // Clean Up
            launch { message?.await()?.delete()?.queue() }
        }
    }

    fun render() {
        val lastMessage = message
        message = async {
            val currentMessage = lastMessage?.await()
            val newMessage = renderer.invoke(this@Paginator)
            if (currentMessage == null) commandExecution.messageAction(newMessage).complete()
            else currentMessage.editMessage(newMessage).complete()
        }
    }

}