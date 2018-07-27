package xyz.astolfo.astolfocommunity.modules

import xyz.astolfo.astolfocommunity.ResourceManager.getImage
import xyz.astolfo.astolfocommunity.messages.image
import xyz.astolfo.astolfocommunity.messages.title
import xyz.astolfo.astolfocommunity.webJson
import java.util.*

fun createNSFWModule() = module("NSFW", nsfw = true) {
    inheritedAction {
        if (!event.channel.isNSFW) {
            messageAction(errorEmbed("The NSFW module is only allowed in NSFW enabled channels!")).queue()
            return@inheritedAction false
        }
        return@inheritedAction true
    }
    command("boob", "boobs") {
        val random = Random()
        action {
            val data = webJson<List<BoobData>>("http://api.oboobs.ru/boobs/${random.nextInt(10330) + 1}").await().firstOrNull()
            if (data == null) {
                messageAction(errorEmbed("Couldn't find any boobs! Please try again later.")).queue()
                return@action
            }
            messageAction(embed {
                title("Astolfo NSFW")
                image(data.url)
            }).queue()
        }
    }
    command("butt", "butts") {
        val random = Random()
        action {
            val data = webJson<List<ButtData>>("http://api.obutts.ru/butts/${random.nextInt(4335) + 1}").await().firstOrNull()
            if (data == null) {
                messageAction(errorEmbed("Couldn't find any butts! Please try again later.")).queue()
                return@action
            }
            messageAction(embed {
                title("Astolfo NSFW")
                image(data.url)
            }).queue()
        }
    }
    command("hentai", "nsfw") {
        action {
            val image = getImage(args, true)
            if (image == null) {
                messageAction(errorEmbed("Couldn't find any hentai! Please try again later.")).queue()
                return@action
            }
            messageAction(embed {
                title("Astolfo NSFW")
                image(image.fileUrl)
            }).queue()
        }
    }
}

class BoobData(val model: String?, val preview: String?, val id: Int?, val rank: Int?, val author: String?) {
    val url
        get() = "http://media.oboobs.ru/$preview"
}

class ButtData(val model: String?, val preview: String?, val id: Int?, val rank: Int?, val author: String?) {
    val url
        get() = "http://media.obutts.ru/$preview"
}