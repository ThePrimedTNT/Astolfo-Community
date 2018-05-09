package xyz.astolfo.astolfocommunity

import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.MessageBuilder
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
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

fun EmbedBuilder.color(color: java.awt.Color) = setColor(color)!!
fun EmbedBuilder.title(title: String, url: String? = null) = setTitle(title, url)!!
fun EmbedBuilder.description(description: String) = setDescription(description)!!
fun EmbedBuilder.description(value: () -> String) = setDescription(value.invoke())!!
fun EmbedBuilder.field(name: String, value: String, inline: Boolean) = addField(name, value, inline)!!
fun EmbedBuilder.field(name: String, inline: Boolean, value: () -> String) = addField(name, value.invoke(), inline)!!
fun EmbedBuilder.thumbnail(imageUrl: String) = setThumbnail(imageUrl)!!
fun EmbedBuilder.image(imageUrl: String) = setImage(imageUrl)!!
fun EmbedBuilder.author(name: String, uri: String? = null, icon: String? = null) = setAuthor(name, uri, icon)!!
