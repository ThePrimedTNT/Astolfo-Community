package xyz.astolfo.astolfocommunity.modules.music

import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.utils.PermissionUtil
import xyz.astolfo.astolfocommunity.*
import xyz.astolfo.astolfocommunity.commands.CommandBuilder
import xyz.astolfo.astolfocommunity.commands.CommandExecution
import xyz.astolfo.astolfocommunity.menus.paginator
import xyz.astolfo.astolfocommunity.menus.provider
import xyz.astolfo.astolfocommunity.menus.renderer
import xyz.astolfo.astolfocommunity.menus.selectionBuilder
import xyz.astolfo.astolfocommunity.modules.command
import xyz.astolfo.astolfocommunity.modules.module
import xyz.astolfo.astolfocommunity.support.SupportLevel
import xyz.astolfo.astolfocommunity.support.supportBuilder
import java.util.concurrent.TimeUnit


fun createMusicModule() = module("Music") {
    createGuildPlaylistCommands()
    command("join", "j") {
        musicAction { joinAction(true) }
    }
    command("play", "p", "search", "yt", "q", "queue") {
        musicAction {
            // Make the play command work like the join command as well
            if (!joinAction()) return@musicAction
            val guild = event.guild
            val searchQuery = MusicUtils.getEffectiveSearchQuery(args)
            if (searchQuery == null) {
                messageAction("Either im not allowed to play music from that website or I do not support it!").queue()
                return@musicAction
            }
            if (!searchQuery.search) {
                val musicSession = application.musicManager.getMusicSession(guild)!!
                musicSession.musicLoader.add(searchQuery, event.channel)
                return@musicAction
            }
            runWhileSessionActive {
                val trackResponse = tempMessage(message { embed("\uD83D\uDD0E Searching for **$args**...") }) {
                    application.musicManager.audioPlayerManager.loadItemSync(searchQuery.query)
                }
                val audioPlaylist = trackResponse.first as AudioPlaylist?
                val exception = trackResponse.second
                if (audioPlaylist != null && audioPlaylist.isSearchResult) {
                    // If the track returned is a list of tracks and are from a ytsearch: or scsearch:
                    val selectedTrack = selectMusic(audioPlaylist.tracks).execute() ?: return@runWhileSessionActive
                    application.musicManager.getMusicSession(guild)?.let {
                        it.boundChannel = event.message.textChannel
                        it.queue(selectedTrack)
                    }
                    messageAction(embed { description("[${selectedTrack.info.title}](${selectedTrack.info.uri}) has been added to the queue") }).queue()
                } else if (exception != null) {
                    messageAction("Failed due to an error: **${exception.message}**").queue()
                } else {
                    messageAction("No matches found for **$args**").queue()
                }
            }
        }
    }
    command("radio") {
        musicAction {
            // Make the radio command work like the join command as well
            if (!joinAction()) return@musicAction
            val guild = event.guild
            val results = tempMessage(message("Searching radio stations for $args...")) {
                application.astolfoRepositories.findRadioStation(args)
            }

            val selectedRadio = selectionBuilder<RadioEntry>()
                    .title("\uD83D\uDD0E Radio Search Results:")
                    .results(results)
                    .noResultsMessage("No results!")
                    .resultsRenderer { "`${it.id}` **${it.name}**" }
                    .renderer {
                        message {
                            embed {
                                titleProvider.invoke()?.let { title(it) }
                                val string = providedContent.joinToString(separator = "\n")
                                description("Type the number of the station you want\n$string")
                                footer("Page ${currentPage + 1}/${provider.pageCount}")
                            }
                        }
                    }.execute() ?: return@musicAction

            val (audioItem, audioException) = tempMessage(message { embed("\uD83D\uDD0E Loading radio **${selectedRadio.name}**...") }) {
                application.musicManager.audioPlayerManager.loadItemSync(selectedRadio.url)
            }
            if (audioItem != null && audioItem is AudioTrack) {
                application.musicManager.getMusicSession(guild)?.let {
                    it.boundChannel = event.message.textChannel
                    it.queue(audioItem)
                }
                messageAction(embed { description("**${selectedRadio.name}** has been added to the queue") }).queue()
            } else if (audioException != null) {
                messageAction("Failed due to an error: **${audioException.message}**").queue()
            } else {
                messageAction("Unknown error occurred while loading **${selectedRadio.name}** radio station").queue()
            }
        }
    }
    command("leave", "l", "disconnect") {
        musicAction {
            application.musicManager.stopMusicSession(event.guild)
            messageAction("I have disconnected").queue()
        }
    }
    command("playing", "nowplaying", "np") {
        musicAction(memberInVoice = false, activeSession = true) {
            val musicSession = application.musicManager.getMusicSession(event.guild)!!
            val paginator = paginator("Astolfo-Community Music Queue") {
                provider(8, {
                    val songs = musicSession.songQueue
                    val repeatedSongs = musicSession.repeatSongQueue
                    if (songs.isEmpty() && repeatedSongs.isEmpty()) listOf("No songs in queue")
                    else listOf(songs, repeatedSongs).flatten().map { audioTrack ->
                        "${if (repeatedSongs.contains(audioTrack)) "\uD83D\uDD04 " else ""}[${audioTrack.info.title}](${audioTrack.info.uri}) **${Utils.formatSongDuration(audioTrack.info.length, audioTrack.info.isStream)}**"
                    }
                })
                renderer {
                    message {
                        embed {
                            titleProvider.invoke()?.let { title(it) }
                            val currentTrack = musicSession.player.playingTrack
                            field("\uD83C\uDFB6 Now Playing" + if (currentTrack != null) " - ${Utils.formatSongDuration(musicSession.player.trackPosition)}/${Utils.formatSongDuration(currentTrack.info.length, currentTrack.info.isStream)}" else "", false) {
                                if (currentTrack == null) {
                                    "No song currently playing"
                                } else {
                                    "[${currentTrack.info.title}](${currentTrack.info.uri})"
                                }
                            }
                            field("\uD83C\uDFBC Queue", false) { providedString }
                            footer("Page ${currentPage + 1}/${provider.pageCount}")
                        }
                    }
                }
            }
            updatable(7, TimeUnit.SECONDS) { paginator.render() }
        }
    }
    command("skip", "s") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getMusicSession(event.guild)!!
            val amountToSkip = args.takeIf { it.isNotBlank() }?.let {
                val amountNum = it.toBigIntegerOrNull()?.toInt()
                if (amountNum == null) {
                    messageAction("Amount to skip must be a whole number!").queue()
                    return@musicAction
                }
                if (amountNum < 1) {
                    messageAction("Amount to skip must be a greater then zero!").queue()
                    return@musicAction
                }
                amountNum
            } ?: 1
            val songsSkipped = musicSession.skip(amountToSkip)
            messageAction(embed {
                when {
                    songsSkipped.isEmpty() -> description("No songs where skipped.")
                    songsSkipped.size == 1 -> {
                        val skippedSong = songsSkipped.first()
                        description("⏩ [${skippedSong.info.title}](${skippedSong.info.uri}) was skipped.")
                    }
                    else -> description("⏩ ${songsSkipped.size} songs where skipped")
                }
            }).queue()
        }
    }
    command("volume", "v") {
        supportBuilder {
            upvote(2).longTermUpvoteMessage("Due to performance reasons, you must upvote the bot to use this feature!")
            supportLevel(SupportLevel.SUPPORTER)
        }
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getMusicSession(event.guild)!!
            val newVolume = args.takeIf { it.isNotBlank() }?.let {
                val amountNum = it.toBigIntegerOrNull()?.toInt()
                if (amountNum == null) {
                    messageAction("The new volume must be a whole number!").queue()
                    return@musicAction
                }
                if (amountNum < 5) {
                    messageAction("The new volume must be at least 5%!").queue()
                    return@musicAction
                }
                if (amountNum > 150) {
                    messageAction("The new volume must be no more than 150%!").queue()
                    return@musicAction
                }
                amountNum
            }
            if (newVolume == null) {
                val currentVolume = musicSession.player.volume
                messageAction(embed { description("Current volume is **$currentVolume%**!") }).queue()
            } else {
                val oldVolume = musicSession.player.volume
                musicSession.player.volume = newVolume
                messageAction(embed { description("${volumeIcon(newVolume)} Volume has changed from **$oldVolume%** to **$newVolume%**") }).queue()
            }
        }
    }
    command("seek") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getMusicSession(event.guild)!!
            val currentTrack = musicSession.player.playingTrack
            if (currentTrack == null) {
                messageAction("There are no tracks currently playing!").queue()
                return@musicAction
            }
            if (!currentTrack.isSeekable) {
                messageAction("You cannot seek this track!").queue()
                return@musicAction
            }
            if (args.isBlank()) {
                messageAction("Please specify a time to go to!").queue()
                return@musicAction
            }
            val time = Utils.parseTimeString(args)
            if (time == null) {
                messageAction("Unknown time format!\n" +
                        "Examples: `1:25:22`, `1h 25m 22s`").queue()
                return@musicAction
            }
            if (time < 0) {
                messageAction("Please give a time that's greater than 0!").queue()
                return@musicAction
            }
            if (time > currentTrack.info.length) {
                messageAction("I cannot seek that far into the song!").queue()
                return@musicAction
            }
            musicSession.player.seekTo(time)
            messageAction("I have seeked to the time **${Utils.formatSongDuration(time)}**").queue()
        }
    }
    command("forward", "fwd") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getMusicSession(event.guild)!!
            val currentTrack = musicSession.player.playingTrack
            if (currentTrack == null) {
                messageAction("There are no tracks currently playing!").queue()
                return@musicAction
            }
            if (!currentTrack.isSeekable) {
                messageAction("You cannot forward this track!").queue()
                return@musicAction
            }
            if (args.isBlank()) {
                messageAction("Please specify a amount to forward by!").queue()
                return@musicAction
            }
            val time = Utils.parseTimeString(args)
            if (time == null) {
                messageAction("Unknown time format!\n" +
                        "Examples: `1:25:22`, `1h 25m 22s`").queue()
                return@musicAction
            }
            if (time < 0) {
                messageAction("Please give a time that's greater than 0!").queue()
                return@musicAction
            }
            val effectiveTime = time + musicSession.player.trackPosition
            if (effectiveTime > currentTrack.info.length) {
                messageAction("You cannot forward into the song by that much!").queue()
                return@musicAction
            }
            musicSession.player.seekTo(effectiveTime)
            messageAction("I have forwarded the song to the time **${Utils.formatSongDuration(effectiveTime)}**").queue()
        }
    }
    command("rewind", "rwd") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getMusicSession(event.guild)!!
            val currentTrack = musicSession.player.playingTrack
            if (currentTrack == null) {
                messageAction("There are no tracks currently playing!").queue()
                return@musicAction
            }
            if (!currentTrack.isSeekable) {
                messageAction("You cannot rewind this track!").queue()
                return@musicAction
            }
            if (args.isBlank()) {
                messageAction("Please specify a amount to rewind by!").queue()
                return@musicAction
            }
            val time = Utils.parseTimeString(args)
            if (time == null) {
                messageAction("Unknown time format!\n" +
                        "Examples: `1:25:22`, `1h 25m 22s`").queue()
                return@musicAction
            }
            if (time < 0) {
                messageAction("Please give a time that's greater than 0!").queue()
                return@musicAction
            }
            val effectiveTime = musicSession.player.trackPosition - time
            if (effectiveTime < 0) {
                messageAction("You cannot rewind back in the song by that much!").queue()
                return@musicAction
            }
            musicSession.player.seekTo(effectiveTime)
            messageAction("I have rewound the song to the time **${Utils.formatSongDuration(effectiveTime)}**").queue()
        }
    }
    command("repeat") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getMusicSession(event.guild)!!
            val currentTrack = musicSession.player.playingTrack
            if (currentTrack == null) {
                messageAction("There are no tracks currently playing!").queue()
                return@musicAction
            }
            val repeatType = when (args.toLowerCase()) {
                "current", "single", "" -> MusicSession.RepeatMode.SINGLE
                "queue", "all" -> MusicSession.RepeatMode.QUEUE
                else -> {
                    messageAction("Unknown repeat mode! Valid Modes: **current/single**, **queue/all**").queue()
                    return@musicAction
                }
            }
            if (musicSession.repeatMode == repeatType) {
                when (repeatType) {
                    MusicSession.RepeatMode.QUEUE -> messageAction("The queue is no longer repeating!").queue()
                    MusicSession.RepeatMode.SINGLE -> messageAction("The song is no longer repeating!").queue()
                    else -> TODO("This shouldn't be called")
                }
                musicSession.repeatMode = MusicSession.RepeatMode.NOTHING
            } else {
                when (repeatType) {
                    MusicSession.RepeatMode.QUEUE -> messageAction("The queue is now repeating!").queue()
                    MusicSession.RepeatMode.SINGLE -> messageAction("The song is now repeating!").queue()
                    else -> TODO("This shouldn't be called")
                }
                musicSession.repeatMode = repeatType
            }
        }
    }
    command("pause") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getMusicSession(event.guild)!!
            musicSession.player.isPaused = true
            messageAction("Music has paused!").queue()
        }
    }
    command("resume") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getMusicSession(event.guild)!!
            musicSession.player.isPaused = false
            messageAction("Music has resumed playing!").queue()
        }
    }
    command("stop") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getMusicSession(event.guild)!!
            musicSession.stop()
            messageAction("Music has stopped!").queue()
        }
    }
}

