package xyz.astolfo.astolfocommunity.modules.music

import com.github.salomonbrys.kotson.fromJson
import com.google.gson.JsonElement
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sun.deploy.net.URLEncoder
import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.delay
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.utils.PermissionUtil
import okhttp3.Request
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.Jsoup
import org.jsoup.safety.Whitelist
import xyz.astolfo.astolfocommunity.*
import xyz.astolfo.astolfocommunity.commands.CommandBuilder
import xyz.astolfo.astolfocommunity.commands.CommandExecution
import xyz.astolfo.astolfocommunity.commands.argsIterator
import xyz.astolfo.astolfocommunity.commands.next
import xyz.astolfo.astolfocommunity.menus.*
import xyz.astolfo.astolfocommunity.messages.*
import xyz.astolfo.astolfocommunity.modules.module
import xyz.astolfo.astolfocommunity.support.SupportLevel
import java.awt.Color
import java.util.concurrent.TimeUnit


fun createMusicModule() = module("Music") {
    createGuildPlaylistCommands()
    command("join", "j") {
        musicAction { joinAction(true) }
    }
    command("play", "p", "search", "yt", "q", "queue") {
        musicAction {
            playAction(false, false)
        }
    }
    command("playtop", "pt", "ptop") {
        musicAction {
            playAction(true, false)
        }
    }
    command("playskip", "ps", "pskip") {
        musicAction {
            playAction(true, true)
        }
    }
    command("radio") {
        musicAction {
            // Make the radio command work like the join command as well
            val musicSession = joinAction() ?: return@musicAction
            val results = tempMessage(embed("Searching radio stations for $args...")) {
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

            val audioItem = try {
                tempMessage(message { embed("\uD83D\uDD0E Loading radio **${selectedRadio.name}**...") }) {
                    application.musicManager.audioPlayerManager.loadItemDeferred(selectedRadio.url).await()
                }
            } catch (e: Throwable) {
                when (e) {
                    is FriendlyException -> messageAction(errorEmbed("Radio failed to load due to an error: **${e.message}**")).queue()
                    is MusicNoMatchException -> messageAction(errorEmbed("Audio Player found no matches found for the radio **${selectedRadio.name}**")).queue()
                    else -> throw e
                }
                return@musicAction
            }
            if (audioItem is AudioTrack) {
                musicSession.await().queueItem(audioItem, event.channel, event.member, selectedRadio.name, false, false) {
                    messageAction(it).queue()
                }
                //messageAction(embed { description("**${selectedRadio.name}** has been added to the queue") }).queue()
            }
        }
    }
    command("leave", "l", "disconnect") {
        musicAction {
            application.musicManager.stopSession(event.guild)
            messageAction(embed("\uD83D\uDEAA I have disconnected")).queue()
        }
    }
    command("playing", "nowplaying", "np") {
        musicAction(memberInVoice = false, activeSession = true) {
            val musicSession = application.musicManager.getSession(event.guild)!!
            val paginator = paginator("Astolfo-Community Music Queue") {
                provider(8, {
                    val songs = musicSession.songQueue()
                    val repeatedSongs = musicSession.repeatSongQueue()
                    if (songs.isEmpty() && repeatedSongs.isEmpty()) listOf("No songs in queue")
                    else listOf(songs, repeatedSongs).flatten().map { audioTrack ->
                        "${if (repeatedSongs.contains(audioTrack)) "\uD83D\uDD04 " else ""}[${audioTrack.info.title}](${audioTrack.info.uri}) **${Utils.formatSongDuration(audioTrack.info.length, audioTrack.info.isStream)}**"
                    }
                })
                renderer {
                    message {
                        embed {
                            titleProvider.invoke()?.let { title(it) }
                            val currentTrack = musicSession.playingTrack()
                            field("\uD83C\uDFB6 Now Playing" + if (currentTrack != null) " - ${Utils.formatSongDuration(musicSession.trackPosition)}/${Utils.formatSongDuration(currentTrack.info.length, currentTrack.info.isStream)}" else "", false) {
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
            val musicSession = application.musicManager.getSession(event.guild)!!
            val amountToSkip = args.takeIf { it.isNotBlank() }?.let {
                val amountNum = it.toBigIntegerOrNull()?.toInt()
                if (amountNum == null) {
                    messageAction(errorEmbed("Amount to skip must be a whole number!")).queue()
                    return@musicAction
                }
                if (amountNum < 1) {
                    messageAction(errorEmbed("Amount to skip must be a greater then zero!")).queue()
                    return@musicAction
                }
                amountNum
            } ?: 1
            val songsSkipped = musicSession.skip(amountToSkip).await()
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
        val supportLevel = SupportLevel.SUPPORTER
        inheritedAction {
            val donationEntry = application.donationManager.getByMember(event.member)
            if (donationEntry.ordinal >= supportLevel.ordinal)
                return@inheritedAction true // Allow Feature
            messageAction(embed {
                description("\uD83D\uDD12 Due to performance reasons volume changing is locked!" +
                        " You can unlock this feature by becoming a [patreon.com/theprimedtnt](https://www.patreon.com/theprimedtnt)" +
                        " and getting at least the **${supportLevel.rewardName}** Tier.")
                color(Color.RED)
            }).queue()
            return@inheritedAction false // Deny Feature
        }
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getSession(event.guild)!!
            val newVolume = args.takeIf { it.isNotBlank() }?.let {
                val amountNum = it.toBigIntegerOrNull()?.toInt()
                if (amountNum == null) {
                    messageAction(errorEmbed("The new volume must be a whole number!")).queue()
                    return@musicAction
                }
                if (amountNum < 5) {
                    messageAction(errorEmbed("The new volume must be at least 5%!")).queue()
                    return@musicAction
                }
                if (amountNum > 150) {
                    messageAction(errorEmbed("The new volume must be no more than 150%!")).queue()
                    return@musicAction
                }
                amountNum
            }
            if (newVolume == null) {
                val currentVolume = musicSession.volume
                messageAction(embed { description("Current volume is **$currentVolume%**!") }).queue()
            } else {
                val oldVolume = musicSession.volume
                musicSession.volume = newVolume
                messageAction(embed { description("${volumeIcon(newVolume)} Volume has changed from **$oldVolume%** to **$newVolume%**") }).queue()
            }
        }
    }
    command("seek") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getSession(event.guild)!!
            val currentTrack = musicSession.playingTrack()
            if (currentTrack == null) {
                messageAction(errorEmbed("There are no tracks currently playing!")).queue()
                return@musicAction
            }
            if (!currentTrack.isSeekable) {
                messageAction(errorEmbed("You cannot seek this track!")).queue()
                return@musicAction
            }
            if (args.isBlank()) {
                messageAction(errorEmbed("Please specify a time to go to!")).queue()
                return@musicAction
            }
            val time = Utils.parseTimeString(args)
            if (time == null) {
                messageAction(errorEmbed("Unknown time format!\n" +
                        "Examples: `1:25:22`, `1h 25m 22s`")).queue()
                return@musicAction
            }
            if (time < 0) {
                messageAction(errorEmbed("Please give a time that's greater than 0!")).queue()
                return@musicAction
            }
            if (time > currentTrack.info.length) {
                messageAction(errorEmbed("I cannot seek that far into the song!")).queue()
                return@musicAction
            }
            musicSession.trackPosition = time
            messageAction(embed("I have sought to the time **${Utils.formatSongDuration(time)}**")).queue()
        }
    }
    command("replay", "reset") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getSession(event.guild)!!
            val currentTrack = musicSession.playingTrack()
            if (currentTrack == null) {
                messageAction(errorEmbed("There are no tracks currently playing!")).queue()
                return@musicAction
            }
            if (!currentTrack.isSeekable) {
                messageAction(errorEmbed("You cannot replay this track!")).queue()
                return@musicAction
            }
            musicSession.trackPosition = 0
            messageAction(embed("I have replayed the song ${currentTrack.info.title}**")).queue()
        }
    }
    command("forward", "fwd") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getSession(event.guild)!!
            val currentTrack = musicSession.playingTrack()
            if (currentTrack == null) {
                messageAction(errorEmbed("There are no tracks currently playing!")).queue()
                return@musicAction
            }
            if (!currentTrack.isSeekable) {
                messageAction(errorEmbed("You cannot forward this track!")).queue()
                return@musicAction
            }
            if (args.isBlank()) {
                messageAction(errorEmbed("Please specify a amount to forward by!")).queue()
                return@musicAction
            }
            val time = Utils.parseTimeString(args)
            if (time == null) {
                messageAction(errorEmbed("Unknown time format!\n" +
                        "Examples: `1:25:22`, `1h 25m 22s`")).queue()
                return@musicAction
            }
            if (time < 0) {
                messageAction(errorEmbed("Please give a time that's greater than 0!")).queue()
                return@musicAction
            }
            val effectiveTime = time + musicSession.trackPosition
            if (effectiveTime > currentTrack.info.length) {
                messageAction(errorEmbed("You cannot forward into the song by that much!")).queue()
                return@musicAction
            }
            musicSession.trackPosition = effectiveTime
            messageAction(embed("I have forwarded the song to the time **${Utils.formatSongDuration(effectiveTime)}**")).queue()
        }
    }
    command("rewind", "rwd") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getSession(event.guild)!!
            val currentTrack = musicSession.playingTrack()
            if (currentTrack == null) {
                messageAction(errorEmbed("There are no tracks currently playing!")).queue()
                return@musicAction
            }
            if (!currentTrack.isSeekable) {
                messageAction(errorEmbed("You cannot rewind this track!")).queue()
                return@musicAction
            }
            if (args.isBlank()) {
                messageAction(errorEmbed("Please specify a amount to rewind by!")).queue()
                return@musicAction
            }
            val time = Utils.parseTimeString(args)
            if (time == null) {
                messageAction(errorEmbed("Unknown time format!\n" +
                        "Examples: `1:25:22`, `1h 25m 22s`")).queue()
                return@musicAction
            }
            if (time < 0) {
                messageAction(errorEmbed("Please give a time that's greater than 0!")).queue()
                return@musicAction
            }
            val effectiveTime = musicSession.trackPosition - time
            if (effectiveTime < 0) {
                messageAction(errorEmbed("You cannot rewind back in the song by that much!")).queue()
                return@musicAction
            }
            musicSession.trackPosition = effectiveTime
            messageAction(embed("I have rewound the song to the time **${Utils.formatSongDuration(effectiveTime)}**")).queue()
        }
    }
    command("repeat") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getSession(event.guild)!!
            val currentTrack = musicSession.playingTrack()
            if (currentTrack == null) {
                messageAction(errorEmbed("There are no tracks currently playing!")).queue()
                return@musicAction
            }
            val repeatType = when (args.toLowerCase()) {
                "current", "single", "" -> MusicSession.RepeatMode.SINGLE
                "queue", "all" -> MusicSession.RepeatMode.QUEUE
                else -> {
                    messageAction(errorEmbed("Unknown repeat mode! Valid Modes: **current/single**, **queue/all**")).queue()
                    return@musicAction
                }
            }
            if (musicSession.repeatMode == repeatType) {
                when (repeatType) {
                    MusicSession.RepeatMode.QUEUE -> messageAction(embed("The queue is no longer repeating!")).queue()
                    MusicSession.RepeatMode.SINGLE -> messageAction(embed("The song is no longer repeating!")).queue()
                    else -> TODO("This shouldn't be called")
                }
                musicSession.repeatMode = MusicSession.RepeatMode.NOTHING
            } else {
                when (repeatType) {
                    MusicSession.RepeatMode.QUEUE -> messageAction(embed("The queue is now repeating!")).queue()
                    MusicSession.RepeatMode.SINGLE -> messageAction(embed("The song is now repeating!")).queue()
                    else -> TODO("This shouldn't be called")
                }
                musicSession.repeatMode = repeatType
            }
        }
    }
    command("shuffle") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getSession(event.guild)!!
            val currentTrack = musicSession.playingTrack()
            if (currentTrack == null) {
                messageAction(errorEmbed("There are no tracks currently playing!")).queue()
                return@musicAction
            }
            musicSession.shuffle()
            messageAction(embed("Music Queue has been shuffled!")).queue()
        }
    }
    command("pause") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getSession(event.guild)!!
            musicSession.isPaused = true
            messageAction(embed("Music has paused!")).queue()
        }
    }
    command("resume", "unpause") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getSession(event.guild)!!
            musicSession.isPaused = false
            messageAction(embed("Music has resumed playing!")).queue()
        }
    }
    command("stop", "clear") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getSession(event.guild)!!
            musicSession.stop()
            messageAction(embed("Music has stopped!")).queue()
        }
    }
    command("leavecleanup", "lc") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getSession(event.guild)!!
            val songsCleanedUp = musicSession.leaveCleanUp().await()
            messageAction(embed {
                when {
                    songsCleanedUp.isEmpty() -> description("No songs where cleaned up.")
                    songsCleanedUp.size == 1 -> {
                        val cleanedUpSong = songsCleanedUp.first()
                        val requesterName = application.shardManager.getEffectiveName(event.guild, cleanedUpSong.requesterId)
                        description("\uD83D\uDDD1 [${cleanedUpSong.info.title}](${cleanedUpSong.info.uri})${if (requesterName != null) " requested by $requesterName" else ""} was cleaned up.")
                    }
                    else -> description("\uD83D\uDDD1 ${songsCleanedUp.size} songs where cleaned up") // TODO show user names that got cleaned up
                }
            }).queue()
        }
    }
    command("removedupes", "drm", "dr") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getSession(event.guild)!!
            val songsCleanedUp = musicSession.duplicateCleanUp().await()
            messageAction(embed {
                when {
                    songsCleanedUp.isEmpty() -> description("No songs where cleaned up.")
                    songsCleanedUp.size == 1 -> description("\uD83D\uDDD1 One duplicate song cleaned up")
                    else -> description("\uD83D\uDDD1 ${songsCleanedUp.size} duplicate songs where cleaned up")
                }
            }).queue()
        }
    }
    command("lyrics", "l", "ly") {
        val slotsRateLimiter = RateLimiter<Long>(1, 10)
        action {
            if (slotsRateLimiter.isLimited(event.author.idLong)) {
                messageAction(errorEmbed("Please cool down! (**${Utils.formatDuration(slotsRateLimiter.remainingTime(event.author.idLong)!!)}** seconds left)")).queue()
                return@action
            }
            slotsRateLimiter.add(event.author.idLong)
            val title = if (args.isBlank()) {
                val musicSession = application.musicManager.getSession(event.guild)
                val playingTrack = musicSession?.playingTrack()
                if (playingTrack == null) {
                    messageAction(errorEmbed("Play a song using Astolfo or provide a name for the lyric lookup.")).queue()
                    return@action
                }
                playingTrack.info.title!!
            } else {
                args
            }
            val response = tempMessage(message { embed("Searching lyrics for **$title**...") }) {
                val requestBuilder = Request.Builder().url("https://api.genius.com/search?q=${URLEncoder.encode(title, Charsets.UTF_8.name())}")
                requestBuilder.header("Authorization", "Bearer ${application.properties.genius_token}")
                val call = ASTOLFO_HTTP_CLIENT.newCall(requestBuilder.build())
                ASTOLFO_GSON.fromJson<LyricsSearch>(call.enqueueDeferred().await())
            }
            val songs = response.response.hits.map { it.result }
            val song = when {
                songs.isEmpty() -> {
                    messageAction(errorEmbed("Couldn't find lyrics for **$title**")).queue()
                    return@action
                }
                songs.size == 1 -> songs.first()
                else -> selectionBuilder<LyricsSearch.Song>()
                        .title("\uD83D\uDD0E Lyric Search Results:")
                        .results(songs)
                        .resultsRenderer { "**${it.title_with_featured}** *by ${it.primary_artist.name}*" }
                        .description("Type the number of the song you want")
                        .execute() ?: return@action
            }
            val pageDoc = Jsoup.connect(song.url)
                    .userAgent("Mozilla/5.0 (Linux; U; Android 6.0.1; ko-kr; Build/IML74K) AppleWebkit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30")
                    .get()
            val lyricsElement = pageDoc.select(".lyrics")
            if (lyricsElement.isEmpty()) {
                messageAction(errorEmbed("Error fetching lyrics for song **${song.full_title}**")).queue()
                return@action
            }
            val lyrics = StringEscapeUtils.unescapeHtml4(Jsoup.clean(lyricsElement.html(), Whitelist.none().addTags("br"))).trim()
            val list = lyrics.replace("<br> ", "")
                    .split("\n").filter { it.isNotEmpty() }.joinToString(separator = "\n")
                    .chunked(2047)
            for ((index, page) in list.withIndex()) {
                val first = index == 0
                messageAction(embed {
                    if (first) title("${song.full_title} Lyrics")
                    else title("${song.full_title} Lyrics Continued")
                    description(page)
                }).queue()
            }
        }
    }
    command("move") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getSession(event.guild)!!

            if (musicSession.songQueue().isEmpty()) {
                messageAction(errorEmbed("The queue is empty")).queue()
                return@musicAction
            }

            val argsIterator = args.argsIterator()

            suspend fun getIndex(title: String, arg: String): Int? {
                return if (arg.isEmpty()) {
                    chatInput(title)
                            .responseValidator {
                                if (it.toBigIntegerOrNull() == null) {
                                    messageAction(errorEmbed("Index must be a whole number!")).queue()
                                    false
                                } else true
                            }
                            .execute()?.toBigIntegerOrNull() ?: return null
                } else {
                    val index = arg.toBigIntegerOrNull()
                    if (index == null) {
                        messageAction(errorEmbed("Index must be a whole number!")).queue()
                        return null
                    }
                    index
                }.toInt()
            }

            val moveFrom = getIndex("Provide the index of the song you want to move:", argsIterator.next(""))
                    ?: return@musicAction
            val moveTo = getIndex("Provide the index of where you want to move the song:", argsIterator.next(""))
                    ?: return@musicAction

            if (moveFrom < 1 || moveTo < 1) {
                messageAction(errorEmbed("A index cannot be lower then 1")).queue()
                return@musicAction
            }

            val response = musicSession.move(moveFrom - 1, moveTo - 1).await()

            if (response.movedTrack == null) {
                messageAction(errorEmbed("No song found at index $moveFrom")).queue()
                return@musicAction
            }

            messageAction(embed("Moved **${response.movedTrack.info.title}** to position ${response.newPosition + 1}")).queue()
        }
    }
    command("remove") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getSession(event.guild)!!

            if (musicSession.songQueue().isEmpty()) {
                messageAction(errorEmbed("The queue is empty")).queue()
                return@musicAction
            }

            val argsIterator = args.argsIterator()

            suspend fun getIndex(title: String, arg: String): Int? {
                return if (arg.isEmpty()) {
                    chatInput(title)
                            .responseValidator {
                                if (it.toBigIntegerOrNull() == null) {
                                    messageAction(errorEmbed("Index must be a whole number!")).queue()
                                    false
                                } else true
                            }
                            .execute()?.toBigIntegerOrNull() ?: return null
                } else {
                    val index = arg.toBigIntegerOrNull()
                    if (index == null) {
                        messageAction(errorEmbed("Index must be a whole number!")).queue()
                        return null
                    }
                    index
                }.toInt()
            }

            val removeIndex = getIndex("Provide the index of the song you want to remove:", argsIterator.next(""))
                    ?: return@musicAction

            if (removeIndex < 1) {
                messageAction(errorEmbed("A index cannot be lower then 1")).queue()
                return@musicAction
            }

            val response = musicSession.remove(removeIndex - 1).await()

            if (response == null) {
                messageAction(errorEmbed("No song found at index $removeIndex")).queue()
                return@musicAction
            }

            messageAction(embed("Removed **${response.info.title}** from song queue")).queue()
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
                messageAction(errorEmbed("There is no active music session!")).queue()
                return@action false
            }
            if (memberInVoice && !author.voiceState.inVoiceChannel()) {
                messageAction(errorEmbed("You must join a voice channel to use music commands!")).queue()
                return@action false
            }
            if (memberInVoice && sameVoiceChannel && musicManager.hasMusicSession(guild) && guild.selfMember.voiceState.inVoiceChannel()) {
                if (author.voiceState.channel !== guild.selfMember.voiceState.channel) {
                    messageAction(errorEmbed("You must be in the same voice channel as Astolfo to use music commands!")).queue()
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

suspend fun CommandExecution.joinAction(forceJoinMessage: Boolean = false): CompletableDeferred<MusicSession>? {
    val author = event.member!!
    val guild = event.guild!!
    val vc = author.voiceState.channel
    if (guild.afkChannel?.let { it == vc } == true) {
        messageAction(errorEmbed("I cannot join a afk channel.")).queue()
        return null
    }
    if (!PermissionUtil.checkPermission(vc, guild.selfMember, Permission.VOICE_MOVE_OTHERS) && vc !== guild.selfMember.voiceState.audioChannel && vc.userLimit > 0 && vc.members.size >= vc.userLimit) {
        messageAction(errorEmbed("I cannot join a full channel.")).queue()
        return null
    }
    if (!PermissionUtil.checkPermission(vc, guild.selfMember, Permission.VOICE_CONNECT)) {
        messageAction(errorEmbed("I don't have permission to connect to **${vc.name}**")).queue()
        return null
    }
    if (!PermissionUtil.checkPermission(vc, guild.selfMember, Permission.VOICE_SPEAK)) {
        messageAction(errorEmbed("I don't have permission to speak in **${vc.name}**")).queue()
        return null
    }
    val changedChannels = application.musicManager.lavaLink.getLink(vc.guild).channel != vc
    application.musicManager.lavaLink.connect(vc)
    val session = application.musicManager.getSession(guild, event.channel)
    if (changedChannels || forceJoinMessage) messageAction(embed("I have joined your voice channel!")).queue()
    return session
}

suspend fun CommandExecution.playAction(top: Boolean, skip: Boolean) {
    // Make the play command work like the join command as well
    val musicSession = joinAction() ?: return
    val searchQuery = MusicUtils.getEffectiveSearchQuery(args)
    if (searchQuery == null) {
        messageAction(errorEmbed("Either im not allowed to play music from that website or I do not support it!")).queue()
        return
    }
    if (!searchQuery.search) {
        musicSession.await().musicLoader.load(event.member, searchQuery.query, event.channel, top, skip)
        return
    }
    val audioPlaylist = try {
        tempMessage(embed("\uD83D\uDD0E Searching for **$args**...")) {
            delay(10, TimeUnit.SECONDS)
            application.musicManager.audioPlayerManager.loadItemDeferred(searchQuery.query).await()
        }
    } catch (e: Throwable) {
        when (e) {
            is FriendlyException -> messageAction(errorEmbed("Failed due to an error: **${e.message}**")).queue()
            is MusicNoMatchException -> messageAction(errorEmbed("No matches found for **${searchQuery.query}**")).queue()
            else -> throw e
        }
        return
    } as AudioPlaylist
    if (audioPlaylist.isSearchResult) {
        // If the track returned is a list of tracks and are from a ytsearch: or scsearch:
        val selectedTrack = selectMusic(audioPlaylist.tracks).execute() ?: return

        musicSession.await().queueItem(selectedTrack, event.channel, event.member, searchQuery.query, top, skip) {
            messageAction(it).queue()
        }
    }
}

@Suppress("unused")
// AS OF 7/21/2018
class LyricsSearch(val meta: Meta,
                   val response: Response) {
    class Meta(val status: Int)
    class Response(val hits: List<Hit>)
    class Hit(val highlights: List<JsonElement>,
              val index: String,
              val type: String,
              val result: Song)

    class Song(val annotation_count: Int,
               val api_path: String,
               val full_title: String,
               val header_image_thumbnail_url: String,
               val header_image_url: String,
               val id: Long,
               val lyrics_owner_id: Int,
               val lyrics_state: String,
               val path: String,
               val pyongs_count: Int,
               val song_art_image_thumbnail_url: String,
               val stats: SongStats,
               val title: String,
               val title_with_featured: String,
               val url: String,
               val primary_artist: Artist)

    class SongStats(val hot: Boolean,
                    val unreviewed_annotations: Int,
                    val concurrents: Int,
                    val pageviews: Long)

    class Artist(val api_path: String,
                 val header_image_url: String,
                 val id: Long,
                 val image_url: String,
                 val is_meme_verified: Boolean,
                 val is_verified: Boolean,
                 val name: String,
                 val url: String)
}