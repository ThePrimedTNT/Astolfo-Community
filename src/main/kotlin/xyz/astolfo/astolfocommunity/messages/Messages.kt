package xyz.astolfo.astolfocommunity.messages

import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.MessageBuilder
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import java.awt.Color

fun embed(text: String) = embed0(text)
inline fun embed(crossinline builder: EmbedBuilder.() -> Unit) = embed0(builder)

fun embed0(text: String) = embed { description(text) }
inline fun embed0(crossinline builder: EmbedBuilder.() -> Unit): MessageEmbed {
    val embedBuilder = EmbedBuilder()
    embedBuilder.setColor(Color(64, 156, 217))
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
inline fun MessageBuilder.embed(crossinline builder: EmbedBuilder.() -> Unit) = setEmbed(xyz.astolfo.astolfocommunity.messages.embed(builder))!!
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

// Helpers
fun errorEmbed(text: String) = errorEmbed0(text)
inline fun errorEmbed(crossinline builder: EmbedBuilder.() -> Unit) = errorEmbed0(builder)

fun errorEmbed0(text: String) = errorEmbed0 { description(text) }
inline fun errorEmbed0(crossinline builder: EmbedBuilder.() -> Unit) = embed {
    color(Color.RED)
    builder(this)
}

fun Message.hasPermission(vararg permissions: Permission): Boolean = guild.selfMember.hasPermission(textChannel, *permissions)
