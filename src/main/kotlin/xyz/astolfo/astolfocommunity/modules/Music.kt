package xyz.astolfo.astolfocommunity.modules

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import lavalink.client.io.Lavalink
import lavalink.client.player.event.AudioEventAdapterWrapped
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.entities.VoiceChannel
import net.dv8tion.jda.core.utils.PermissionUtil
import xyz.astolfo.astolfocommunity.*
import java.net.MalformedURLException
import java.net.URI
import java.net.URL
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MusicManager(astolfoCommunityApplication: AstolfoCommunityApplication, properties: AstolfoProperties) {
    val lavaLink = Lavalink(
            properties.bot_user_id,
            properties.shard_count,
            { shardId -> astolfoCommunityApplication.shardManager.getShardById(shardId) }
    )

    val audioPlayerManager = DefaultAudioPlayerManager()

    private val musicSessionMap = ConcurrentHashMap<Guild, MusicSession>()

    val sessionCount: Int
        get() = musicSessionMap.size
    val queuedSongCount: Int
        get() = musicSessionMap.toMap().values.map { it.songQueue.size }.sum()
    val listeningCount: Int
        get() = musicSessionMap.toMap().values.map {
            it.player.link.channel?.members?.filter { !it.user.isBot }?.size ?: 0
        }.sum()

    init {
        properties.lavalink_nodes.split(",").forEach {
            lavaLink.addNode(URI(it), properties.lavalink_password)
        }

        audioPlayerManager.setItemLoaderThreadPoolSize(100)
        val youtube = YoutubeAudioSourceManager(true)
        youtube.setPlaylistPageCount(5)
        audioPlayerManager.registerSourceManager(youtube)
        audioPlayerManager.registerSourceManager(SoundCloudAudioSourceManager())
        audioPlayerManager.registerSourceManager(BandcampAudioSourceManager())
        audioPlayerManager.registerSourceManager(VimeoAudioSourceManager())
        audioPlayerManager.registerSourceManager(TwitchStreamAudioSourceManager())
        audioPlayerManager.registerSourceManager(BeamAudioSourceManager())
        audioPlayerManager.registerSourceManager(HttpAudioSourceManager())

        launch {
            while (isActive) {
                try {
                    println("Starting Music Clean Up...")
                    val currentTime = System.currentTimeMillis()
                    val amountCleanedUp = AtomicInteger()
                    musicSessionMap.toMap().forEach { guild, session ->
                        val currentVoiceChannel = session.player.link.channel
                        if (currentVoiceChannel != null && currentVoiceChannel.members.any { !it.user.isBot }) session.lastSeenMember = currentTime
                        // Auto Leave if no one is in voice channel for more then 5 minutes
                        if (currentTime - session.lastSeenMember > 5 * 60 * 1000) {
                            stopMusicSession(guild)
                            amountCleanedUp.incrementAndGet()
                            session.boundChannel.sendMessage(embed { description("Disconnected due to being all alone...") }).queue()
                        }
                    }
                    println("Cleaned up ${amountCleanedUp.get()} music sessions in ${System.currentTimeMillis() - currentTime}ms")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(2, TimeUnit.MINUTES)
            }
        }
    }

    fun getMusicSession(guild: Guild) = musicSessionMap[guild]
    fun getMusicSession(guild: Guild, boundChannel: TextChannel) = musicSessionMap.computeIfAbsent(guild, { MusicSession(this, guild, boundChannel) })
    fun hasMusicSession(guild: Guild) = musicSessionMap.containsKey(guild)

    fun stopMusicSession(guild: Guild) {
        musicSessionMap.remove(guild)?.destroy()
        lavaLink.getLink(guild).destroy()
    }
}

class MusicSession(musicManager: MusicManager, guild: Guild, var boundChannel: TextChannel) : AudioEventAdapterWrapped() {
    val player = musicManager.lavaLink.getPlayer(guild)

    private val queueLock = Any()
    val songQueue = LinkedBlockingDeque<AudioTrack>()

    var lastSeenMember = System.currentTimeMillis()

    private var nowPlayingMessage: Deferred<Message>? = null

    init {
        player.addListener(this)
    }

    fun queue(track: AudioTrack, top: Boolean = false) {
        if (top) songQueue.offerFirst(track)
        else songQueue.offer(track)
        pollNextTrack()
    }

    fun skip(amountToSkip: Int): List<AudioTrack> {
        val skippedSongs = (0 until (amountToSkip - 1)).mapNotNull { songQueue.poll() }.toMutableList()
        val skippedPlayingSong = player.playingTrack
        if (skippedPlayingSong != null) {
            skippedSongs.add(0, skippedPlayingSong)
            player.stopTrack()
        }
        return skippedSongs
    }

    private fun pollNextTrack() {
        synchronized(queueLock) {
            if (player.playingTrack != null) return

            if (songQueue.isEmpty()) {
                boundChannel.sendMessage(embed {
                    description("\uD83C\uDFC1 Song Queue Finished!")
                }).queue()
                return
            }

            val track = songQueue.poll() ?: return

            player.playTrack(track)
        }
    }

    override fun onTrackStart(player: AudioPlayer?, track: AudioTrack?) {
        val newMessage = message {
            embed {
                author("\uD83C\uDFB6 Now Playing: ${track!!.info.title}", track.info.uri)
            }
        }

        val lastMessage = nowPlayingMessage
        nowPlayingMessage = async {
            val message = lastMessage?.await()
            if (message != null && boundChannel.latestMessageIdLong == message.idLong) {
                message.editMessage(newMessage).complete()
            } else {
                message?.delete()?.queue()
                boundChannel.sendMessage(newMessage).complete()
            }
        }
    }

    override fun onTrackEnd(player: AudioPlayer?, track: AudioTrack?, endReason: AudioTrackEndReason?) {
        pollNextTrack()
    }

    fun destroy() {
        player.removeListener(this)
        player.link.resetPlayer()
        nowPlayingMessage?.let { launch { it.await().delete().queue() } }
    }

}

fun createMusicModule() = module("Music") {
    command("join", "j") {
        musicAction { joinAction(true) }
    }
    command("play", "p", "search", "yt", "q", "queue") {
        val allowedHosts = listOf("youtube.com", "youtu.be", "music.youtube.com", "soundcloud.com", "bandcamp.com", "beam.pro", "mixer.com", "vimeo.com")

        musicAction {
            // Make the play command work like the join command as well
            if (!joinAction()) return@musicAction
            val guild = event.guild
            val searchQuery = try {
                val url = URL(args)
                val host = url.host.let { if (it.startsWith("www")) it.substring(4) else it }
                if (!allowedHosts.any { it.equals(host, true) }) {
                    messageAction("Either im not allowed to play music from that website or I do not support it!").queue()
                    return@musicAction
                }
                args
            } catch (e: MalformedURLException) {
                "ytsearch: $args"
            }
            val trackResponse = tempMessage(message { embed("\uD83D\uDD0E Searching for **$args**...") }) {
                val future = CompletableFuture<Pair<AudioItem?, FriendlyException?>>()
                application.musicManager.audioPlayerManager.loadItem(searchQuery, object : AudioLoadResultHandler {
                    override fun trackLoaded(track: AudioTrack?) {
                        future.complete(track to null)
                    }

                    override fun loadFailed(exception: FriendlyException?) {
                        future.complete(null to exception)
                    }

                    override fun noMatches() {
                        future.complete(null to null)
                    }

                    override fun playlistLoaded(playlist: AudioPlaylist?) {
                        future.complete(playlist to null)
                    }
                })
                future.get(1, TimeUnit.MINUTES)!!
            }
            val audioItem = trackResponse.first
            val exception = trackResponse.second
            if (audioItem != null && audioItem is AudioTrack?) {
                // If the track returned is a normal audio track
                val audioTrack: AudioTrack = audioItem
                application.musicManager.getMusicSession(guild)?.let {
                    it.boundChannel = event.message.textChannel
                    it.queue(audioTrack)
                }
                messageAction(embed { description("[${audioTrack.info.title}](${audioTrack.info.uri}) has been added to the queue") }).queue()
            } else if (audioItem != null && audioItem is AudioPlaylist?) {
                // If the track returned is a list of tracks
                val audioPlaylist: AudioPlaylist = audioItem
                if (audioPlaylist.isSearchResult) {
                    // If the tracks returned are from a ytsearch: or scsearch:
                    val menu = paginator("\uD83D\uDD0E Music Search Results:") {
                        provider(8, audioPlaylist.tracks.map { audioTrack -> "**${audioTrack.info.title}** *by ${audioTrack.info.author}*" })
                        renderer {
                            message {
                                embed {
                                    titleProvider.invoke()?.let { title(it) }
                                    description("Type the number of the song you want.\n$providedString")
                                    footer("Page ${currentPage + 1}/${provider.pageCount}")
                                }
                            }
                        }
                    }
                    // Waits for a follow up response for song selection
                    responseListener {
                        if (menu.isDestroyed) {
                            removeListener()
                            true
                        } else if (it.args.matches("\\d+".toRegex())) {
                            val numSelection = it.args.toBigInteger().toInt()
                            if (numSelection < 1 || numSelection > audioPlaylist.tracks.size) {
                                messageAction("Unknown Selection").queue()
                                return@responseListener false
                            }
                            val selectedTrack = audioPlaylist.tracks[numSelection - 1]
                            application.musicManager.getMusicSession(guild)?.let {
                                it.boundChannel = event.message.textChannel
                                it.queue(selectedTrack)
                            }
                            messageAction(embed { description("[${selectedTrack.info.title}](${selectedTrack.info.uri}) has been added to the queue") }).queue()
                            removeListener()
                            menu.destroy()
                            false // Don't run the command since song was added
                        } else {
                            messageAction(embed { description("Please type the # of the song you want") }).queue()
                            false // Still waiting for valid response
                        }
                    }
                } else {
                    // If the tracks are from directly from a url
                    application.musicManager.getMusicSession(guild)?.let { session ->
                        session.boundChannel = event.message.textChannel
                        audioPlaylist.tracks.forEach { session.queue(it) }
                    }
                    messageAction(embed { setDescription("The playlist [${audioPlaylist.name}]($args) has been added to the queue") }).queue()
                }
            } else if (exception != null) {
                messageAction("Failed due to an error: **${exception.message}**").queue()
            } else {
                messageAction("No matches found for **$args**").queue()
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

            if (results.isEmpty()) {
                messageAction("No results!").queue()
                return@musicAction
            }

            val menu = paginator("\uD83D\uDD0E Music Search Results:") {
                provider(8, results.map { radio -> "`${radio.id}` **${radio.name}**" })
                renderer {
                    message {
                        embed {
                            titleProvider.invoke()?.let { title(it) }
                            val string = providedContent.joinToString(separator = "\n")
                            description("Type the number of the song you want.\n$string")
                            footer("Page ${currentPage + 1}/${provider.pageCount}")
                        }
                    }
                }
            }
            // Waits for a follow up response for radio selection
            responseListener {
                if (menu.isDestroyed) {
                    removeListener()
                    true
                } else if (it.args.matches("\\d+".toRegex())) {
                    val numSelection = it.args.toBigInteger().toLong()
                    val selectedRadio = results.find { it.id == numSelection }
                    if (selectedRadio == null) {
                        messageAction("Unknown Selection").queue()
                        return@responseListener false
                    }
                    val (audioItem, audioException) = tempMessage(message { embed("\uD83D\uDD0E Loading radio **${selectedRadio.name}**...") }) {
                        val future = CompletableFuture<Pair<AudioItem?, FriendlyException?>>()
                        application.musicManager.audioPlayerManager.loadItem(selectedRadio.url, object : AudioLoadResultHandler {
                            override fun trackLoaded(track: AudioTrack?) {
                                future.complete(track to null)
                            }

                            override fun loadFailed(exception: FriendlyException?) {
                                future.complete(null to exception)
                            }

                            override fun noMatches() {
                                future.complete(null to null)
                            }

                            override fun playlistLoaded(playlist: AudioPlaylist?) {
                                future.complete(playlist to null)
                            }
                        })
                        future.get(1, TimeUnit.MINUTES)!!
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
                    removeListener()
                    menu.destroy()
                    false // Don't run the command since song was added
                } else {
                    messageAction(embed { description("Please type the # of the radio station you want") }).queue()
                    false // Still waiting for valid response
                }
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
                    if (songs.isEmpty()) listOf("No songs in queue")
                    else songs.map { audioTrack ->
                        "[${audioTrack.info.title}](${audioTrack.info.uri}) **${Utils.formatSongDuration(audioTrack.info.length, audioTrack.info.isStream)}**"
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
}

fun volumeIcon(volume: Int) = when {
    volume == 0 -> Emotes.MUTE
    volume < 30 -> Emotes.SPEAKER
    volume < 70 -> Emotes.SPEAKER_1
    else -> Emotes.SPEAKER_2
}

fun Lavalink.connect(voiceChannel: VoiceChannel) = getLink(voiceChannel.guild).connect(voiceChannel)
fun Lavalink.getPlayer(guild: Guild) = getLink(guild).player!!

fun CommandBuilder.musicAction(memberInVoice: Boolean = true, sameVoiceChannel: Boolean = true, activeSession: Boolean = false, musicAction: CommandExecution.() -> Unit) {
    action {
        val author = event.member!!
        if (activeSession && !application.musicManager.hasMusicSession(event.guild)) {
            messageAction("There is no active music session!").queue()
            return@action
        }
        if (memberInVoice && !author.voiceState.inVoiceChannel()) {
            messageAction("You must join a voice channel to use music commands!").queue()
            return@action
        }
        if (memberInVoice && sameVoiceChannel && application.musicManager.hasMusicSession(event.guild) && event.guild.selfMember.voiceState.inVoiceChannel()) {
            if (author.voiceState.channel !== event.guild.selfMember.voiceState.channel) {
                messageAction("You must be in the same voice channel as Astolfo to use music commands!").queue()
                return@action
            }
        }
        musicAction.invoke(this)
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
