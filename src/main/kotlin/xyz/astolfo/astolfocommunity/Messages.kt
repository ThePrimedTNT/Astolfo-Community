package xyz.astolfo.astolfocommunity

import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.MessageBuilder
import net.dv8tion.jda.core.entities.Emote
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.requests.RequestFuture
import net.dv8tion.jda.core.requests.restaction.MessageAction
import net.dv8tion.jda.core.utils.Promise
import java.awt.Color

fun embed(text: String) = embed { description(text) }
fun embed(builder: EmbedBuilder.() -> Unit): MessageEmbed {
    val embedBuilder = EmbedBuilder()
    embedBuilder.setColor(Color(119, 60, 138))
    builder.invoke(embedBuilder)
    return embedBuilder.build()
}

fun message(text: String) = message { setContent(text) }
fun message(builder: MessageBuilder.() -> Unit): Message {
    val messageBuilder = MessageBuilder()
    builder.invoke(messageBuilder)
    return messageBuilder.build()
}

fun MessageBuilder.embed(text: String) = embed { description(text) }
fun MessageBuilder.embed(builder: EmbedBuilder.() -> Unit) = setEmbed(xyz.astolfo.astolfocommunity.embed(builder))!!
fun MessageBuilder.content(content: String) = setContent(content)!!

fun EmbedBuilder.color(color: java.awt.Color) = setColor(color)!!
fun EmbedBuilder.title(title: String, url: String? = null) = setTitle(title, url)!!
fun EmbedBuilder.description(description: String) = setDescription(description)!!
fun EmbedBuilder.description(value: () -> String) = setDescription(value.invoke())!!
fun EmbedBuilder.field(name: String, value: String, inline: Boolean) = addField(name, value, inline)!!
fun EmbedBuilder.field(name: String, inline: Boolean, value: () -> String) = addField(name, value.invoke(), inline)!!
fun EmbedBuilder.thumbnail(imageUrl: String) = setThumbnail(imageUrl)!!
fun EmbedBuilder.image(imageUrl: String) = setImage(imageUrl)!!
fun EmbedBuilder.author(name: String, uri: String? = null, icon: String? = null) = setAuthor(name, uri, icon)!!
fun EmbedBuilder.footer(text: String, icon: String? = null) = setFooter(text, icon)!!

fun MessageAction.sendAsync() = AsyncMessage(this.submit())
fun Message.toAsync() = AsyncMessage(Promise(this))

class AsyncMessage(asyncMessage: RequestFuture<Message>) {

    private var cachedMessage: Message? = null

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
            } else {
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

    fun addReaction(emote: Emote?) {
        thenApply { it.addReaction(emote).queue() }
    }

    fun addReaction(unicode: String?) {
        thenApply { it.addReaction(unicode).queue() }
    }

    fun clearReactions() {
        thenApply { it.clearReactions().queue() }
    }

    fun getIdLong(): Long? = cachedMessage?.idLong

    fun editMessage(newContent: CharSequence?) {
        thenApply { oldMessage ->
            oldMessage.editMessage(newContent).queue { newMessage -> updateCachedWithNew(newMessage) }
        }
    }

    fun editMessage(newContent: MessageEmbed?) {
        thenApply { oldMessage ->
            oldMessage.editMessage(newContent).queue { newMessage -> updateCachedWithNew(newMessage) }
        }
    }

    fun editMessage(newContent: Message?) {
        thenApply { oldMessage ->
            oldMessage.editMessage(newContent).queue { newMessage -> updateCachedWithNew(newMessage) }
        }
    }

    fun delete() {
        thenApply { it.delete().queue { cachedMessage = null } }
    }

}