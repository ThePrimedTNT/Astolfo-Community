package xyz.astolfo.astolfocommunity

import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.entities.MessageEmbed
import java.awt.Color

fun embed(builder: EmbedBuilder.() -> Unit): MessageEmbed {
    val embedBuilder = EmbedBuilder()
    embedBuilder.setColor(Color(119, 60, 138))
    builder.invoke(embedBuilder)
    return embedBuilder.build()
}

fun EmbedBuilder.title(title: String, url: String? = null) = setTitle(title, url)!!
fun EmbedBuilder.field(name: String, value: String, inline: Boolean) = addField(name, value, inline)!!
fun EmbedBuilder.field(name: String, inline: Boolean, value: () -> String) = addField(name, value.invoke(), inline)!!
fun EmbedBuilder.description(description: String) = setDescription(description)
fun EmbedBuilder.description(value: () -> String) = setDescription(value.invoke())