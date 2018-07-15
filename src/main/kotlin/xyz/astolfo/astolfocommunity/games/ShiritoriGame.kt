package xyz.astolfo.astolfocommunity.games

import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import org.springframework.core.io.ClassPathResource
import xyz.astolfo.astolfocommunity.messages.*
import java.lang.Math.max
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.pow
import kotlin.streams.toList


class ShiritoriGame(gameHandler: GameHandler, member: Member, channel: TextChannel, private val difficulty: Difficulty) : Game(gameHandler, member, channel) {

    companion object {
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

    enum class Difficulty(val responseTime: Long) {
        EASY(15), NORMAL(10), HARD(8), IMPOSSIBLE(5)
    }

    private val wordPool = words.filter {
        when (difficulty) {
            Difficulty.EASY -> it.length in 4..6
            Difficulty.NORMAL -> it.length in 5..8
            Difficulty.HARD -> it.length >= 5
            Difficulty.IMPOSSIBLE -> it.length >= 6
        }
    }

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

    private val listener = object : ListenerAdapter() {
        override fun onMessageReceived(event: MessageReceivedEvent?) {
            if (event!!.author.idLong != member.user.idLong || event.channel.idLong != channel.idLong) return

            lastWarning?.delete()
            lastWarning = null

            val currentTurn = this@ShiritoriGame.currentTurn

            if (currentTurn.member.user.idLong != event.author.idLong) {
                lastWarning = channel.sendMessage(embed("It is not your turn yet!")).sendCached()
                return
            }

            val wordInput = event.message.contentRaw.toLowerCase().replace(wordFilter, "")
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

            processMove(wordInput)
        }
    }

    private fun processMove(wordInput: String) {
        usedWords.add(wordInput)

        val timeLeft = max(0, 15 * 1000 - (System.currentTimeMillis() - lastWordTime))

        val lengthBonus = max(0, wordInput.length - 4)
        val timeBonus = timeLeft / 1000.0
        val moveScore = lengthBonus + timeBonus
        currentTurn.score -= moveScore

        val score = currentTurn.score
        if (score <= 0) {
            processWin()
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
            computerDelay = launch {
                delay(((1 - random.nextDouble().pow(2)) * difficulty.responseTime).toLong(), TimeUnit.SECONDS)
                thinkingMessage?.delete()
                thinkingMessage = null
                channel.sendMessage(chosenWord).queue()
                processMove(chosenWord)
                computerDelay = null
            }
        }
    }

    private fun processWin() {
        channel.sendMessage(embed("${currentTurn.name} has won!")).queue()
        endGame()
    }

    override fun start() {
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

        channel.jda.addEventListener(listener)
    }

    override fun destroy() {
        channel.jda.removeEventListener(listener)
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