package xyz.astolfo.astolfocommunity.games

import com.markozajc.akiwrapper.Akiwrapper
import com.markozajc.akiwrapper.AkiwrapperBuilder
import com.markozajc.akiwrapper.core.entities.Guess
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.channels.sendBlocking
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import xyz.astolfo.astolfocommunity.levenshteinDistance
import xyz.astolfo.astolfocommunity.messages.*
import java.util.concurrent.TimeUnit
import kotlin.math.max


class AkinatorGame(member: Member, channel: TextChannel) : Game(member, channel) {

    companion object {
        private val akinatorContext = newFixedThreadPoolContext(30, "Akinator")

        private val answerMap: Map<String, Answer>
        private val yesNoList = listOf(Answer.YES, Answer.NO)

        init {
            answerMap = listOf(
                    Answer.YES to listOf("Y", "yes"),
                    Answer.NO to listOf("N", "no"),
                    Answer.DONT_KNOW to listOf("DK", "dont know", "don't know"),
                    Answer.PROBABLY to listOf("P", "probably"),
                    Answer.PROBABLY_NOT to listOf("PN", "probably not"),
                    Answer.UNDO to listOf("U", "B", "undo", "back")
            ).map {
                val answer = it.first
                it.second.map { it to answer }
            }.flatten().toMap()
        }

    }

    private enum class Answer(val akiAnswer: Akiwrapper.Answer) {
        YES(Akiwrapper.Answer.YES),
        NO(Akiwrapper.Answer.NO),
        DONT_KNOW(Akiwrapper.Answer.DONT_KNOW),
        PROBABLY(Akiwrapper.Answer.PROBABLY),
        PROBABLY_NOT(Akiwrapper.Answer.PROBABLY_NOT),
        UNDO(Akiwrapper.Answer.PROBABLY_NOT)
    }

    private val jdaListener = object : ListenerAdapter() {
        override fun onMessageReceived(event: MessageReceivedEvent) {
            if (event.author.idLong != member.user.idLong || event.channel.idLong != channel.idLong) return

            ankinatorActor.sendBlocking(AkinatorEvent.MessageEvent(event.message.contentRaw))
        }
    }

    private lateinit var akiWrapper: Akiwrapper
    private var akiState = State.ASKING
    private val hasGuessed = mutableListOf<String>()

    private var timeoutJob: Job? = null

    private var questionMessage: CachedMessage? = null
    private var errorMessage: CachedMessage? = null

    private enum class State {
        ASKING,
        GUESS,
        ITERATING
    }

    private sealed class AkinatorEvent {
        object StartEvent : AkinatorEvent()
        class MessageEvent(val message: String) : AkinatorEvent()
        object NoMoreQuestions : AkinatorEvent()
        object NextQuestion : AkinatorEvent()
        object DestroyEvent : AkinatorEvent()
        object TimeoutEvent : AkinatorEvent()
    }

    private val ankinatorActor = actor<AkinatorEvent>(context = akinatorContext, capacity = Channel.UNLIMITED) {
        for (event in this.channel) {
            if (destroyed) continue
            handleEvent(event)
        }
        // Dispose messages
        handleEvent(AkinatorEvent.DestroyEvent)
    }

