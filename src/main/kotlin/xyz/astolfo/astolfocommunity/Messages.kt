package xyz.astolfo.astolfocommunity

import kotlinx.coroutines.experimental.*
import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.MessageBuilder
import net.dv8tion.jda.core.entities.Emote
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.requests.RequestFuture
import net.dv8tion.jda.core.requests.RestAction
import net.dv8tion.jda.core.requests.restaction.MessageAction
import net.dv8tion.jda.core.utils.Promise
import java.awt.Color
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

fun embed(text: String) = embed { description(text) }
inline fun embed(builder: EmbedBuilder.() -> Unit): MessageEmbed {
    val embedBuilder = EmbedBuilder()
    embedBuilder.setColor(Color(119, 60, 138))
    builder.invoke(embedBuilder)
    return embedBuilder.build()
}

fun message(text: String) = message { setContent(text) }
inline fun message(builder: MessageBuilder.() -> Unit): Message {
    val messageBuilder = MessageBuilder()
    builder.invoke(messageBuilder)
    return messageBuilder.build()
}

fun MessageBuilder.embed(text: String) = embed { description(text) }
inline fun MessageBuilder.embed(builder: EmbedBuilder.() -> Unit) = setEmbed(xyz.astolfo.astolfocommunity.embed(builder))!!
fun MessageBuilder.content(content: String) = setContent(content)!!

fun EmbedBuilder.color(color: Color) = setColor(color)!!
fun EmbedBuilder.title(title: String, url: String? = null) = setTitle(title, url)!!
fun EmbedBuilder.description(description: String) = setDescription(description)!!
inline fun EmbedBuilder.description(value: () -> String) = description(value.invoke())
fun EmbedBuilder.field(name: String, value: String, inline: Boolean) = addField(name, value, inline)!!
inline fun EmbedBuilder.field(name: String, inline: Boolean, value: () -> String) = field(name, value.invoke(), inline)
fun EmbedBuilder.thumbnail(imageUrl: String) = setThumbnail(imageUrl)!!
fun EmbedBuilder.image(imageUrl: String) = setImage(imageUrl)!!
fun EmbedBuilder.author(name: String, uri: String? = null, icon: String? = null) = setAuthor(name, uri, icon)!!
fun EmbedBuilder.footer(text: String, icon: String? = null) = setFooter(text, icon)!!

fun MessageAction.sendAsync() = AsyncMessage(this.submit())
fun Message.toAsync() = AsyncMessage(Promise(this))

class AsyncMessage(asyncMessage: RequestFuture<Message>) {

    companion object {
        private val asyncRequestFutureContext = newFixedThreadPoolContext(50, "Async Request Future")
    }

    // The latest version from discord
    private var cachedMessage: Message? = null
    // If the message was deleted, no longer intractable
    private var deleted = false

    private val taskManager = TaskManager()

    init {
        asyncMessage.thenApply {
            cachedMessage = it
            taskManager.start()
        }
    }

    private fun updateCachedWithNew(newMessage: Message) {
        if (cachedMessage == null || !cachedMessage!!.isEdited) {
            cachedMessage = newMessage
            return
        }
        val cachedEditTime = cachedMessage!!.editedTime
        val newEditTime = newMessage.editedTime
        if (newEditTime < cachedEditTime) cachedMessage = newMessage
    }

    private fun handle(action: (Message) -> RestAction<Void?>, response: () -> Unit) {
        synchronized(taskManager) {
            if (deleted) return
            taskManager.add(action, 0L, TimeUnit.MINUTES) { response.invoke() }
        }
    }

    private fun handleMessage(action: (Message) -> RestAction<Message?>, delay: Long, unit: TimeUnit, response: (Message) -> Unit) {
        synchronized(taskManager) {
            if (deleted) return
            taskManager.add(action, delay, unit) {
                updateCachedWithNew(it!!)
                response.invoke(it)
            }
        }
    }

    fun addReaction(emote: Emote?, response: () -> Unit = {}) = handle({ it.addReaction(emote) }, response)
    fun addReaction(unicode: String?, response: () -> Unit = {}) = handle({ it.addReaction(unicode) }, response)
    fun clearReactions(response: () -> Unit = {}) = handle({ it.clearReactions() }, response)

    fun getIdLong(): Long? = cachedMessage?.idLong

    fun editMessage(newContent: CharSequence?, delay: Long = 0, unit: TimeUnit = TimeUnit.SECONDS, response: (Message) -> Unit = {}) =
            handleMessage({ it.editMessage(newContent) }, delay, unit, response)

    fun editMessage(newContent: MessageEmbed?, delay: Long = 0, unit: TimeUnit = TimeUnit.SECONDS, response: (Message) -> Unit = {}) =
            handleMessage({ it.editMessage(newContent) }, delay, unit, response)

    fun editMessage(newContent: Message?, delay: Long = 0, unit: TimeUnit = TimeUnit.SECONDS, response: (Message) -> Unit = {}) =
            handleMessage({ it.editMessage(newContent) }, delay, unit, response)

    fun delete(response: () -> Unit = {}) {
        synchronized(taskManager) {
            if (deleted) throw IllegalStateException("Message already deleted!")
            taskManager.dispose()
            taskManager.add({ it.delete() }, 0, TimeUnit.MINUTES, {
                cachedMessage = null
                response.invoke()
            })
            deleted = true
        }
    }

    inner class TaskManager {
        private val tasks: MutableList<AsyncMessageTask<*>> = CopyOnWriteArrayList()
        private var started = false

        fun start() {
            synchronized(tasks) {
                if (started) throw IllegalStateException("You cannot start the task manager twice!")
                started = true
                tasks.forEach { it.start() }
            }
        }

        fun <E> add(action: (Message) -> RestAction<E?>, delay: Long, unit: TimeUnit, response: (E?) -> Unit) = add(AsyncMessageTask(action, delay, unit, response))

        private fun add(task: AsyncMessageTask<*>) {
            synchronized(tasks) {
                if (deleted) throw IllegalStateException("AsyncMessage already disposed!")
                tasks.add(task)
                if (started) task.start()
            }
        }

        fun dispose() {
            synchronized(tasks) {
                tasks.forEach { it.dispose() }
            }
        }

        inner class AsyncMessageTask<E>(val action: (Message) -> RestAction<E?>, val delay: Long, val unit: TimeUnit, val response: (E?) -> Unit) {
            private val lock = Any()
            private var task: Job? = null
            private var restFuture: Future<E?>? = null
            fun start() {
                synchronized(lock) {
                    val restAction = action.invoke(cachedMessage!!)
                    restFuture = if (delay > 0) restAction.submitAfter(delay, unit)
                    else restAction.submit()
                    task = launch(asyncRequestFutureContext) {
                        // TODO find solution that doesn't block a thread
                        val result = restFuture!!.get(5, TimeUnit.MINUTES)
                        response.invoke(result)
                    }
                }
            }

            fun dispose() {
                synchronized(lock) {
                    restFuture?.cancel(true)
                    runBlocking { task?.cancelAndJoin() }
                    tasks.remove(this@AsyncMessageTask)
                }
            }
        }

    }

}