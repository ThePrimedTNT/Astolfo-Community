package xyz.astolfo.astolfocommunity

import net.dv8tion.jda.core.EmbedBuilder
import net.dv8tion.jda.core.entities.MessageEmbed

fun embed(builder: EmbedBuilder.() -> Unit): MessageEmbed {
    val embedBuilder = EmbedBuilder()
    builder.invoke(embedBuilder)
    return embedBuilder.build()
}

fun EmbedBuilder.title(title: String, url: String? = null) = setTitle(title, url)!!

fun EmbedBuilder.field(name: String, value: String, inline: Boolean) = addField(name, value, inline)!!