fun volumeIcon(volume: Int) = when {
    volume == 0 -> Emotes.MUTE
    volume < 30 -> Emotes.SPEAKER
    volume < 70 -> Emotes.SPEAKER_1
    else -> Emotes.SPEAKER_2
}

fun CommandBuilder.musicAction(
        memberInVoice: Boolean = true,
        sameVoiceChannel: Boolean = true,
        activeSession: Boolean = false,
        musicAction: suspend CommandExecution.() -> Unit
) {
    stageActions<Any?> {
        action {
            val musicManager = application.musicManager
            val guild = event.guild
            val author = event.member!!
            if (activeSession && !musicManager.hasMusicSession(guild)) {
                messageAction("There is no active music session!").queue()
                return@action false
            }
            if (memberInVoice && !author.voiceState.inVoiceChannel()) {
                messageAction("You must join a voice channel to use music commands!").queue()
                return@action false
            }
            if (memberInVoice && sameVoiceChannel && musicManager.hasMusicSession(guild) && guild.selfMember.voiceState.inVoiceChannel()) {
                if (author.voiceState.channel !== guild.selfMember.voiceState.channel) {
                    messageAction("You must be in the same voice channel as Astolfo to use music commands!").queue()
                    return@action false
                }
            }
            true
        }
        basicAction {
            musicAction.invoke(this)
        }
    }
}

