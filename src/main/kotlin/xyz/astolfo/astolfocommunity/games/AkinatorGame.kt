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
                // connect to server and start game
                akiWrapper = AkiwrapperBuilder().build()
                channel.jda.addEventListener(jdaListener)
                // start the game off by asking a question
                handleEvent(AkinatorEvent.NextQuestion)
            }
            is AkinatorEvent.MessageEvent -> {
                resetTimeout() // reset timeout (this is only if there's no activity happening
                // clean up last error message (since its no longer important)
                errorMessage?.delete()
                errorMessage = null

                val rawContent = event.message
                // go through all possible response choices and select the most similar
                val bestMatch = answerMap.map { it to it.key.levenshteinDistance(rawContent, true) }
                        .filter {
                            // Only allow No and Yes for guesses
                            if (akiState == State.GUESS) yesNoList.contains(it.first.value)
                            else true
                        }
                        .sortedBy { it.second }.first()
                // check if response is close enough to closest possible result
                if (bestMatch.second >= max(1, bestMatch.first.key.length / 4)) {
                    errorMessage = channel.sendMessage(embed("Unknown answer!")).sendCached()
                    return
                }
                // stop timeout since we got a valid response
                stopTimeout()
                // Do stuff with answer
                val answer = bestMatch.first.value
                var bestGuess: Guess? = null
                when (akiState) {
                    State.GUESS, State.ITERATING -> {
                        // If the bot is guessing
                        if (answer == Answer.YES) {
                            // Guess was correct. end game
                            channel.sendMessage("Nice! Im glad I got it correct.").queue()
                            endGame()
                            return
                        } else {
                            // Guess was wrong
                            // Find next guess
                            bestGuess = getGuess()
                            if (bestGuess == null) {
                                // No more guesses left
                                when (akiState) {
                                    State.GUESS -> {
                                        // Still has questions to ask (Only can guess normally if next question is valid)
                                        channel.sendMessage("Aww, here are some more questions to narrow the result.").queue()
                                        akiState = State.ASKING
                                    }
                                    State.ITERATING -> {
                                        // No more questions to ask, defeat
                                        handleEvent(AkinatorEvent.NoMoreQuestions)
                                        return
                                    }
                                    else -> TODO("Not supposed to happen")
                                }
                            }
                        }
                    }
                    State.ASKING -> {
                        // bot is asking a question to user
                        if (answer == Answer.UNDO) {
                            // undo and remove current question
                            akiWrapper.undoAnswer()
                            questionMessage?.delete()
                            questionMessage = null
                            resetTimeout()
                            return
                        }
                        // send result back to akinator
                        if (akiWrapper.answerCurrentQuestion(answer.akiAnswer) == null) {
                            // No more questions left
                            handleEvent(AkinatorEvent.NoMoreQuestions)
                            return
                        }
                        // populate best guess with guess after the answer
                        bestGuess = getGuess()
                    }
                }
                if (bestGuess != null) {
                    // If the bot has a good enough guess, ask it
                    hasGuessed += bestGuess.name
                    akiState = State.GUESS
                    ask(bestGuess)
                    return
                }
                // If no conditions met just ask the next question
                handleEvent(AkinatorEvent.NextQuestion)
            }
            is AkinatorEvent.NextQuestion -> {
                // Get the next question
                val question = akiWrapper.currentQuestion
                if (question == null) {
                    // If somehow the question is null, usually happens when it runs out of questions to ask and guess score is too low
                    handleEvent(AkinatorEvent.NoMoreQuestions)
                    return
                }
                // ask it and start timeout
                questionMessage = channel.sendMessage(embed("**#${question.step + 1}** ${question.question}\n(Answer: yes/no/don't know/probably/probably not or undo)")).sendCached()
                resetTimeout()
            }
            is AkinatorEvent.NoMoreQuestions -> {
                // No more questions, enter the iterating state
                akiState = State.ITERATING
                val bestGuess = getGuess()

                if (bestGuess == null) {
                    // No more guesses, defeat
                    channel.sendMessage("Aww, you have defeated me!").queue()
                    endGame()
                    return
                }
                // Ask the next guess
                hasGuessed += bestGuess.name
                ask(bestGuess)
            }
            is AkinatorEvent.TimeoutEvent -> {
                // Timeout met
                channel.sendMessage("Akinator automatically ended since you didnt repond in time!").queue()
                endGame()
            }
            is AkinatorEvent.DestroyEvent -> {
                // Destroy
                channel.jda.removeEventListener(jdaListener)

                errorMessage?.delete()
                stopTimeout(false)

                questionMessage = null
                errorMessage = null
            }
        }
    }

    private suspend fun ask(bestGuess: Guess) {
        // ask guess and start timeout
        channel.sendMessage(embed {
            description("Is **${bestGuess.name}** correct?\n(Answer: yes/no)")
            try {
                image(bestGuess.image.toString())
            } catch (e: IllegalArgumentException) { // ignore
            }
        }).queue()
        resetTimeout()
    }

    private suspend fun stopTimeout(join: Boolean = false) {
        if (join) timeoutJob?.cancelAndJoin() else timeoutJob?.cancel()
        timeoutJob = null
    }

    private suspend fun resetTimeout() {
        stopTimeout()
        timeoutJob = launch {
            delay(5, TimeUnit.MINUTES)
            ankinatorActor.send(AkinatorEvent.TimeoutEvent)
        }
    }

    /**
     * Get guess with probability greater then 85% and grab guess with highest probability
     */
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