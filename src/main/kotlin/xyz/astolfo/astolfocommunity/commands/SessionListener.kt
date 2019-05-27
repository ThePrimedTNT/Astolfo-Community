package xyz.astolfo.astolfocommunity.commands

import io.sentry.Sentry
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.newFixedThreadPoolContext
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.messages.errorEmbed
import xyz.astolfo.astolfocommunity.modules.modules

class SessionListener(
    val application: AstolfoCommunityApplication,
    val channelListener: ChannelListener
) {

    companion object {
        internal val sessionContext = newFixedThreadPoolContext(50, "Session Processor")
        internal val commandContext = newFixedThreadPoolContext(100, "CommandData Processor")
    }

    private var destroyed = false

    suspend fun addMessage(guildMessageData: GuildListener.GuildMessageData) =
        sessionActor.send(MessageEvent(guildMessageData))

    suspend fun addCommand(guildMessageData: GuildListener.GuildMessageData) =
        sessionActor.send(CommandEvent(guildMessageData))

    private interface SessionEvent
    private class MessageEvent(val guildMessageData: GuildListener.GuildMessageData) : SessionEvent
    private class CommandEvent(val guildMessageData: GuildListener.GuildMessageData) : SessionEvent
    private object CleanUp : SessionEvent

    private val sessionActor = GlobalScope.actor<SessionEvent>(context = sessionContext, capacity = Channel.UNLIMITED) {
        for (event in channel) {
            if (destroyed) continue
            try {
                handleEvent(event)
            } catch (e: Throwable) {
                e.printStackTrace()
                Sentry.capture(e)
            }
        }
        handleEvent(CleanUp)
    }

    private var currentSession: CommandSession? = null
    private var sessionJob: Job? = null

    private suspend fun handleEvent(event: SessionEvent) {
        when (event) {
            is CleanUp -> {
                currentSession?.destroy()
                sessionJob?.cancelAndJoin()
                currentSession = null
                sessionJob = null
            }
            is MessageEvent -> {
//                val currentSession = this.currentSession ?: return
//                // TODO add rate limit
//                //if (!processRateLimit(event)) return@launch
//                val guildMessageData = event.guildMessageData
//                val jdaEvent = guildMessageData.messageReceivedEvent
//                val execution = CommandDataExecution(
//                    application,
//                    jdaEvent,
//                    currentSession,
//                    currentSession.commandPath,
//                    jdaEvent.message.contentRaw,
//                    guildMessageData.timeIssued
//                )
//                if (currentSession.onMessageReceived(execution) == CommandSession.ResponseAction.RUN_COMMAND) {
//                    // If the response listeners return true or all the response listeners removed themselves
//                    handleEvent(CleanUp)
//                }
            }
            is CommandEvent -> {
                val guildMessageData = event.guildMessageData
                val jdaEvent = guildMessageData.messageReceivedEvent
                val member = jdaEvent.member
                val channel = jdaEvent.channel

                val guildSettings = application.astolfoRepositories.getEffectiveGuildSettings(jdaEvent.guild.idLong)
                val channelBlacklisted = guildSettings.blacklistedChannels.contains(channel.idLong)

                val rawContent = jdaEvent.message.contentRaw!!
                val prefixMatched = guildMessageData.prefixMatched
                val isMention = prefixMatched.startsWith("<@")

                var commandMessage = rawContent.substring(prefixMatched.length).trim()

                val context = CommandContext(
                    application,
                    "",
                    commandMessage,
                    mutableMapOf(
                        CommandContext.DATA_KEY_PREFIX to prefixMatched,
                        CommandContext.DATA_KEY_EVENT to jdaEvent,
                        CommandContext.DATA_KEY_ISSUED to guildMessageData.timeIssued
                    )
                )

                for (module in modules) {
                    if (module.execute(context)) {
                        break
                    }
                }
//
//                var commandNodes = resolvePath(commandMessage)
//
//                var checkedRateLimit = false
//
//                if (commandNodes == null) {
//                    if (channelBlacklisted) return // Ignore chat bot if channel is blacklisted
//                    if (!isMention) return
//                    if (!checkPatreonBot(guildMessageData)) return
//                    if (!processRateLimit(jdaEvent)) return
//                    checkedRateLimit = true
//                    // Not a command but rather a chat bot message
//                    if (commandMessage.isEmpty()) {
//                        channel.sendMessage("Hi :D").queue()
//                        return
//                    }
//                    if (commandMessage.contains("prefix", true)) {
//                        channel.sendMessage(
//                            "Yahoo! My prefix in this guild is **${guildSettings.getEffectiveGuildPrefix(
//                                application
//                            )}**!"
//                        ).queue()
//                        return
//                    }
//                    val chatBotManager = channelListener.guildListener.messageListener.chatBotManager
//
//                    val response = chatBotManager.process(member, commandMessage)
//                    if (response.type == ChatResponse.ResponseType.COMMAND) {
//                        commandMessage = response.response
//                        commandNodes = resolvePath(commandMessage)
//                        if (commandNodes == null) return // cancel the command
//                    } else {
//                        channel.sendMessage(response.response).queue()
//                        return
//                    }
//                } else {
//                    if (!checkPatreonBot(guildMessageData)) return
//                }
//                // Only allow Admin module if blacklisted
//                if (channelBlacklisted) {
//                    val module = commandNodes.first
//                    if (!module.name.equals("Admin", true)) return
//                }
//
//                if (!checkedRateLimit && !processRateLimit(jdaEvent)) return
//
//                // Process CommandData
//                application.statsDClient.incrementCounter("commands_executed")
//
//                if (!channel.hasPermission(Permission.MESSAGE_EMBED_LINKS)) {
//                    channel.sendMessage("Please enable **embed links** to use Astolfo commands.").queue()
//                    return
//                }
//
//                fun createExecution(session: CommandSession, commandPath: String, commandContent: String) =
//                    CommandDataExecution(
//                        application,
//                        jdaEvent,
//                        session,
//                        commandPath,
//                        commandContent,
//                        guildMessageData.timeIssued
//                    )
//
//                val module = commandNodes.first
//
//                val moduleExecution = createExecution(InheritedCommandSession(commandMessage), "", commandMessage)
//                if (!module.inheritedActions.all { it.invoke(moduleExecution) }) return
//
//                // Go through all the nodes in the command path and check permissions/actions
//                for ((command, commandPath, commandContent) in commandNodes.second) {
//                    // PERMISSIONS
//                    val permission = command.permission
//
//                    var hasPermission: Boolean? = if (member.hasPermission(Permission.ADMINISTRATOR)) true else null
//                    // Check discord path if the member isn't a admin already
//                    if (hasPermission != true && permission.discordOverrides.isNotEmpty())
//                        hasPermission = member.hasPermission(channel, permission.discordOverrides)
//                    // Check Astolfo path if discord path didn't already grant permissions
//                    if (hasPermission != true)
//                        AstolfoPermissionUtils.hasPermission(
//                            member,
//                            channel,
//                            application.astolfoRepositories.getEffectiveGuildSettings(jdaEvent.guild.idLong).permissions,
//                            permission
//                        )?.let { hasPermission = it }
//
//                    if (hasPermission == false) {
//                        channel.sendMessage(
//                            errorEmbed(
//                                "You are missing the astolfo **${permission.path}**${if (permission.discordOverrides.isNotEmpty())
//                                    " or discord ${permission.discordOverrides.joinToString(", ") { "**${it.getName()}**" }}" else ""} path(s)"
//                            )
//                        )
//                            .queue()
//                        return
//                    }
//
//                    // INHERITED ACTIONS
//                    val inheritedExecution =
//                        createExecution(InheritedCommandSession(commandPath), commandPath, commandContent)
//                    if (!command.inheritedActions.all { it.invoke(inheritedExecution) }) return
//                }
//                // COMMAND ENDPOINT
//                val (command, commandPath, commandContent) = commandNodes.second.last()
//
//                suspend fun runNewSession() {
//                    handleEvent(CleanUp)
//                    application.statsDClient.incrementCounter("commandExecuteCount", "command:$commandPath")
//                    currentSession = CommandSessionImpl(commandPath)
//                    val execution = createExecution(currentSession!!, commandPath, commandContent)
//                    sessionJob = GlobalScope.launch(commandContext) {
//                        withTimeout(TimeUnit.MINUTES.toMillis(1)) {
//                            command.action(execution)
//                        }
//                    }
//                }
//
//                val currentSession = this.currentSession
//
//                // Checks if command is the same as the previous, if so, check if its a follow up response
//                if (currentSession != null && currentSession.commandPath.equals(commandPath, true)) {
//                    val action =
//                        currentSession.onMessageReceived(createExecution(currentSession, commandPath, commandContent))
//                    when (action) {
//                        CommandSession.ResponseAction.RUN_COMMAND -> {
//                            runNewSession()
//                        }
//                        CommandSession.ResponseAction.IGNORE_COMMAND -> {
//                        }
//                        else -> TODO("Invalid action: $action")
//                    }
//                } else {
//                    runNewSession()
//                }
            }
        }
    }

    private fun checkPatreonBot(data: GuildListener.GuildMessageData): Boolean {
        if (!application.properties.patreon_bot) return true
        val staffIds = application.staffMemberIds
        if (staffIds.contains(data.messageReceivedEvent.author.idLong)) return true
        val donorGuild = application.donationManager.getByMember(data.messageReceivedEvent.guild.owner)
        if (!donorGuild.patreonBot) {
            data.messageReceivedEvent.channel.sendMessage(
                errorEmbed("In order to use the high quality patreon bot, the owner of your guild must pledge at least $10 on [patreon.com/theprimedtnt](https://www.patreon.com/theprimedtnt)")
            ).queue()
            return false
        }
        return true
    }

    private suspend fun processRateLimit(event: GuildMessageReceivedEvent): Boolean {
        val rateLimiter = channelListener.guildListener.messageListener.commandRateLimiter
        val user = event.author.idLong
        val wasLimited = rateLimiter.isLimited(user)
        rateLimiter.add(user)
        if (wasLimited) return false
        if (rateLimiter.isLimited(user)) {
            event.channel.sendMessage("${event.member.asMention} You have been ratelimited! Please wait a little and try again!")
                .queue()
            return false
        }
        return true
    }

//    private fun resolvePath(commandMessage: String): Pair<Module, List<PathNode>>? {
//        for (module in modules) return module to (resolvePath(module.commands, "", commandMessage) ?: continue)
//        return null
//    }
//
//    private fun resolvePath(commands: List<CommandData>, commandPath: String, commandMessage: String): List<PathNode>? {
//        val (commandName, commandContent) = commandMessage.splitFirst(" ")
//
//        val command = commands.findByName(commandName) ?: return null
//
//        val newCommandPath = "$commandPath ${command.name}".trim()
//        val commandNode = PathNode(command, newCommandPath, commandContent)
//
//        if (commandContent.isBlank()) return listOf(commandNode)
//
//        val subPath = resolvePath(command.subCommands, newCommandPath, commandContent) ?: listOf(commandNode)
//        return listOf(commandNode, *subPath.toTypedArray())
//    }

    data class PathNode(val command: Command, val commandPath: String, val commandContent: String)

    fun dispose() {
        destroyed = true
        sessionActor.close()
    }

}