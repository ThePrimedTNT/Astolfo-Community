package xyz.astolfo.astolfocommunity.modules

import com.github.natanbc.reliqua.request.PendingRequest
import com.github.natanbc.weeb4j.image.HiddenMode
import com.github.natanbc.weeb4j.image.NsfwFilter
import com.oopsjpeg.osu4j.backend.EndpointUsers
import com.oopsjpeg.osu4j.backend.Osu
import org.jsoup.Jsoup
import xyz.astolfo.astolfocommunity.games.GameHandler
import xyz.astolfo.astolfocommunity.games.ShiritoriGame
import xyz.astolfo.astolfocommunity.games.SnakeGame
import xyz.astolfo.astolfocommunity.games.TetrisGame
import xyz.astolfo.astolfocommunity.menus.memberSelectionBuilder
import xyz.astolfo.astolfocommunity.messages.*
import xyz.astolfo.astolfocommunity.web
import xyz.astolfo.astolfocommunity.webJson
import xyz.astolfo.astolfocommunity.words
import java.math.BigInteger
import java.util.*
import kotlin.coroutines.experimental.suspendCoroutine

fun createFunModule() = module("Fun") {
    command("osu") {
        action {
            messageAction(embed {
                val osuPicture = "https://upload.wikimedia.org/wikipedia/commons/d/d3/Osu%21Logo_%282015%29.png"
                title("Astolfo Osu Integration")
                description("**sig**  -  generates an osu signature of the user" +
                        "\n**profile**  -  gets user data from the osu api")
                thumbnail(osuPicture)
            }).queue()
        }
        command("sig", "s") {
            action {
                val osuUsername = args
                messageAction(embed {
                    val url = "http://lemmmy.pw/osusig/sig.php?colour=purple&uname=$osuUsername&pp=1"
                    title("Astolfo Osu Signature", url)
                    description("$osuUsername\'s Osu Signature!")
                    image(url)
                }).queue()
            }
        }
        command("profile", "p", "user", "stats") {
            action {
                val osu = Osu.getAPI(application.properties.osu_api_token)
                val user = try {
                    osu.users.query(EndpointUsers.ArgumentsBuilder(args).build())
                } catch (e: Exception) {
                    messageAction(errorEmbed(":mag: I looked for `$args`, but couldn't find them!" +
                            "\n Try using the sig command instead.")).queue()
                    return@action
                }
                messageAction(embed {
                    val topPlayBeatmap = user.getTopScores(1).get().first().beatmap.get()
                    title("Osu stats for ${user.username}", user.url.toString())
                    description("\nProfile url: ${user.url}" +
                            "\nCountry: **${user.country}**" +
                            "\nGlobal Rank: **#${user.rank} (${user.pp}pp)**" +
                            "\nAccuracy: **${user.accuracy}%**" +
                            "\nPlay Count: **${user.playCount} (Lv${user.level})**" +
                            "\nTop play: **$topPlayBeatmap** ${topPlayBeatmap.url}")
                }).queue()
            }
        }
    }
    command("advice") {
        action {
            messageAction(embed("\uD83D\uDCD6 ${webJson<Advice>("http://api.adviceslip.com/advice").await().slip!!.advice}")).queue()
        }
    }
    command("cat", "cats") {
        action {
            messageAction(message(webJson<Cat>("http://aws.random.cat/meow", null).await().file!!)).queue()
        }
    }
    command("catgirl", "neko", "catgirls") {
        action {
            messageAction(message(webJson<Neko>("https://nekos.life/api/neko").await().neko!!)).queue()
        }
    }
    command("coinflip", "flip", "coin") {
        val random = Random()
        action {
            val flipMessage = messageAction(embed("Flipping a coin for you...")).sendCached()
            flipMessage.editMessage(embed("Coin landed on **${if (random.nextBoolean()) "Heads" else "Tails"}**"), 1L)
        }
    }
    command("roll", "die", "dice") {
        val random = Random()

        val ONE = BigInteger.valueOf(1)
        val SIX = BigInteger.valueOf(6)

        action {
            val parts = args.words()
            val (bound1, bound2) = when (parts.size) {
                0 -> ONE to SIX
                1 -> {
                    val to = parts[0].toBigIntegerOrNull()
                    (to?.signum() ?: 1).toBigInteger() to to
                }
                2 -> parts[0].toBigIntegerOrNull() to parts[1].toBigIntegerOrNull()
                else -> {
                    messageAction(errorEmbed("Invalid roll format! Accepted Formats: *<max>*, *<min> <max>*")).queue()
                    return@action
                }
            }

            if (bound1 == null || bound2 == null) {
                messageAction(errorEmbed("Only whole numbers are allowed for bounds!")).queue()
                return@action
            }

            val lowerBound = bound1.min(bound2)
            val upperBound = bound1.max(bound2)

            val diffBound = upperBound - lowerBound

            var randomNum: BigInteger
            do {
                randomNum = BigInteger(diffBound.bitLength(), random)
            } while (randomNum < BigInteger.ZERO || randomNum > diffBound)

            randomNum += lowerBound

            val rollingMessage = messageAction(embed(":game_die: Rolling a dice for you...")).sendCached()
            rollingMessage.editMessage(embed("Dice landed on **$randomNum**"), 1)
        }
    }
    command("8ball") {
        val random = Random()
        val responses = arrayOf("It is certain", "You may rely on it", "Cannot predict now", "Yes", "Reply hazy try again", "Yes definitely", "My reply is no", "Better not tell yo now", "Don't count on it", "Most likely", "Without a doubt", "As I see it, yes", "Outlook not so good", "Outlook good", "My sources say no", "Signs point to yes", "Very doubtful", "It is decidedly so", "Concentrate and ask again")
        action {
            val question = args
            if (question.isBlank()) {
                messageAction(embed(":exclamation: Make sure to ask a question next time. :)")).queue()
                return@action
            }
            messageAction(embed {
                title(":8ball: 8 Ball")
                field("Question", question, false)
                field("Answer", responses[random.nextInt(responses.size)], false)
            }).queue()
        }
    }
    command("csshumor", "cssjoke", "cssh") {
        action {
            messageAction(embed("```css" +
                    "\n${Jsoup.parse(web("https://csshumor.com/").await()).select(".crayon-code").text()}" +
                    "\n```")).queue()
        }
    }
    command("cyanideandhappiness", "cnh") {
        val random = Random()
        action {
            val r = random.nextInt(4665) + 1
            val imageUrl = Jsoup.parse(web("http://explosm.net/comics/$r/").await())
                    .select("#main-comic").first()
                    .attr("src")
                    .let { if (it.startsWith("//")) "https:$it" else it }
            messageAction(embed {
                title("Cyanide and Happiness")
                image(imageUrl)
            }).queue()
        }
    }
    command("dadjoke", "djoke", "dadjokes", "djokes") {
        action {
            messageAction(embed("\uD83D\uDCD6 **Dadjoke:** ${webJson<DadJoke>("https://icanhazdadjoke.com/").await().joke!!}")).queue()
        }
    }
    command("hug") {
        action {
            val selectedMember = memberSelectionBuilder(args).title("Hug Selection").execute() ?: return@action
            val image = application.weeb4J.imageProvider.getRandomImage("hug", HiddenMode.DEFAULT, NsfwFilter.NO_NSFW).await()
            messageAction(embed {
                description("${event.author.asMention} has hugged ${selectedMember.asMention}")
                image(image.url)
                footer("Powered by weeb.sh")
            }).queue()
        }
    }
    command("kiss") {
        action {
            val selectedMember = memberSelectionBuilder(args).title("Kiss Selection").execute() ?: return@action
            val image = application.weeb4J.imageProvider.getRandomImage("kiss", HiddenMode.DEFAULT, NsfwFilter.NO_NSFW).await()
            messageAction(embed {
                description("${event.author.asMention} has kissed ${selectedMember.asMention}")
                image(image.url)
                footer("Powered by weeb.sh")
            }).queue()
        }
    }
    command("slap") {
        action {
            val selectedMember = memberSelectionBuilder(args).title("Slap Selection").execute() ?: return@action
            val image = application.weeb4J.imageProvider.getRandomImage("slap", HiddenMode.DEFAULT, NsfwFilter.NO_NSFW).await()
            messageAction(embed {
                description("${event.author.asMention} has slapped ${selectedMember.asMention}")
                image(image.url)
                footer("Powered by weeb.sh")
            }).queue()
        }
    }
    command("game") {
        inheritedAction {
            val currentGame = GameHandler.get(event.channel.idLong, event.author.idLong)
            if (currentGame != null) {
                if (args.equals("stop", true)) {
                    currentGame.endGame()
                    messageAction(embed("Current game has stopped!")).queue()
                } else {
                    messageAction(errorEmbed("To stop the current game you're in, type `?game stop`")).queue()
                }
                false
            } else true
        }
        action {
            messageAction(embed {
                title("Astolfo Game Help")
                description("**snake**  -  starts a game of snake!\n" +
                        "**tetris** - starts a game of tetris!\n" +
                        "**shiritori [easy/normal/hard/impossible]** - starts a game of shiritori!\n\n" +
                        "**stop** - stops the current game your playing")
            }).queue()
        }
        command("snake") {
            action {
                messageAction("Starting the game of snake...").queue()
                GameHandler.start(event.channel.idLong, event.author.idLong, SnakeGame(event.member, event.channel))
            }
        }
        command("tetris") {
            action {
                messageAction("Starting the game of tetris...").queue()
                GameHandler.start(event.channel.idLong, event.author.idLong, TetrisGame(event.member, event.channel))
            }
        }
        command("shiritori") {
            action {
                if (GameHandler.getAll(event.channel.idLong).any { it is ShiritoriGame }) {
                    messageAction(errorEmbed("Only one game of Shiritori is allowed per channel!")).queue()
                    return@action
                }
                val difficulty = args.takeIf { it.isNotBlank() }?.let { string ->
                    val choosen = ShiritoriGame.Difficulty.values().find { string.equals(it.name, true) }
                    if (choosen == null) {
                        messageAction(errorEmbed("Unknown difficulty! Valid difficulties: **Easy**, **Normal**, **Hard**, **Impossible**")).queue()
                        return@action
                    }
                    choosen
                } ?: ShiritoriGame.Difficulty.NORMAL
                messageAction("Starting the game of Shiritori with difficulty **${difficulty.name.toLowerCase().capitalize()}**...").queue()
                GameHandler.start(event.channel.idLong, event.author.idLong, ShiritoriGame(event.member, event.channel, difficulty))
            }
        }
    }
}

class Advice(val slip: AdviceSlip?) {
    inner class AdviceSlip(val advice: String?, @Suppress("unused") val slip_id: String?)
}

class Cat(val file: String?)
class Neko(val neko: String?)
class DadJoke(val id: String?, val status: Int?, var joke: String?)

suspend inline fun <E> PendingRequest<E>.await() = suspendCoroutine<E> { cont ->
    async({ cont.resume(it) }, { cont.resumeWithException(it) })
}