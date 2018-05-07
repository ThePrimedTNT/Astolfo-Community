package xyz.astolfo.astolfocommunity

import com.oopsjpeg.osu4j.GameMode
import com.oopsjpeg.osu4j.backend.EndpointUsers
import com.oopsjpeg.osu4j.backend.Osu
import net.dv8tion.jda.core.MessageBuilder
import org.jsoup.Jsoup
import java.awt.Color
import java.util.*
import java.util.concurrent.TimeUnit

fun createFunModule() = module("Fun") {
    command("osu") {
        val purpleEmbedColor = Color(119, 60, 138)
        action {
            message(embed {
                val osuPicture = "https://upload.wikimedia.org/wikipedia/commons/d/d3/Osu%21Logo_%282015%29.png"
                color(purpleEmbedColor)
                title("Astolfo Osu Integration")
                description("**sig**  -  generates an osu signature of the user" +
                        "\n**profile**  -  gets user data from the osu api")
                thumbnail(osuPicture)
            }).queue()
        }
        command("sig") {
            action {
                val osuUsername = args
                message(embed {
                    val url = "http://lemmmy.pw/osusig/sig.php?colour=purple&uname=$osuUsername&pp=1"
                    color(purpleEmbedColor)
                    title("Astolfo Osu Signature", url)
                    description("$osuUsername\'s Osu Signature!")
                    image(url)
                }).queue()
            }
        }
        command("profile") {
            action {
                message(embed {
                    val osu = Osu.getAPI(application.properties.osu_api_token)
                    val user = osu.users.query(EndpointUsers.ArgumentsBuilder(args)
                            .setMode(GameMode.STANDARD)
                            .build())
                    val topPlayBeatmap = user.getTopScores(1).get()[0].beatmap.get()
                    color(purpleEmbedColor)
                    title("Osu stats for ${user.username}", user.url.toString())

                    description("\nProfile url: ${user.url}" +
                            "\nGlobal Rank: **#${user.rank} (${user.pp}pp)**" +
                            "\nAccuracy: **${user.accuracy}%**" +
                            "\nTotal score: **${user.totalScore}**" +
                            "\nTop play: **$topPlayBeatmap** ${topPlayBeatmap.url}")
                }).queue()
            }
        }
    }
    command("advice") {
        action {
            message(embed {
                description("\uD83D\uDCD6 ${webJson<Advice>("http://api.adviceslip.com/advice")!!.slip!!.advice}")
            }).queue()
        }
    }
    command("cat") {
        action {
            message(webJson<Cat>("http://aws.random.cat/meow")!!.file!!).queue()
        }
    }
    command("catgirl") {
        action {
            message(webJson<Neko>("https://nekos.life/api/neko")!!.neko!!).queue()
        }
    }
    command("coinflip") {
        val random = Random()
        action {
            message("Flipping a coin for you...").queue {
                it.editMessage(MessageBuilder().append("Coin landed on **${if (random.nextBoolean()) "Heads" else "Tails"}**").build()).queueAfter(1, TimeUnit.SECONDS)
            }
        }
    }
    command("csshumor") {
        action {
            message(embed {
                description("```css" +
                        "\n${Jsoup.parse(web("https://csshumor.com/")).select(".crayon-code").text()}" +
                        "\n```")
            }).queue()
        }
    }
    command("cyanideandhappiness") {
        val random = Random()
        action {
            val r = random.nextInt(4665) + 1
            message(embed {
                title("Cyanide and Happiness")
                image(Jsoup.parse(web("http://explosm.net/comics/$r/"))
                        .select("#main-comic").first()
                        .attr("src")
                        .let { if (it.startsWith("//")) "https:$it" else it })
            }).queue()
        }
    }
    command("dadjoke") {
        action {
            message(embed { description("\uD83D\uDCD6 **Dadjoke:** ${webJson<DadJoke>("https://icanhazdadjoke.com/")!!.joke!!}") }).queue()
        }
    }
}

class Advice(val slip: AdviceSlip?) {
    inner class AdviceSlip(val advice: String?, val slip_id: String?)
}

class Cat(val file: String?)
class Neko(val neko: String?)
class DadJoke(val id: String?, val status: Int?, var joke: String?)