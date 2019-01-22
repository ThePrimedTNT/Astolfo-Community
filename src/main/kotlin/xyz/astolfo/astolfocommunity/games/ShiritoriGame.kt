package xyz.astolfo.astolfocommunity.games

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.sendBlocking
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.entities.User
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import org.springframework.core.io.ClassPathResource
import xyz.astolfo.astolfocommunity.messages.*
import java.lang.Math.max
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.streams.toList


class ShiritoriGame(member: Member, channel: TextChannel, private val difficulty: Difficulty) : Game(member, channel) {

    companion object {
        private val shiritoriContext = newFixedThreadPoolContext(30, "Shiritori")

        private val words: List<String>
        private val random = Random()

        private val wordFilter = Regex("[^a-zA-Z]+")

        init {
            val wordRegex = Regex("\\w+")
            words = ClassPathResource("twl06.txt").inputStream.bufferedReader().use { it.lines().toList() }
                    .filter { it.isNotBlank() && it.matches(wordRegex) && it.length >= 4 }
                    .sorted()
        }
    }

    enum class Difficulty(val responseTime: Long, val wordLengthRange: IntRange) {
        EASY(15, 4..6),
        NORMAL(10, 5..8),
        HARD(8, 5..Int.MAX_VALUE),
        IMPOSSIBLE(5, 6..Int.MAX_VALUE)
    }

    private val wordPool = words.filter { it.length in difficulty.wordLengthRange }

    private val usedWords = mutableListOf<String>()
    private var startLetter = ('a'..'z').toList().let { it[random.nextInt(it.size)] }
    private var lastWordTime = 0L
    private var infoMessage: CachedMessage? = null
    private var lastWarning: CachedMessage? = null
    private var thinkingMessage: CachedMessage? = null
    private var scoreboardMessage: CachedMessage? = null

    private var computerDelay: Job? = null

    private var turnId = 0
    private val players = listOf(Player(member, 100.0), Player(channel.guild.selfMember, 100.0))
    private val currentTurn
        get() = players[turnId]

    class Player(val member: Member, var score: Double) {
        val name: String
            get() = member.effectiveName
    }

    private val jdaListener = object : ListenerAdapter() {
        override fun onMessageReceived(event: MessageReceivedEvent) {
            if (event.author.idLong != member.user.idLong || event.channel.idLong != channel.idLong) return

            shiritoriActor.sendBlocking(MessageEvent(event.author, event.message.contentRaw))
        }
    }

    private interface ShiritoriEvent
    private object StartEvent : ShiritoriEvent
    private class MessageEvent(val author: User, val message: String) : ShiritoriEvent
    private class MoveEvent(val move: String) : ShiritoriEvent
    private object WinEvent : ShiritoriEvent
    private object DestroyEvent : ShiritoriEvent

    private val shiritoriActor = GlobalScope.actor<ShiritoriEvent>(context = shiritoriContext, capacity = Channel.UNLIMITED) {
        for (event in this.channel) {
            if (destroyed) continue
            handleEvent(event)
        }
        // Dispose messages
        handleEvent(DestroyEvent)
    }