fun CommandExecution.joinAction(forceJoinMessage: Boolean = false): Boolean {
    val author = event.member!!
    val guild = event.guild!!
    val vc = author.voiceState.channel
    if (guild.afkChannel?.let { it == vc } == true) {
        messageAction("I cannot join a afk channel.").queue()
        return false
    }
    if (!PermissionUtil.checkPermission(vc, guild.selfMember, Permission.VOICE_MOVE_OTHERS) && vc !== guild.selfMember.voiceState.audioChannel && vc.userLimit > 0 && vc.members.size >= vc.userLimit) {
        messageAction("I cannot join a full channel.").queue()
        return false
    }
    if (!PermissionUtil.checkPermission(vc, guild.selfMember, Permission.VOICE_CONNECT)) {
        messageAction("I don't have permission to connect to **${vc.name}**").queue()
        return false
    }
    if (!PermissionUtil.checkPermission(vc, guild.selfMember, Permission.VOICE_SPEAK)) {
        messageAction("I don't have permission to speak in **${vc.name}**").queue()
        return false
    }
    val changedChannels = application.musicManager.lavaLink.getLink(vc.guild).channel != vc
    application.musicManager.lavaLink.connect(vc)
    application.musicManager.getMusicSession(guild, event.textChannel)
    if (changedChannels || forceJoinMessage) messageAction("I have joined your voice channel!").queue()
    return true
}