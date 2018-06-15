package xyz.astolfo.astolfocommunity

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

    private var cachedMessage: Message? = null
    private var deleted = false

    private val waitingActions = mutableListOf<(Message) -> Unit>()

    init {
        asyncMessage.thenApply {
            cachedMessage = it
            synchronized(waitingActions) {
                waitingActions.forEach { it.invoke(cachedMessage!!) }
                waitingActions.clear()
            }
        }
    }

    private fun thenApply(action: (Message) -> Unit) {
        synchronized(waitingActions) {
            if (cachedMessage != null) {
                action.invoke(cachedMessage!!)
            } else if (!deleted) {
                waitingActions.add(action)
            }
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

    private fun handle(action: (Message) -> RestAction<Void>, response: () -> Unit) {
        thenApply { action.invoke(it).queue({ response.invoke() }) }
    }

    private fun handleMessage(action: (Message) -> RestAction<Message>, delay: Long, unit: TimeUnit, response: (Message) -> Unit) {
        thenApply { oldMessage ->
            fun handleNewMessage(newMessage: Message) {
                updateCachedWithNew(newMessage)
                response.invoke(newMessage)
            }

            val action = action.invoke(oldMessage)
            if (delay > 0) action.queueAfter(delay, unit, ::handleNewMessage)
            else action.queue(::handleNewMessage)
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
        thenApply {
            it.delete().queue {
                deleted = true
                cachedMessage = null
                response.invoke()
            }
        }
    }

}