    private suspend fun handleEvent(event: ShiritoriEvent) {
        when (event) {
            is StartEvent -> {
                infoMessage = channel.sendMessage(embed {
                    title("Astolfo Shiritori")
                    description("When the game *starts* you are given a **random letter**. You must pick a word *beginning* with that *letter*." +
                            "After you pick your word, the bot will pick another word starting with the **last part** of your *word*." +
                            "Then you will play against the bot till your **score reaches zero**, starting at *100*." +
                            "First one to **zero points wins**!" +
                            "\n" +
                            "\n__**Words must:**__" +
                            "\n- Be in the *dictionary*" +
                            "\n- Have at least ***4*** *letters*" +
                            "\n- Have *not* been *used*" +
                            "\n" +
                            "\n__**Points:**__" +
                            "\n- *Length Bonus:* Number of letters *minus four*" +
                            "\n- *Speed Bonus:* **15 seconds** minus time it took")
                }).sendCached()
                scoreboardMessage = channel.sendMessage("You may go first, can be any word that is 4 letters or longer and starting with the letter **$startLetter**!").sendCached()
                lastWordTime = System.currentTimeMillis()

                channel.jda.addEventListener(jdaListener)
            }
            is MessageEvent -> {
                lastWarning?.delete()
                lastWarning = null

                val currentTurn = this@ShiritoriGame.currentTurn

                if (currentTurn.member.user.idLong != event.author.idLong) {
                    lastWarning = channel.sendMessage(embed("It is not your turn yet!")).sendCached()
                    return
                }

                val wordInput = event.message.toLowerCase().replace(wordFilter, "")
                if (wordInput.length < 4) {
                    lastWarning = channel.sendMessage(embed("Word *must be at least* ***4 or more*** *letters long*! You said **$wordInput**")).sendCached()
                    return
                }

                if (!wordInput.startsWith(startLetter)) {
                    lastWarning = channel.sendMessage(embed("Word must start with **$startLetter** You said: **$wordInput**")).sendCached()
                    return
                }

                if (!words.contains(wordInput)) {
                    lastWarning = channel.sendMessage(embed("Unknown word... Make sure its a noun or verb! You said: **$wordInput**")).sendCached()
                    return
                }

                if (usedWords.contains(wordInput)) {
                    lastWarning = channel.sendMessage(embed("That word has already been used! You said: **$wordInput**")).sendCached()
                    return
                }

                handleEvent(MoveEvent(wordInput))
            }
            is MoveEvent -> {
                val wordInput = event.move

                usedWords.add(wordInput)

                val timeLeft = max(0, 15 * 1000 - (System.currentTimeMillis() - lastWordTime))

                val lengthBonus = max(0, wordInput.length - 4)
                val timeBonus = timeLeft / 1000.0
                val moveScore = lengthBonus + timeBonus
                currentTurn.score -= moveScore

                val score = currentTurn.score
                if (score <= 0) {
                    handleEvent(WinEvent)
                    return
                }

                startLetter = wordInput.last()
                lastWordTime = System.currentTimeMillis()

                scoreboardMessage?.delete()
                scoreboardMessage = channel.sendMessage(embed {
                    description("*Word:* **$wordInput**")
                    field("Breakdown", "*Time:* **${Math.ceil(timeBonus).toInt()}** (*${Math.ceil(timeLeft / 1000.0).toInt()}s left*)" +
                            "\n*Length:* **$lengthBonus** (*${wordInput.length} letters*)" +
                            "\n*Total:* **${Math.ceil(moveScore).toInt()}**", true)
                    field("Scores", players.joinToString(separator = "\n") { "*${it.name}:* **${Math.ceil(it.score).toInt()}**" }, true)
                }).sendCached()

                if (turnId + 1 >= players.size) turnId = 0
                else turnId++

                if (currentTurn.member.user.idLong == channel.guild.selfMember.user.idLong) {
                    thinkingMessage = channel.sendMessage("Thinking...").sendCached()
                    val chosenWord = wordPool.filter { it.startsWith(startLetter) && !usedWords.contains(it) }.let { it[(random.nextDouble().pow(2) * it.size).toInt()] }
                    computerDelay?.cancel()
                    computerDelay = GlobalScope.launch(shiritoriContext) {
                        delay(TimeUnit.SECONDS.toMillis(((1 - random.nextDouble().pow(2)) * difficulty.responseTime).toLong()))
                        thinkingMessage?.delete()
                        thinkingMessage = null
                        channel.sendMessage(chosenWord).queue()
                        shiritoriActor.send(MoveEvent(chosenWord))
                    }
                }
            }
            is WinEvent -> {
                channel.sendMessage(embed("${currentTurn.name} has won!")).queue()
                endGame()
            }
            is DestroyEvent -> {
                channel.jda.removeEventListener(jdaListener)
                infoMessage?.delete()
                lastWarning?.delete()
                scoreboardMessage?.delete()
                thinkingMessage?.delete()
                computerDelay?.cancel()

                thinkingMessage = null
                scoreboardMessage = null
                infoMessage = null
                lastWarning = null
                computerDelay = null
            }
        }
    }

    override suspend fun start() {
        super.start()
        shiritoriActor.send(StartEvent)
    }

    override suspend fun destroy() {
        super.destroy()
        shiritoriActor.close()
    }

}