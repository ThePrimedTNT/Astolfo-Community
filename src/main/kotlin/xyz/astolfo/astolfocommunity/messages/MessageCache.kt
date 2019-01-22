package xyz.astolfo.astolfocommunity.messages

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.dv8tion.jda.core.MessageBuilder
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Emote
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.MessageEmbed
import net.dv8tion.jda.core.exceptions.ErrorResponseException
import net.dv8tion.jda.core.requests.RequestFuture
import net.dv8tion.jda.core.requests.RestAction
import net.dv8tion.jda.core.requests.restaction.MessageAction
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

object MessageCache {

    private val messageCacheContext = newFixedThreadPoolContext(50, "MessageCache")
    private val messageReferenceQueue = ReferenceQueue<CachedMessage>()

    private val cachedMessageMutex = Mutex()
    private val cachedMessageMap = mutableMapOf<Long, MessageReference>()

    var cachedMessageCount = 0
        private set

    init {
        GlobalScope.launch(messageCacheContext) {
            while (isActive) {
                delay(TimeUnit.SECONDS.toMillis(1))
                clearDeletedMessages()
            }
        }
    }

    private suspend fun clearDeletedMessages() {
        while (true) {
            val removedRef = messageReferenceQueue.poll() ?: return
            cachedMessageMutex.withLock {
                val messageRef = removedRef as MessageReference
                //println("Removed reference of ${messageRef.messageId}")
                cachedMessageMap.remove(messageRef.messageId)
                cachedMessageCount = cachedMessageMap.size
            }
        }
    }

    fun sendCached(restAction: RestAction<Message>): CachedMessage {
        val completableDeferred = restAction.submit().toCompletableDeferred()
        val cachedMessage = CachedMessage(completableDeferred)
        GlobalScope.launch(messageCacheContext) {
            val result = withTimeout(TimeUnit.MINUTES.toMillis(1)) {
                completableDeferred.await()
            }
            cachedMessageMutex.withLock {
                //println("Saved Message ${result.idLong}")
                cachedMessageMap[result.idLong] = MessageReference(result.idLong, cachedMessage)
                cachedMessageCount = cachedMessageMap.size
            }
        }
        return cachedMessage
    }

    private class MessageReference(val messageId: Long, cachedMessage: CachedMessage) :
        WeakReference<CachedMessage>(cachedMessage, messageReferenceQueue)

}

fun MessageAction.sendCached() = MessageCache.sendCached(this)
fun <T : Any?> RequestFuture<T>.toCompletableDeferred(parent: Job? = null): CompletableDeferred<T> {
    val completableDeferred = CompletableDeferred<T>(parent)
    whenComplete { result, error ->
        try {
            if (error == null) completableDeferred.complete(result)
            else completableDeferred.completeExceptionally(error)
        } catch (e: Throwable) {
            completableDeferred.completeExceptionally(e)
        }
    }
    return completableDeferred
}

class CachedMessage(messageDeferred: CompletableDeferred<Message>) {

    companion object {
        private val cachedContext = newFixedThreadPoolContext(100, "Cached Message")
    }

    private val messageMutex = Mutex()
    private lateinit var message: Message

    var isCreated = false
        private set

    var isDeleted = false
        private set

    private val editManager = EditManager()
    private val emoteManager = EmoteManager()

    init {
        GlobalScope.launch(cachedContext) {
            // Lock the use of the message until its created
            // TODO handle failed creations
            messageMutex.withLock {
                try {
                    withTimeout(TimeUnit.MINUTES.toMillis(1)) {
                        message = messageDeferred.await()
                    }
                } catch (e: Throwable) {
                    isDeleted = true
                }
                isCreated = true
            }
        }
    }

    /**
     * Use a lock to wait for the creation of the message
     */
    private fun <T> internalWaitForMessage(block: (Message) -> T): CompletableDeferred<T> {
        if (isDeleted) error("You cannot edit a deleted message!")
        return if (isCreated) CompletableDeferred(block(message))
        else {
            val completableDeferred = CompletableDeferred<T>()
            GlobalScope.launch(cachedContext) {
                messageMutex.withLock {
                    if (isDeleted) return@launch
                    try {
                        completableDeferred.complete(block(message))
                    } catch (e: Throwable) {
                        completableDeferred.completeExceptionally(e)
                    }
                }
            }
            completableDeferred
        }
    }

    val idLong: CompletableDeferred<Long>
        get() {
            if (isCreated) CompletableDeferred(message.idLong)
            return internalWaitForMessage { it.idLong }
        }

    // Edit Message Methods