    private suspend fun handleEvent(event: AkinatorEvent) {
        when (event) {
            is AkinatorEvent.StartEvent -> {
                akiWrapper = AkiwrapperBuilder().build()
                channel.jda.addEventListener(jdaListener)
                handleEvent(AkinatorEvent.NextQuestion)
            }
            is AkinatorEvent.MessageEvent -> {
                val rawContent = event.message
                val bestMatch = answerMap.map { it to it.key.levenshteinDistance(rawContent, true) }
                        .filter {
                            if (akiState == State.GUESS) yesNoList.contains(it.first.value)
                            else true
                        }
                        .sortedBy { it.second }.first()
                println("$rawContent -> ${bestMatch.first.key} W: ${bestMatch.second}")
                errorMessage?.delete()
                errorMessage = null
                if (bestMatch.second >= max(1, bestMatch.first.key.length / 5)) {
                    errorMessage = channel.sendMessage(embed("Unknown answer!")).sendCached()
                    return
                }
                timeoutJob?.cancelAndJoin()
                timeoutJob = null
                val answer = bestMatch.first.value
                var bestGuess: Guess? = null
                when (akiState) {
                    State.GUESS, State.ITERATING -> {
                        if (answer == Answer.YES) {
                            channel.sendMessage("Nice! Im glad I got it correct.").queue()
                            endGame()
                            return
                        } else {
                            bestGuess = getGuess()
                            if (bestGuess == null) {
                                when (akiState) {
                                    State.GUESS -> {
                                        channel.sendMessage("Aww, here are some more questions to narrow the result.").queue()
                                        akiState = State.ASKING
                                    }
                                    State.ITERATING -> {
                                        // defeat
                                        handleEvent(AkinatorEvent.NoMoreQuestions)
                                        return
                                    }
                                    else -> TODO("Not supposed to happen")
                                }
                            }
                        }
                    }
                    State.ASKING -> {
                        if (answer == Answer.UNDO) {
                            akiWrapper.undoAnswer()
                            questionMessage?.delete()
                            questionMessage = null
                            return
                        }
                        if (akiWrapper.answerCurrentQuestion(answer.akiAnswer) == null) {
                            handleEvent(AkinatorEvent.NoMoreQuestions)
                            return
                        }
                        bestGuess = getGuess()
                    }
                }
                if (bestGuess != null) {
                    hasGuessed += bestGuess.name
                    akiState = State.GUESS
                    ask(bestGuess)
                    return
                }
                handleEvent(AkinatorEvent.NextQuestion)
            }
            is AkinatorEvent.NextQuestion -> {
                val question = akiWrapper.currentQuestion
                if (question == null) {
                    handleEvent(AkinatorEvent.NoMoreQuestions)
                    return
                }
                println("G: ${question.gain} P: ${question.progression} S: ${question.step} Q: ${question.question}")
                questionMessage = channel.sendMessage(embed("**#${question.step + 1}** ${question.question}\n(Answer: yes/no/don't know/probably/probably not or undo)")).sendCached()
                timeoutJob?.cancelAndJoin()
                timeoutJob = launch {
                    delay(5, TimeUnit.MINUTES)
                    ankinatorActor.send(AkinatorEvent.TimeoutEvent)
                }
            }
            is AkinatorEvent.NoMoreQuestions -> {
                akiState = State.ITERATING
                val bestGuess = getGuess()

                if (bestGuess == null) {
                    channel.sendMessage("Aww, you have defeated me!").queue()
                    endGame()
                    return
                }

                hasGuessed += bestGuess.name
                ask(bestGuess)
            }
            is AkinatorEvent.TimeoutEvent -> {
                channel.sendMessage("Akinator automatically ended since you didnt repond in time!").queue()
                endGame()
            }
            is AkinatorEvent.DestroyEvent -> {
                channel.jda.removeEventListener(jdaListener)

                errorMessage?.delete()
                timeoutJob?.cancel()

                timeoutJob = null
                questionMessage = null
                errorMessage = null
            }
        }
    }

    private suspend fun ask(bestGuess: Guess) {
        channel.sendMessage(embed {
            description("Is **${bestGuess.name}** correct?\n(Answer: yes/no)")
            try {
                image(bestGuess.image.toString())
            } catch (e: IllegalArgumentException) { // ignore
            }
        }).queue()
        timeoutJob?.cancelAndJoin()
        timeoutJob = launch {
            delay(5, TimeUnit.MINUTES)
            ankinatorActor.send(AkinatorEvent.TimeoutEvent)
        }
    }

    private fun getGuess() = akiWrapper.getGuessesAboveProbability(0.85)
            .filter { !hasGuessed.contains(it.name) }
            .sortedByDescending { it.probability }.firstOrNull()

    override suspend fun start() {
        super.start()
        ankinatorActor.send(AkinatorEvent.StartEvent)
    }

    override suspend fun destroy() {
        super.destroy()
        ankinatorActor.close()
    }

}