package xyz.astolfo.astolfocommunity.commands

import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class CommandSessionImpl(override val commandPath: String) : CommandSession {

    private val listeners: MutableList<CommandSession.SessionListener> = CopyOnWriteArrayList()
    val parentJob = Job()

    private var destroyed = false

    override fun updatable(rate: Long, unit: TimeUnit, updater: (CommandSession) -> Unit) {
        if (destroyed) IllegalStateException("You cannot register an updater to a destroyed session!")
        (GlobalScope + parentJob).launch {
            while (isActive) {
                updater.invoke(this@CommandSessionImpl)
                delay(unit.toMillis(rate))
            }
        }
    }

    override fun addListener(listener: CommandSession.SessionListener): Boolean {
        if (destroyed) IllegalStateException("You cannot register an listener to a destroyed session!")
        return listeners.add(listener)
    }

    override fun removeListener(listener: CommandSession.SessionListener) = listeners.remove(listener)
    override fun getListeners() = listeners.toList()

    override fun onMessageReceived(execution: CommandContext): CommandSession.ResponseAction {
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

    private fun inheritedError(): NotImplementedError =
        throw NotImplementedError("Inherited Actions don't support command sessions!")

    override fun updatable(rate: Long, unit: TimeUnit, updater: (CommandSession) -> Unit) {
        throw inheritedError()
    }

    override fun addListener(listener: CommandSession.SessionListener): Boolean {
        throw inheritedError()
    }

    override fun removeListener(listener: CommandSession.SessionListener): Boolean {
        throw inheritedError()
    }

    override fun getListeners(): List<CommandSession.SessionListener> {
        throw inheritedError()
    }

    override fun onMessageReceived(execution: CommandContext): CommandSession.ResponseAction {
        throw inheritedError()
    }

    override fun destroy() {
        throw inheritedError()
    }

}

interface CommandSession {
    val commandPath: String

    fun updatable(rate: Long, unit: TimeUnit = TimeUnit.SECONDS, updater: (CommandSession) -> Unit)

    fun addListener(listener: SessionListener): Boolean
    fun removeListener(listener: SessionListener): Boolean
    fun getListeners(): List<SessionListener>

    fun onMessageReceived(execution: CommandContext): ResponseAction

    fun destroy()

    open class SessionListener {
        open fun onMessageReceived(execution: CommandContext) = ResponseAction.NOTHING
        @Deprecated("Use suspending functions instead")
        open fun onSessionDestroyed() {
        }
    }

    enum class ResponseAction {
        NOTHING,
        RUN_COMMAND,
        IGNORE_COMMAND,
        IGNORE_AND_UNREGISTER_LISTENER,
        UNREGISTER_LISTENER
    }
}