    fun editMessage(newContent: String, delay: Long = 0, unit: TimeUnit = TimeUnit.SECONDS) =
        editMessage(MessageBuilder().content(newContent).build(), delay, unit)

    fun editMessage(newContent: MessageEmbed, delay: Long = 0, unit: TimeUnit = TimeUnit.SECONDS) =
        editMessage(MessageBuilder().setEmbed(newContent).build(), delay, unit)

    fun editMessage(newContent: Message, delay: Long = 0, unit: TimeUnit = TimeUnit.SECONDS) =
        editManager.edit(newContent, delay, unit)

    // Reactions

    fun addReaction(unicode: String, delay: Long = 0, unit: TimeUnit = TimeUnit.SECONDS) =
        emoteManager.addReaction(unicode, delay, unit)

    fun addReaction(emote: Emote, delay: Long = 0, unit: TimeUnit = TimeUnit.SECONDS) =
        emoteManager.addReaction(emote, delay, unit)

    fun clearReactions(delay: Long = 0, unit: TimeUnit = TimeUnit.SECONDS) =
        emoteManager.clearReactions(delay, unit)

    // Misc

    fun delete() = GlobalScope.launch(cachedContext) {
        editManager.dispose()
        internalWaitForMessage { msg ->
            if (!msg.hasPermission(Permission.MESSAGE_READ)) return@internalWaitForMessage
            msg.delete().queue()
            isDeleted = true
        }
    }

    inner class EditManager {

        private val contentMutex = Mutex()
        private var newContent: Message? = null
        private var contentQueued: Job? = null

        fun edit(newContent: Message, delay: Long, delayUnit: TimeUnit) {
            if (delay <= 0) runBlocking(cachedContext) { editInternal(newContent) }
            else GlobalScope.launch(cachedContext) {
                delay(delayUnit.toMillis(delay))
                editInternal(newContent)
            }
        }

        /**
         * Attempt to cancel the current edit job and start a new one
         */
        private suspend fun editInternal(newContent: Message) {
            contentMutex.withLock {
                if (this.newContent == newContent) return
                this.newContent = newContent
                contentQueued?.cancelAndJoin()
                val mainJob = Job()
                val completableDeferred = internalWaitForMessage {
                    it.editMessage(newContent).submit().toCompletableDeferred(mainJob)
                }.await()
                (GlobalScope + mainJob).launch(context = cachedContext) {
                    withTimeout(TimeUnit.MINUTES.toMillis(1)) {
                        muteUnknownError {
                            val newMessage = completableDeferred.await()
                            messageMutex.withLock {
                                message = newMessage!!
                            }
                        }
                    }
                }
                contentQueued = mainJob
            }
        }

        suspend fun dispose() {
            contentMutex.withLock {
                contentQueued?.cancel()
            }
        }
    }

    // TODO finish this class
    inner class EmoteManager {

        fun addReaction(unicode: String, delay: Long, delayUnit: TimeUnit) =
            addReaction(EmoteActionKey(0, unicode), delay, delayUnit)

        fun addReaction(emote: Emote, delay: Long, delayUnit: TimeUnit) =
            addReaction(EmoteActionKey(0, emote = emote), delay, delayUnit)

        private fun addReaction(key: EmoteActionKey, delay: Long, delayUnit: TimeUnit) = runBlocking(cachedContext) {
            internalWaitForMessage { msg ->
                if (!msg.hasPermission(Permission.MESSAGE_HISTORY)) return@internalWaitForMessage
                val emote = key.emote
                val unicode = key.unicode
                val action = if (emote != null) msg.addReaction(emote) else msg.addReaction(unicode!!)
                if (delay <= 0) action.queue()
                else action.queueAfter(delay, delayUnit)
            }.await()
        }

        fun clearReactions(delay: Long, delayUnit: TimeUnit) {
            internalWaitForMessage { msg ->
                if (!msg.hasPermission(Permission.MESSAGE_MANAGE)) return@internalWaitForMessage

                val action = msg.clearReactions()
                if (delay <= 0) action.queue()
                else action.queueAfter(delay, delayUnit)
            }
        }

    }

    data class EmoteActionKey(
        val user: Long,
        val unicode: String? = null,
        val emote: Emote? = null
    )
}

private suspend inline fun muteUnknownError(crossinline block: suspend () -> Unit) {
    try {
        block()
    } catch (e: ErrorResponseException) {
        if (e.errorCode == 10008 || e.errorCode == 10003) {
            println("Error: ${e.errorCode}")
        } else {
            throw e
        }
    }
}