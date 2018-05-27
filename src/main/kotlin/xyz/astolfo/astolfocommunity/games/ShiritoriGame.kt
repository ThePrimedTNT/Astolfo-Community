package xyz.astolfo.astolfocommunity.games

import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import org.springframework.core.io.ClassPathResource
import xyz.astolfo.astolfocommunity.*
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
    private var turn = Turn.PLAYER_1
    private val scoreMap = mutableMapOf<Turn, Double>()
    private var startLetter = ('a'..'z').toList().let { it[random.nextInt(it.size)] }
    private var lastWordTime = 0L
    private var infoMessage: AsyncMessage? = null
    private var lastWarning: AsyncMessage? = null
    private var scoreboardMessage: AsyncMessage? = null

    private var computerDelay: Job? = null

    // TODO change to a list or something to add group shiritori support
    enum class Turn {
        PLAYER_1,
        COMPUTER
    }

    private val listener = object : ListenerAdapter() {
        override fun onMessageReceived(event: MessageReceivedEvent?) {
            if (event!!.author.idLong != member.user.idLong || event.channel.idLong != channel.idLong) return

            lastWarning?.delete()
            lastWarning = null

            if (turn != Turn.PLAYER_1) {
                lastWarning = channel.sendMessage(embed("It is not your turn yet!")).sendAsync()
                return
            }

            val wordInput = event.message.contentRaw.toLowerCase().replace(wordFilter, "")
            if (wordInput.length < 4) {
                lastWarning = channel.sendMessage(embed("Word *must be at least* ***4 or more*** *letters long*! You said **$wordInput**")).sendAsync()
                return
            }

            if (!wordInput.startsWith(startLetter)) {
                lastWarning = channel.sendMessage(embed("Word must start with **$startLetter** You said: **$wordInput**")).sendAsync()
                return
            }

            if (!words.contains(wordInput)) {
                lastWarning = channel.sendMessage(embed("Unknown word... Make sure its a noun or verb! You said: **$wordInput**")).sendAsync()
                return
            }

            if (usedWords.contains(wordInput)) {
                lastWarning = channel.sendMessage(embed("That word has already been used! You said: **$wordInput**")).sendAsync()
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
        scoreMap[turn] = scoreMap[turn]!! - moveScore

        val score = scoreMap[turn]!!
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
            field("Scores", scoreMap.map { entry -> "*${entry.key.name.replace("_", " ").toLowerCase().capitalize()}:* **${Math.ceil(entry.value).toInt()}**" }.joinToString(separator = "\n"), true)
        }).sendAsync()

        turn = if (turn == Turn.PLAYER_1) Turn.COMPUTER
        else Turn.PLAYER_1

        if (turn == Turn.COMPUTER) {
            val thinkingMessage = channel.sendMessage("Thinking...").sendAsync()
            var choosenWord: String? = null
            while (choosenWord == null || usedWords.contains(choosenWord)) choosenWord = wordPool[(random.nextDouble().pow(2) * wordPool.size).toInt()]
            computerDelay = launch {
                delay(((1 - random.nextDouble().pow(2)) * difficulty.responseTime).toLong(), TimeUnit.SECONDS)
                thinkingMessage.delete()
                channel.sendMessage(choosenWord).queue()
                processMove(choosenWord)
                computerDelay = null
            }
        }
    }

    private fun processWin() {
        channel.sendMessage(embed("${turn.name.replace("_", " ").toLowerCase().capitalize()} has won!")).queue()
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
        }).sendAsync()
        scoreboardMessage = channel.sendMessage("You may go first, can be any word that is 4 letters or longer and starting with the letter **$startLetter**!").sendAsync()
        lastWordTime = System.currentTimeMillis()
        turn = Turn.PLAYER_1

        Turn.values().forEach { scoreMap[it] = 100.0 }

        channel.jda.addEventListener(listener)
    }

    override fun destroy() {
        channel.jda.removeEventListener(listener)
        infoMessage?.delete()
        lastWarning?.delete()
        scoreboardMessage?.delete()
        computerDelay?.cancel()
        scoreboardMessage = null
        infoMessage = null
        lastWarning = null
        computerDelay = null
    }

}