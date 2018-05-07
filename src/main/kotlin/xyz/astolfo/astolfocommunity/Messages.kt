package xyz.astolfo.astolfocommunity

import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.entities.MessageEmbed

fun embed(builder: EmbedBuilder.() -> Unit): MessageEmbed {
    val embedBuilder = EmbedBuilder()
    builder.invoke(embedBuilder)
    return embedBuilder.build()
}

fun EmbedBuilder.color(color: java.awt.Color) = setColor(color)!!
fun EmbedBuilder.title(title: String, url: String? = null) = setTitle(title, url)!!
fun EmbedBuilder.description(description: String) = setDescription(description)!!
fun EmbedBuilder.field(name: String, value: String, inline: Boolean) = addField(name, value, inline)!!
fun EmbedBuilder.field(name: String, inline: Boolean, value: () -> String) = addField(name, value.invoke(), inline)!!
fun EmbedBuilder.thumbnail(imageUrl: String) = setThumbnail(imageUrl)!!
fun EmbedBuilder.image(imageUrl: String) = setImage(imageUrl)!!