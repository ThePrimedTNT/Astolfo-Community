package xyz.astolfo.astolfocommunity.modules.music

import com.github.salomonbrys.kotson.fromJson
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.sendBlocking
import lavalink.client.LavalinkUtil
import lavalink.client.io.jda.JdaLavalink
import lavalink.client.io.jda.JdaLink
import lavalink.client.player.event.AudioEventAdapterWrapped
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.*
import net.dv8tion.jda.core.events.ReadyEvent
import net.dv8tion.jda.core.events.guild.GuildLeaveEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import org.apache.commons.io.FileUtils
import xyz.astolfo.astolfocommunity.ASTOLFO_GSON
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.AstolfoProperties
import xyz.astolfo.astolfocommunity.commands.CommandExecution
import xyz.astolfo.astolfocommunity.menus.selectionBuilder
import xyz.astolfo.astolfocommunity.messages.description
import xyz.astolfo.astolfocommunity.messages.embed
import xyz.astolfo.astolfocommunity.messages.errorEmbed
import xyz.astolfo.astolfocommunity.messages.sendCached
import xyz.astolfo.astolfocommunity.support.SupportLevel
import xyz.astolfo.astolfocommunity.synchronized2
import java.io.File
import java.net.MalformedURLException
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

class MusicManager(val application: AstolfoCommunityApplication, properties: AstolfoProperties) {

    private val musicContext = newFixedThreadPoolContext(50, "Music Manager")

    val lavaLink = JdaLavalink(
        properties.bot_user_id,
        properties.shard_count
    ) { shardId -> application.shardManager.getShardById(shardId) }

    private val saveFolder = File("./tempMusic/sessions/")

    private val musicSessionMap = ConcurrentHashMap<Guild, MusicSession>()

    private interface MusicEvent
    private class CreateMusicEvent(
        val guild: Guild,
        val textChannel: TextChannel,
        val callback: CompletableDeferred<MusicSession>
    ) : MusicEvent

    private class DeleteMusicEvent(val guild: Guild) : MusicEvent
    private object CleanUpMusicEvent : MusicEvent

    private val musicActor = GlobalScope.actor<MusicEvent>(context = musicContext, capacity = Channel.UNLIMITED) {
        for (event in channel) {
            handleEvent(event)
        }
    }

