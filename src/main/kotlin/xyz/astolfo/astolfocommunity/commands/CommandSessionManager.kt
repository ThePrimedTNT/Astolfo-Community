package xyz.astolfo.astolfocommunity.commands

import io.sentry.Sentry
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.ClosedSendChannelException
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.channels.sendBlocking
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

class CommandSessionManager {

    private val worker = Worker()
    private val commandProcessorContext = newFixedThreadPoolContext(100, "Command Session Processor")

    init {
        launch(commandProcessorContext) {
            while (isActive) {
                try {
                    cleanUp()
                } catch (e: Exception) {
                    Sentry.capture(e)
                }
                delay(5, TimeUnit.MINUTES)
            }
        }
    }

    private val sessionMap: MutableMap<SessionKey, CommandSession> = ConcurrentHashMap()
    private val sessionJobs = mutableMapOf<SessionKey, Job>()

    data class SessionKey(val guildId: Long, val memberId: Long, val channelId: Long) {
        constructor(event: MessageReceivedEvent) : this(event.guild.idLong, event.author.idLong, event.channel.idLong)
    }

    fun get(event: MessageReceivedEvent): CommandSession? {
        val key = SessionKey(event)
        return sessionMap[key]
    }

    suspend fun session(event: MessageReceivedEvent, commandPath: String, sessionThread: suspend (CommandSession) -> Unit) {
        val key = SessionKey(event)
        worker.add(key) {
            invalidateNow(key)
            val newSession = CommandSessionImpl(commandPath)
            val sessionJob = launch(commandProcessorContext, start = CoroutineStart.LAZY) {
                withTimeout(1, TimeUnit.MINUTES) {
                    sessionThread.invoke(newSession)
                }
            }
            sessionJobs[key] = sessionJob
            sessionMap[key] = newSession
            sessionJob.start()
        }
    }

    suspend fun invalidate(event: MessageReceivedEvent) = invalidate(SessionKey(event))
    suspend fun invalidate(key: SessionKey) {
        worker.add(key) { invalidateNow(key) }
    }

    private suspend fun invalidateNow(key: SessionKey) {
        sessionMap.remove(key)?.destroy()
        sessionJobs.remove(key)?.cancelAndJoin()
    }

    private suspend fun cleanUp() {
        for ((key, session) in sessionMap.toMap()) {
            worker.add(key) {
                val job = sessionJobs[key]
                if (job?.isCompleted != false && session.getListeners().isEmpty())
                    invalidateNow(key)
            }
        }
    }

    /**
     * This workers task is to make sure only one job is running for each user.
     */
    class Worker {

        companion object {
            private val workerContext = newFixedThreadPoolContext(100, "Command Session Worker")
        }

        private val tasks = hashMapOf<SessionKey, WorkerTask>()

        private interface TaskEvent
        private class AddTaskEvent(val key: SessionKey, val task: suspend () -> Unit) : TaskEvent
        private object UpdateTaskEvent : TaskEvent

        private val taskActor = actor<TaskEvent>(context = workerContext, capacity = Channel.UNLIMITED) {
            for (event in channel) {
                handleEvent(event)
            }
        }

        private suspend fun handleEvent(event: TaskEvent) {
            when (event) {
                is AddTaskEvent -> {
                    handleEvent(UpdateTaskEvent)
                    val task = tasks.computeIfAbsent(event.key) { WorkerTask() }
                    task.add(event.task)
                }
                is UpdateTaskEvent -> {
                    val taskIterator = tasks.iterator()
                    while (taskIterator.hasNext()) {
                        val task = taskIterator.next().value
                        if (task.isDone()) {
                            task.destroy()
                            taskIterator.remove()
                        }
                    }
                }
            }
        }

        suspend fun <T> add(key: SessionKey, task: suspend () -> T): CompletableDeferred<T> {
            val future = CompletableDeferred<T>()
            taskActor.send(AddTaskEvent(key, {
                try {
                    future.complete(task())
                } catch (e: Throwable) {
                    future.completeExceptionally(e)
                }
            }))
            return future
        }

        class WorkerTask {

            private var destroyed = false

            private interface WorkerEvent
            private class AddTaskEvent(val task: suspend () -> Unit) : WorkerEvent
            private object UpdateTaskEvent : WorkerEvent

            private val taskQueue = LinkedBlockingDeque<suspend () -> Unit>()
            private val taskMutex = Mutex()
            private var runningJob: Job? = null

            private val workerActor = actor<WorkerEvent>(context = workerContext, capacity = Channel.UNLIMITED) {
                for (event in channel) {
                    if (destroyed) continue
                    taskMutex.withLock {
                        handleEvent(event)
                    }
                }
            }

