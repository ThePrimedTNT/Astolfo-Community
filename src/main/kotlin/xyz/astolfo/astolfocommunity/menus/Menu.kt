package xyz.astolfo.astolfocommunity.menus

import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.events.message.react.GenericMessageReactionEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import xyz.astolfo.astolfocommunity.commands.CommandExecution
import xyz.astolfo.astolfocommunity.commands.CommandSession
import xyz.astolfo.astolfocommunity.messages.*
import xyz.astolfo.astolfocommunity.value
import kotlin.math.max
import kotlin.math.min


fun CommandExecution.paginator(titleProvider: String? = "", builder: PaginatorBuilder.() -> Unit) = paginator({ titleProvider }, builder)
fun CommandExecution.paginator(titleProvider: () -> String? = { null }, builder: PaginatorBuilder.() -> Unit): Paginator {
    val paginatorBuilder = PaginatorBuilder(this, titleProvider, PaginatorProvider(0) { listOf() }) {
        message {
            embed {
                titleProvider.invoke()?.let { title(it) }
                description(providedString)
                footer("Page ${currentPage + 1}/${provider.pageCount}")
            }
        }
    }
    builder.invoke(paginatorBuilder)
    return paginatorBuilder.build()
}

private const val default_extraCharactersPerLine = 20

fun PaginatorBuilder.provider(perPage: Int, provider: List<String>, extraCharactersPerLine: Int = default_extraCharactersPerLine) = provider(perPage, { provider }, extraCharactersPerLine)
fun PaginatorBuilder.provider(perPage: Int, provider: () -> List<String>, extraCharactersPerLine: Int = default_extraCharactersPerLine) {
    this.provider = PaginatorProvider(perPage, extraCharactersPerLine, provider)
}

fun PaginatorBuilder.renderer(renderer: Paginator.() -> Message) {
    this.renderer = renderer
}

class PaginatorBuilder(private val commandExecution: CommandExecution, var titleProvider: () -> String?, var provider: PaginatorProvider, var renderer: Paginator.() -> Message) {
    fun build() = Paginator(commandExecution, titleProvider, provider, renderer)
}

class PaginatorProvider(val perPage: Int,
                        val extraCharactersPerLine: Int = default_extraCharactersPerLine,
                        provider: () -> List<String>) {
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
            return if (content.isEmpty()) emptyList()
            else content.subList((currentPage * provider.perPage).coerceIn(content.indices),
                    ((currentPage + 1) * provider.perPage).coerceIn(0..content.size))
        }

    val providedString: String
        get() {
            val indexOffset = currentPage * provider.perPage
            val maxTitleLength = 1024 - (provider.extraCharactersPerLine + 3) * provider.perPage
            return providedContent.mapIndexed { index, s -> "`${index + 1 + indexOffset}` $s" }.joinToString("\n") {
                if (it.length > maxTitleLength) "${it.substring(0, maxTitleLength)}..." else it
            }
        }

    private var message: CachedMessage? = null

    private val listener = object : ListenerAdapter() {
        override fun onGenericMessageReaction(event: GenericMessageReactionEvent?) {
            if (event!!.user.idLong != commandExecution.event.author.idLong) return
            if (message?.idLong?.value != event.messageIdLong) return
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

    private val destroyListener = object : CommandSession.SessionListener() {
        override fun onSessionDestroyed() {
            destroy()
        }
    }

    var isDestroyed = false
        private set

    init {
        commandExecution.event.jda.addEventListener(listener)
        render()
        commandExecution.session.addListener(destroyListener)
    }

    fun destroy() {
        // Clean Up
        if (isDestroyed) return
        isDestroyed = true
        commandExecution.session.removeListener(destroyListener)
        commandExecution.event.jda.removeEventListener(listener)
        message?.delete()
        message = null
    }

    companion object {
        val START_ARROW = "\u23EE"
        val BACK_ARROW = "\u25C0"
        val SELECT = "\u23F9"
        val FORWARD_ARROW = "\u25B6"
        val END_ARROW = "\u23ED"
    }

    fun render() {
        if (isDestroyed) return
        val newMessage = renderer.invoke(this@Paginator)
        if (message == null) {
            message = commandExecution.messageAction(newMessage).sendCached()

            val pageCount = provider.pageCount
            val emotes = mutableListOf<String>()
            if (pageCount > 2) emotes.add(START_ARROW)
            if (pageCount > 1) emotes.add(BACK_ARROW)
            emotes.add(SELECT)
            if (pageCount > 1) emotes.add(FORWARD_ARROW)
            if (pageCount > 2) emotes.add(END_ARROW)

            emotes.forEach { message!!.addReaction(it) }
        } else {
            message!!.editMessage(newMessage)
        }
    }

}