    private suspend fun handleEvent(event: MusicEvent) {
        when (event) {
            is CreateMusicEvent -> {
                val currentSession = musicSessionMap[event.guild]
                if (currentSession != null) {
                    event.callback.complete(currentSession)
                    return // No need to create, just return already created version
                }
                // Create new music session
                val session = MusicSession(this@MusicManager, event.guild, event.textChannel)
                musicSessionMap[event.guild] = session

                // Set up default volume
                val donationEntry = application.donationManager.getByMember(event.guild.owner)
                if (donationEntry >= SupportLevel.SUPPORTER) {
                    // only if owner is a supporter or greater
                    val defaultVolume =
                        application.astolfoRepositories.getEffectiveGuildSettings(event.guild.idLong).defaultMusicVolume
                    session.volume = defaultVolume // ez
                }

                event.callback.complete(session) // send newly created session back
            }
            is DeleteMusicEvent -> {
                // clean up music session and lavalink link
                musicSessionMap.remove(event.guild)?.destroy()
                lavaLink.getLink(event.guild).destroy()
            }
            is CleanUpMusicEvent -> {
                try {
                    println("Starting Music Clean Up...")
                    val currentTime = System.currentTimeMillis()
                    var amountCleanedUp = 0
                    for ((guild, session) in musicSessionMap.toMap()) {
                        val currentVoiceChannel = session.channel()
                        if (currentVoiceChannel != null && guild.getVoiceChannelById(currentVoiceChannel).members.any { !it.user.isBot })
                            session.lastSeenMember = currentTime
                        // Auto Leave if no one is in voice channel for more then 5 minutes
                        if (currentTime - session.lastSeenMember > 5 * 60 * 1000) {
                            handleEvent(DeleteMusicEvent(guild))
                            amountCleanedUp++
                            session.boundChannel.sendMessage(embed("Disconnected due to being all alone...")).queue()
                        }
                    }
                    println("Cleaned up $amountCleanedUp music sessions in ${System.currentTimeMillis() - currentTime}ms")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    class MusicSessionSave(
        var voiceChannel: Long,
        var boundChannel: Long,
        val currentSong: String?,
        val currentSongPosition: Long,
        val songQueue: List<String>,
        val repeatSongQueue: List<String>,
        val repeatMode: MusicSession.RepeatMode
    )

    val musicManagerListener = object : ListenerAdapter() {
        override fun onReady(event: ReadyEvent) {
            GlobalScope.launch(musicContext) {
                val shardFile = File(saveFolder, "${event.jda.shardInfo.shardId}.json")
                println("Loading music session file from ${shardFile.absolutePath}")
                if (!shardFile.exists()) return@launch
                val data = ASTOLFO_GSON.fromJson<MutableMap<Long, MusicSessionSave>>(shardFile.readText())
                for (guild in event.jda.guilds) {
                    try {
                        val musicSessionSave = data[guild.idLong] ?: continue
                        if (guild.getVoiceChannelById(musicSessionSave.voiceChannel) == null) continue
                        val boundChannel = guild.getTextChannelById(musicSessionSave.boundChannel) ?: continue

                        val musicSession = getSession(guild, boundChannel).await()
                        musicSession.loadSave(musicSessionSave)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                FileUtils.deleteQuietly(shardFile)
            }
        }

        override fun onGuildLeave(event: GuildLeaveEvent) {
            GlobalScope.launch(musicContext) { stopSession(event.guild) }
        }
    }

    val audioPlayerManager = DefaultAudioPlayerManager()

    val sessionCount: Int
        get() = musicSessionMap.size
    val queuedSongCount: Int
        get() = musicSessionMap.toMap().values.map { it.songQueue().size }.sum()
    val listeningCount: Int
        get() = musicSessionMap.toMap().values.map {
            val channelId = it.channel()
            if (channelId != null) {
                it.guild.getVoiceChannelById(channelId)
                    ?.members?.filter { !it.user.isBot }?.size ?: 0
            } else 0
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

        // Send a clean up task every 2 minutes
        GlobalScope.launch {
            while (isActive) {
                delay(TimeUnit.MINUTES.toMillis(2))
                musicActor.send(CleanUpMusicEvent)
            }
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            runBlocking(musicContext) {
                try {
                    println("Saving music sessions to ${saveFolder.absolutePath}")
                    val musicMap = mutableMapOf<Int, MutableMap<Long, MusicSessionSave>>()
                    for ((guild, musicSession) in musicSessionMap.toMap()) {
                        musicMap.computeIfAbsent(guild.jda.shardInfo.shardId) { mutableMapOf() }[guild.idLong] =
                            MusicSessionSave(
                                musicSession.channel()?.toLong()
                                    ?: 0,
                                musicSession.boundChannel.idLong,
                                musicSession.playingTrack()?.let { LavalinkUtil.toMessage(it) },
                                musicSession.trackPosition,
                                musicSession.songQueue().map { LavalinkUtil.toMessage(it) },
                                musicSession.repeatSongQueue().map { LavalinkUtil.toMessage(it) },
                                musicSession.repeatMode
                            )
                        stopSession(guild)
                    }
                    if (saveFolder.exists()) FileUtils.cleanDirectory(saveFolder)
                    musicMap.forEach { shardId, sessions ->
                        val shardFile = File(saveFolder, "$shardId.json")
                        FileUtils.writeStringToFile(shardFile, ASTOLFO_GSON.toJson(sessions), Charsets.UTF_8)
                    }
                    println("Saved!")
                    // TODO move this to a proper system
                    application.shardManager.shutdown()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        })
    }

    fun getSession(guild: Guild) = musicSessionMap[guild]
    fun hasMusicSession(guild: Guild) = musicSessionMap.containsKey(guild)

    suspend fun getSession(guild: Guild, boundChannel: TextChannel): CompletableDeferred<MusicSession> {
        val completableDeferred = CompletableDeferred<MusicSession>()
        musicActor.send(CreateMusicEvent(guild, boundChannel, completableDeferred))
        return completableDeferred
    }

    suspend fun stopSession(guild: Guild) = musicActor.send(DeleteMusicEvent(guild))
}

class MusicSession(val musicManager: MusicManager, val guild: Guild, var boundChannel: TextChannel) :
    AudioEventAdapterWrapped() {

    companion object {
        private val musicContext = newFixedThreadPoolContext(100, "Music Session")
    }

    private var destroyed = false

    private val player = musicManager.lavaLink.getLink(guild).player
    private val songQueue = LinkedBlockingDeque<AudioTrack>()
    private val repeatSongQueue = LinkedBlockingDeque<AudioTrack>()
    private val nowPlayingMessage = MusicNowPlayingMessage(this)
    val musicLoader = MusicLoader()

    private var internalRepeatMode = RepeatMode.NOTHING
    private var songToRepeat: AudioTrack? = null

    private interface MusicEvent
    private object NextSong : MusicEvent
    private object Shuffle : MusicEvent
    private class QueueSongs(
        val member: Member,
        val audioTracks: List<AudioTrack>,
        val top: Boolean,
        val skip: Boolean,
        val completableDeferred: CompletableDeferred<QueueSongsResponse>
    ) : MusicEvent

    class QueueSongsResponse(
        val songsQueued: Int,
        val queueMax: Boolean,
        val userLimit: Boolean,
        val queuedDups: Boolean
    )

    private class ChangeRepeatMode(val newMode: RepeatMode) : MusicEvent
    private class TrackEnd(val audioTrack: AudioTrack, val endReason: AudioTrackEndReason) : MusicEvent
    private class SkipTracks(val amount: Int, val completableDeferred: CompletableDeferred<List<AudioTrack>>) :
        MusicEvent

    private class LoadSave(val save: MusicManager.MusicSessionSave) : MusicEvent
    private class Connect(val voiceChannel: VoiceChannel?) : MusicEvent
    private object Stop : MusicEvent
    private object Destroy : MusicEvent
    private class LeaveCleanUp(val completableDeferred: CompletableDeferred<List<AudioTrack>>) : MusicEvent
    private class DuplicateCleanUp(val completableDeferred: CompletableDeferred<List<AudioTrack>>) : MusicEvent
    private class Remove(val removeAt: Int, val completableDeferred: CompletableDeferred<AudioTrack?>) : MusicEvent
    private class Move(
        val fromIndex: Int,
        val toIndex: Int,
        val completableDeferred: CompletableDeferred<MoveResponse>
    ) : MusicEvent

    // Move Helpers
    class MoveResponse(val movedTrack: AudioTrack?, val newPosition: Int)

    private val musicActor = GlobalScope.actor<MusicEvent>(capacity = Channel.UNLIMITED, context = musicContext) {
        for (event in channel) {
            if (destroyed) continue
            handleEvent(event)
        }
        handleEvent(Destroy)
    }

    private fun handleEvent(event: MusicEvent) {
        when (event) {
            is NextSong -> {
                synchronized2(songQueue, repeatSongQueue) {
                    if (player.playingTrack != null) return

                    var track: AudioTrack? = null

                    if (internalRepeatMode == RepeatMode.SINGLE) track = songToRepeat

                    if (track == null) track = songQueue.poll() ?: repeatSongQueue.poll()

                    if (track == null) {
                        if (boundChannel.canTalk())
                            boundChannel.sendMessage(embed {
                                description("\uD83C\uDFC1 Song Queue Finished!")
                            }).queue()
                        return
                    }

                    player.playTrack(track)
                }
            }
            is QueueSongs -> {
                val guildSettings = musicManager.application.astolfoRepositories.getEffectiveGuildSettings(guild.idLong)
                val supportLevel = musicManager.application.donationManager.getByMember(event.member)
                val userLimitAmount = guildSettings.maxUserSongs
                val dupPrevention = guildSettings.dupSongPrevention

                var songCount = 0
                var currentUserSongCount = songQueue.count { it.requesterId == event.member.user.idLong }

                var hitQueueMax = false
                var hitUserLimit = false
                var queuedDups = false

                for (audioTrack in event.audioTracks) {
                    if (userLimitAmount in 1..currentUserSongCount) hitUserLimit = true
                    if (songQueue.size >= supportLevel.queueSize) hitQueueMax = true
                    if (!hitQueueMax && !hitUserLimit) {
                        if (dupPrevention && songQueue.any { it.info.uri == audioTrack.info.uri }) {
                            queuedDups = true
                            continue
                        }
                        audioTrack.requesterId = event.member.user.idLong
                        if (event.top) songQueue.offerFirst(audioTrack)
                        else songQueue.offer(audioTrack)
                        currentUserSongCount++
                        songCount++
                    }
                }
                event.completableDeferred.complete(QueueSongsResponse(songCount, hitQueueMax, hitUserLimit, queuedDups))
                if (event.skip && songQueue.isNotEmpty()) {
                    // Skip the current track if its a playskip command
                    handleEvent(SkipTracks(0, CompletableDeferred()))
                } else {
                    handleEvent(NextSong)
                }
            }
            is Shuffle -> {
                fun shuffle(songQueue: LinkedBlockingDeque<AudioTrack>) {
                    synchronized(songQueue) {
                        val list = mutableListOf<AudioTrack>()
                        songQueue.drainTo(list)
                        list.shuffle()
                        songQueue.addAll(list)
                    }
                }
                shuffle(songQueue)
                shuffle(repeatSongQueue)
            }
            is ChangeRepeatMode -> {
                val newMode = event.newMode
                if (newMode != RepeatMode.QUEUE) repeatSongQueue.clear()
                if (newMode == RepeatMode.SINGLE) songToRepeat = player.playingTrack
                internalRepeatMode = newMode
            }
            is TrackEnd -> {
                if (event.endReason == AudioTrackEndReason.FINISHED) {
                    if (repeatMode == RepeatMode.QUEUE) repeatSongQueue.add(event.audioTrack)
                    if (repeatMode == RepeatMode.SINGLE) songToRepeat = event.audioTrack
                }
                handleEvent(NextSong)
            }
            is SkipTracks -> {
                handleEvent(ChangeRepeatMode(RepeatMode.NOTHING))
                val skippedSongs = (0 until (event.amount - 1)).mapNotNull { songQueue.poll() }.toMutableList()
                val skippedPlayingSong = player.playingTrack
                if (skippedPlayingSong != null) {
                    skippedSongs.add(0, skippedPlayingSong)
                    player.stopTrack()
                }
                event.completableDeferred.complete(skippedSongs)
            }
            is LoadSave -> {
                val save = event.save
                val voiceChannel = boundChannel.jda.getVoiceChannelById(save.voiceChannel) ?: return
                handleEvent(Connect(voiceChannel))
                synchronized(songQueue) { songQueue.addAll(save.songQueue.map { LavalinkUtil.toAudioTrack(it) }) }
                synchronized(repeatSongQueue) {
                    repeatSongQueue.addAll(save.repeatSongQueue.map {
                        LavalinkUtil.toAudioTrack(
                            it
                        )
                    })
                }
                handleEvent(ChangeRepeatMode(save.repeatMode))

                val currentSong = save.currentSong?.let { LavalinkUtil.toAudioTrack(it) }
                if (currentSong != null) {
                    if (currentSong.isSeekable) currentSong.position = save.currentSongPosition
                    player.playTrack(currentSong)
                }

                handleEvent(NextSong)
            }
            is Connect -> {
                val voiceChannel = event.voiceChannel
                if (voiceChannel != null) {
                    // Just some insanity check
                    if (voiceChannel.guild.selfMember.hasPermission(voiceChannel, Permission.VOICE_CONNECT)) {
                        (player.link as JdaLink).connect(voiceChannel)
                    }
                } else player.link.disconnect()
            }
            is Stop -> {
                handleEvent(ChangeRepeatMode(RepeatMode.NOTHING))
                songQueue.clear()
                player.stopTrack()
            }
            is Destroy -> {
                player.removeListener(this)
                player.link.resetPlayer()
                nowPlayingMessage.dispose()
                musicLoader.destroy()
            }
            is LeaveCleanUp -> {
                val channelId = player.link.channel
                val validRequesterIds = if (channelId != null)
                    (player.link as JdaLink).jda.getVoiceChannelById(channelId)?.members?.map { it.user.idLong }
                else null
                if (validRequesterIds == null) {
                    // For whatever reason we cant get the members
                    event.completableDeferred.complete(listOf())
                    return
                }
                val cleanedUpSongs = mutableListOf<AudioTrack>()
                fun leaveCleanUp(songQueue: LinkedBlockingDeque<AudioTrack>) {
                    synchronized(songQueue) {
                        val list = mutableListOf<AudioTrack>()
                        songQueue.drainTo(list)
                        list.removeIf {
                            val shouldRemove = !validRequesterIds.contains(it.requesterId)
                            if (shouldRemove) cleanedUpSongs.add(it)
                            shouldRemove
                        }
                        songQueue.addAll(list)
                    }
                }
                leaveCleanUp(songQueue)
                leaveCleanUp(repeatSongQueue)
                event.completableDeferred.complete(cleanedUpSongs) // send back the songs removed
            }
            is DuplicateCleanUp -> {
                val cleanedUpSongs = mutableListOf<AudioTrack>()
                fun leaveCleanUp(songQueue: LinkedBlockingDeque<AudioTrack>) {
                    synchronized(songQueue) {
                        val list = mutableListOf<AudioTrack>()
                        songQueue.drainTo(list)
                        list.removeIf { track ->
                            val shouldRemove = cleanedUpSongs.any { it.info.uri == track.info.uri }
                            if (shouldRemove) cleanedUpSongs.add(track)
                            shouldRemove
                        }
                        songQueue.addAll(list)
                    }
                }
                leaveCleanUp(repeatSongQueue)
                leaveCleanUp(songQueue)
                event.completableDeferred.complete(cleanedUpSongs) // send back the songs removed
            }
            is Remove -> {
                synchronized(songQueue) {
                    val list = mutableListOf<AudioTrack>()
                    songQueue.drainTo(list)
                    val track = if (event.removeAt in list.indices) {
                        list.removeAt(event.removeAt)
                    } else null
                    event.completableDeferred.complete(track)
                    songQueue.addAll(list)
                }
            }
            is Move -> {
                synchronized(songQueue) {
                    val list = mutableListOf<AudioTrack>()
                    songQueue.drainTo(list)
                    val track = if (event.fromIndex in list.indices) {
                        list.removeAt(event.fromIndex)
                    } else {
                        songQueue.addAll(list)
                        event.completableDeferred.complete(MoveResponse(null, 0))
                        return
                    }
                    val newIndex = if (event.toIndex >= list.size) {
                        list.add(track)
                        list.size - 1
                    } else {
                        list.add(event.toIndex, track)
                        event.toIndex
                    }
                    songQueue.addAll(list)
                    event.completableDeferred.complete(MoveResponse(track, newIndex))
                }
            }
        }
    }

    fun songQueue() = synchronized(songQueue) { songQueue.toList() }
    fun repeatSongQueue() = synchronized(repeatSongQueue) { repeatSongQueue.toList() }

    fun channel() = player.link.channel
    fun playingTrack(): AudioTrack? = player.playingTrack

    var trackPosition
        get() = if (player.playingTrack != null) player.trackPosition else 0L
        set(value) {
            if (player.playingTrack != null)
                player.seekTo(value)
        }

    var repeatMode
        set(value) = musicActor.sendBlocking(ChangeRepeatMode(value))
        get() = internalRepeatMode

    var volume
        set(value) {
            player.volume = value
        }
        get() = player.volume

    var isPaused
        set(value) {
            player.isPaused = value
        }
        get() = player.isPaused

    fun shuffle() = musicActor.sendBlocking(Shuffle)

    var lastSeenMember = System.currentTimeMillis()

    enum class RepeatMode {
        NOTHING,
        SINGLE,
        QUEUE
    }

    init {
        player.addListener(this)
    }

    suspend fun queue(
        member: Member,
        tracks: List<AudioTrack>,
        top: Boolean = false,
        skip: Boolean = false,
        completableDeferred: CompletableDeferred<QueueSongsResponse>
    ) = musicActor.send(QueueSongs(member, tracks, top, skip, completableDeferred))

    suspend fun queueItem(
        audioItem: AudioItem,
        textChannel: TextChannel,
        member: Member,
        query: String,
        top: Boolean,
        skip: Boolean,
        messageCallback: (MessageEmbed) -> Unit
    ) {
        if (audioItem is AudioTrack) {
            // If the track returned is a normal audio track
            val audioTrack: AudioTrack = audioItem
            boundChannel = textChannel
            val completableDeferred = CompletableDeferred<QueueSongsResponse>()
            queue(member, listOf(audioTrack), top, skip, completableDeferred)
            withTimeout(TimeUnit.MINUTES.toMillis(1)) {
                val response = completableDeferred.await()
                when {
                    response.queueMax -> messageCallback(
                        errorEmbed(
                            "❗ [${audioTrack.info.title}](${audioTrack.info.uri}) couldn't be added to the queue since the queue is full! " +
                                "To increase the size of the queue consider donating to our [patreon.com/theprimedtnt](https://www.patreon.com/theprimedtnt)"
                        )
                    )
                    response.userLimit -> messageCallback(errorEmbed("❗ [${audioTrack.info.title}](${audioTrack.info.uri}) couldn't be added to the queue since you hit the user song limit!"))
                    response.queuedDups -> messageCallback(errorEmbed("❗ [${audioTrack.info.title}](${audioTrack.info.uri}) couldn't be added to the queue since it was a duplicate and duplicate song prevention is enabled!"))
                    else -> messageCallback(embed("\uD83D\uDCDD [${audioTrack.info.title}](${audioTrack.info.uri}) has been added to the queue"))
                }
            }
        } else if (audioItem is AudioPlaylist) {
            val audioPlaylist: AudioPlaylist = audioItem
            // If the tracks are from directly from a url
            boundChannel = textChannel
            val completableDeferred = CompletableDeferred<QueueSongsResponse>()
            val tracks = audioPlaylist.tracks
            queue(member, tracks, top, skip, completableDeferred)
            withTimeout(TimeUnit.MINUTES.toMillis(1)) {
                val response = completableDeferred.await()
                val amountAdded = response.songsQueued
                when {
                    response.queueMax -> if (amountAdded == 0) messageCallback(
                        errorEmbed(
                            "❗ No songs from the playlist [${audioPlaylist.name}]($query) could be added to the queue since the queue is full! " +
                                "To increase the size of the queue consider donating to our [patreon.com/theprimedtnt](https://www.patreon.com/theprimedtnt)"
                        )
                    )
                    else messageCallback(
                        errorEmbed(
                            "❗ Only **$amountAdded** of **${tracks.size}** songs from the playlist [${audioPlaylist.name}]($query) could be added to the queue since the queue is full! " +
                                "To increase the size of the queue consider donating to our [patreon.com/theprimedtnt](https://www.patreon.com/theprimedtnt)"
                        )
                    )

                    response.userLimit -> if (amountAdded == 0) messageCallback(errorEmbed("❗ No songs from the playlist [${audioPlaylist.name}]($query) could be added to the queue since you hit the user song limit!"))
                    else messageCallback(errorEmbed("❗ Only **$amountAdded** of **${tracks.size}** songs from the playlist [${audioPlaylist.name}]($query) could be added to the queue since you hit the user song limit!"))

                    response.queuedDups -> if (amountAdded == 0) messageCallback(errorEmbed("❗ No songs from the playlist [${audioPlaylist.name}]($query) could be added to the queue since they where all duplicates and duplicate song prevention is enabled!"))
                    else messageCallback(errorEmbed("❗ Only **$amountAdded** of **${tracks.size}** songs from the playlist [${audioPlaylist.name}]($query) could be added to the queue since some where duplicates and duplicate song prevention is enabled!"))

                    else -> messageCallback(embed("\uD83D\uDCDD The playlist [${audioPlaylist.name}]($query) has been added to the queue"))
                }
            }
        }
    }

    suspend fun move(moveFrom: Int, moveTo: Int): CompletableDeferred<MoveResponse> {
        val completableDeferred = CompletableDeferred<MoveResponse>()
        musicActor.send(Move(moveFrom, moveTo, completableDeferred))
        return completableDeferred
    }

    suspend fun remove(removeAt: Int): CompletableDeferred<AudioTrack?> {
        val completableDeferred = CompletableDeferred<AudioTrack?>()
        musicActor.send(Remove(removeAt, completableDeferred))
        return completableDeferred
    }

    suspend fun skip(amountToSkip: Int): CompletableDeferred<List<AudioTrack>> {
        val completableDeferred = CompletableDeferred<List<AudioTrack>>()
        musicActor.send(SkipTracks(amountToSkip, completableDeferred))
        return completableDeferred
    }

    suspend fun leaveCleanUp(): CompletableDeferred<List<AudioTrack>> {
        val completableDeferred = CompletableDeferred<List<AudioTrack>>()
        musicActor.send(LeaveCleanUp(completableDeferred))
        return completableDeferred
    }

    suspend fun duplicateCleanUp(): CompletableDeferred<List<AudioTrack>> {
        val completableDeferred = CompletableDeferred<List<AudioTrack>>()
        musicActor.send(DuplicateCleanUp(completableDeferred))
        return completableDeferred
    }

    suspend fun stop() = musicActor.send(Stop)

    internal suspend fun loadSave(save: MusicManager.MusicSessionSave) = musicActor.send(LoadSave(save))

    override fun onTrackStart(player: AudioPlayer?, track: AudioTrack) {
        nowPlayingMessage.update(track)
    }

    override fun onTrackEnd(player: AudioPlayer?, track: AudioTrack, endReason: AudioTrackEndReason) {
        musicActor.sendBlocking(TrackEnd(track, endReason))
    }

    fun destroy() {
        destroyed = true
        musicActor.close()
    }

    private interface MusicLoaderEvent
    private class QueueTaskEvent(
        val member: Member,
        val query: String,
        val textChannel: TextChannel,
        val top: Boolean,
        val skip: Boolean
    ) : MusicLoaderEvent

    private class TaskFinishEvent(val musicLoaderTask: MusicLoader.MusicLoaderTask, val cancelled: Boolean) :
        MusicLoaderEvent

    inner class MusicLoader {

        private var destroyed = false
        private val tasks = mutableListOf<MusicLoaderTask>()

        private val musicLoaderActor =
            GlobalScope.actor<MusicLoaderEvent>(capacity = Channel.UNLIMITED, context = musicContext) {
                for (event in channel) {
                    if (destroyed) continue
                    handleEvent(event)
                }
                // Clean up
                for (task in tasks.toList()) {
                    handleEvent(TaskFinishEvent(task, true))
                }
                tasks.clear()
            }

        private suspend fun handleEvent(event: MusicLoaderEvent) {
            when (event) {
                is QueueTaskEvent -> {
                    tasks.add(MusicLoaderTask(event.member, event.textChannel, event.query, event.top, event.skip))
                }
                is TaskFinishEvent -> {
                    event.musicLoaderTask.run(event.cancelled)
                    tasks.remove(event.musicLoaderTask)
                }
            }
        }

        suspend fun load(member: Member, query: String, textChannel: TextChannel, top: Boolean, skip: Boolean) =
            musicLoaderActor.send(QueueTaskEvent(member, query, textChannel, top, skip))

        fun destroy() {
            destroyed = true
            musicLoaderActor.close()
        }

        inner class MusicLoaderTask(
            val member: Member,
            val textChannel: TextChannel,
            val query: String,
            val top: Boolean,
            val skip: Boolean
        ) {
            private val message =
                textChannel.sendMessage(embed("\uD83D\uDD0E Loading **$query** to queue...")).sendCached()
            private val completableDeferred = musicManager.audioPlayerManager.loadItemDeferred(query)

            init {
                completableDeferred.invokeOnCompletion {
                    // Queue that its ready
                    if (destroyed) return@invokeOnCompletion // ignore if its already destroyed
                    musicLoaderActor.sendBlocking(TaskFinishEvent(this@MusicLoaderTask, false))
                }
            }

            suspend fun run(cancelled: Boolean) {
                if (cancelled) {
                    message.editMessage(embed("\uD83D\uDD0E Loading **$query** to queue... **[CANCELLED]**"))
                    completableDeferred.cancel()
                    return
                }
                val audioItem = try {
                    completableDeferred.await()
                } catch (e: Throwable) {
                    when (e) {
                        is FriendlyException -> message.editMessage(errorEmbed("❗ Failed due to an error: **${e.message}**"))
                        is MusicNoMatchException -> message.editMessage(errorEmbed("❗ No matches found for **$query**"))
                        else -> throw e
                    }
                    return
                }
                queueItem(audioItem, textChannel, member, query, top, skip) { message.editMessage(it) }
            }
        }
    }
}

// Audio shortcuts

var AudioTrack.requesterId: Long
    set(value) {
        audioData.requesterId = value
    }
    get() = audioData.requesterId

var AudioTrack.audioData: AudioData
    set(value) {
        userData = value
    }
    get() {
        var data = userData ?: AudioData()
        if (userData !is AudioData) data = AudioData()
        userData = data
        return data as AudioData
    }


class AudioData(var requesterId: Long = 0)

// Lavalink shortcuts

fun JdaLavalink.connect(voiceChannel: VoiceChannel) = getLink(voiceChannel.guild).connect(voiceChannel)
fun JdaLavalink.getPlayer(guild: Guild) = getLink(guild).player!!

fun CommandExecution.selectMusic(results: List<AudioTrack>) = selectionBuilder<AudioTrack>()
    .title("\uD83D\uDD0E Music Search Results:")
    .results(results)
    .noResultsMessage("Unknown Song!")
    .resultsRenderer { "**${it.info.title}** *by ${it.info.author}*" }
    .description("Type the number of the song you want")

class MusicNoMatchException : RuntimeException()

fun AudioPlayerManager.loadItemDeferred(searchQuery: String): CompletableDeferred<AudioItem> {
    val future = CompletableDeferred<AudioItem>()
    val loadTask = loadItem(searchQuery, object : AudioLoadResultHandler {
        override fun trackLoaded(track: AudioTrack) {
            future.complete(track)
        }

        override fun loadFailed(exception: FriendlyException) {
            future.completeExceptionally(exception)
        }

        override fun noMatches() {
            future.completeExceptionally(MusicNoMatchException())
        }

        override fun playlistLoaded(playlist: AudioPlaylist) {
            future.complete(playlist)
        }
    })
    return future
}

object MusicUtils {

    private val allowedHosts = listOf(
        "youtube.com",
        "youtu.be",
        "music.youtube.com",
        "soundcloud.com",
        "bandcamp.com",
        "beam.pro",
        "mixer.com",
        "vimeo.com"
    )

    fun getEffectiveSearchQuery(query: String): MusicQuery? {
        return try {
            val url = URL(query)
            val host = url.host.let { if (it.startsWith("www")) it.substring(4) else it }
            if (!allowedHosts.any { it.equals(host, true) }) {
                return null
            }
            MusicQuery(query, false)
        } catch (e: MalformedURLException) {
            MusicQuery("ytsearch: $query", true)
        }
    }

    class MusicQuery(val query: String, val search: Boolean)

}