            private suspend fun handleEvent(event: WorkerEvent) {
                when (event) {
                    is AddTaskEvent -> {
                        taskQueue.add(event.task)
                        handleEvent(UpdateTaskEvent)
                    }
                    is UpdateTaskEvent -> {
                        if (runningJob?.isActive == true) return // discard event if job is still running
                        // get next task to run
                        val nextTask = taskQueue.poll() ?: return
                        // start it
                        runningJob = launch(context = workerContext) { nextTask() }
                        // register a update task so next job starts right after this one
                        runningJob!!.invokeOnCompletion {
                            launch(workerContext) {
                                try {
                                    workerActor.send(UpdateTaskEvent)
                                }catch (e: ClosedSendChannelException){
                                    // Meh, idc
                                }
                            }
                        }
                    }
                }
            }

            suspend fun isDone() = taskMutex.withLock {
                taskQueue.isEmpty() && runningJob?.isCompleted != false
            }

            suspend fun add(task: suspend () -> Unit) = workerActor.send(AddTaskEvent(task))

            fun destroy() {
                destroyed = true
                workerActor.close()
            }
        }
    }
}

class CommandSessionImpl(override val commandPath: String) : CommandSession {

    private val listeners: MutableList<CommandSession.SessionListener> = CopyOnWriteArrayList()
    val parentJob = Job()

    private var destroyed = false

    override fun updatable(rate: Long, unit: TimeUnit, updater: (CommandSession) -> Unit) {
        if (destroyed) IllegalStateException("You cannot register an updater to a destroyed session!")
        launch(parent = parentJob) {
            while (isActive) {
                updater.invoke(this@CommandSessionImpl)
                delay(rate, unit)
            }
        }
    }

    override fun addListener(listener: CommandSession.SessionListener): Boolean {
        if (destroyed) IllegalStateException("You cannot register an listener to a destroyed session!")
        return listeners.add(listener)
    }

    override fun removeListener(listener: CommandSession.SessionListener) = listeners.remove(listener)
    override fun getListeners() = listeners.toList()

    override fun onMessageReceived(execution: CommandExecution): CommandSession.ResponseAction {
        var action = CommandSession.ResponseAction.RUN_COMMAND
        loop@ for (it in listeners.toList()) {
            val listenerAction = it.onMessageReceived(execution)
            when (listenerAction) {
                CommandSession.ResponseAction.UNREGISTER_LISTENER -> removeListener(it)
                CommandSession.ResponseAction.IGNORE_AND_UNREGISTER_LISTENER -> {
                    removeListener(it)
                    action = CommandSession.ResponseAction.IGNORE_COMMAND
                }
                CommandSession.ResponseAction.NOTHING -> continue@loop
                else -> action = listenerAction
            }
        }
        return action
    }

    override fun destroy() = runBlocking {
        destroyed = true
        listeners.forEach { it.onSessionDestroyed() }
        parentJob.cancelAndJoin()
    }
}

class InheritedCommandSession(override val commandPath: String) : CommandSession {

    private fun inheritedError(): Unit = TODO("Inherited Actions don't support command sessions!")

    override fun updatable(rate: Long, unit: TimeUnit, updater: (CommandSession) -> Unit) {
        inheritedError()
    }

    override fun addListener(listener: CommandSession.SessionListener): Boolean {
        inheritedError()
        return false
    }

    override fun removeListener(listener: CommandSession.SessionListener): Boolean {
        inheritedError()
        return false
    }

    override fun getListeners(): List<CommandSession.SessionListener> {
        inheritedError()
        return listOf()
    }

    override fun onMessageReceived(execution: CommandExecution): CommandSession.ResponseAction {
        inheritedError()
        return CommandSession.ResponseAction.RUN_COMMAND
    }

    override fun destroy() {
        inheritedError()
    }

}

interface CommandSession {
    val commandPath: String

    fun updatable(rate: Long, unit: TimeUnit = TimeUnit.SECONDS, updater: (CommandSession) -> Unit)

    fun addListener(listener: SessionListener): Boolean
    fun removeListener(listener: SessionListener): Boolean
    fun getListeners(): List<SessionListener>

    fun onMessageReceived(execution: CommandExecution): ResponseAction

    fun destroy()

    open class SessionListener {
        open fun onMessageReceived(execution: CommandExecution) = ResponseAction.NOTHING
        open fun onSessionDestroyed() {}
    }

    enum class ResponseAction {
        NOTHING,
        RUN_COMMAND,
        IGNORE_COMMAND,
        IGNORE_AND_UNREGISTER_LISTENER,
        UNREGISTER_LISTENER
    }
}