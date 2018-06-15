package xyz.astolfo.astolfocommunity.commands

import kotlinx.coroutines.experimental.*
import net.dv8tion.jda.core.events.message.MessageReceivedEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class CommandSessionManager {

    private val worker = Worker()
    private val commandProcessorContext = newFixedThreadPoolContext(100, "Command Session Processor")

    private val sessionMap: MutableMap<SessionKey, CommandSession> = ConcurrentHashMap()
    private val sessionJobs = mutableMapOf<SessionKey, Job>()

    data class SessionKey(val guildId: Long, val memberId: Long, val channelId: Long) {
        constructor(event: MessageReceivedEvent) : this(event.guild.idLong, event.author.idLong, event.channel.idLong)
    }

    fun get(event: MessageReceivedEvent): CommandSession? {
        val key = SessionKey(event)
        return sessionMap[key]
    }

    fun session(event: MessageReceivedEvent, commandPath: String, sessionThread: suspend (CommandSession) -> Unit) {
        val key = SessionKey(event)
        worker.add(key) {
            invalidateNow(key)
            val newSession = CommandSessionImpl(commandPath)
            val sessionJob = launch(commandProcessorContext, start = CoroutineStart.LAZY) {
                withTimeout(1, TimeUnit.MINUTES) {
                    sessionThread.invoke(newSession)
                }
                if(isActive) invalidate(key)
            }
            sessionJobs[key] = sessionJob
            sessionMap[key] = newSession
            sessionJob.start()
        }
    }

    fun invalidate(event: MessageReceivedEvent) = invalidate(SessionKey(event))
    fun invalidate(key: SessionKey) {
        worker.add(key) { invalidateNow(key) }
    }

    private suspend fun invalidateNow(key: SessionKey) {
        sessionMap.remove(key)?.destroy()
        sessionJobs.remove(key)?.join()
    }

    fun cleanUp() {
        sessionMap.toMap().forEach { key, session ->
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
    class Worker(threadCount: Int = 100) {
        private val tasks = hashMapOf<SessionKey, WorkerTask>()
        private val context = newFixedThreadPoolContext(threadCount, "Command Session Worker")

        private fun updateTasks() {
            synchronized(tasks) {
                val taskIterator = tasks.iterator()
                while (taskIterator.hasNext()) {
                    val task = taskIterator.next().value
                    task.update()
                    if (task.isDone) taskIterator.remove()
                }
            }
        }

        fun add(key: SessionKey, job: suspend () -> Unit) {
            synchronized(tasks) {
                val task = tasks.computeIfAbsent(key, { WorkerTask() })
                task.add(launch(context, start = CoroutineStart.LAZY) { job.invoke() })
            }
            updateTasks()
        }

        suspend fun <T> add(key: SessionKey, job: suspend () -> T): T {
            val future = async(context, start = CoroutineStart.LAZY) { job.invoke() }
            synchronized(tasks) {
                val jobList = tasks.computeIfAbsent(key, { WorkerTask() })
                jobList.add(future)
            }
            updateTasks()
            return future.await()
        }

        class WorkerTask {
            private val queuedJobs = mutableListOf<Job>()
            private var runningJobs: Job? = null

            val isDone
                get() = queuedJobs.isEmpty() && runningJobs?.isCompleted != false

            fun add(job: Job): Boolean {
                synchronized(queuedJobs) {
                    return queuedJobs.add(job)
                }
            }

            fun update() {
                synchronized(queuedJobs) {
                    // Ignore since job is still running
                    if (runningJobs?.isCompleted == false) return
                    // Ignore if there are no more jobs left
                    if (queuedJobs.isEmpty()) return
                    val newJob = queuedJobs.removeAt(0)
                    newJob.invokeOnCompletion {
                        runningJobs = null
                        update()
                    }
                    runningJobs = newJob
                    newJob.start()
                }
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