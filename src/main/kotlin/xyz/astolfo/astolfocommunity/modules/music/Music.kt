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
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.channels.sendBlocking
import lavalink.client.LavalinkUtil
import lavalink.client.io.Lavalink
import lavalink.client.player.event.AudioEventAdapterWrapped
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.entities.VoiceChannel
import net.dv8tion.jda.core.events.ReadyEvent
import net.dv8tion.jda.core.events.guild.GuildLeaveEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import org.apache.commons.io.FileUtils
import xyz.astolfo.astolfocommunity.ASTOLFO_GSON
import xyz.astolfo.astolfocommunity.AstolfoCommunityApplication
import xyz.astolfo.astolfocommunity.AstolfoProperties
import xyz.astolfo.astolfocommunity.commands.CommandExecution
import xyz.astolfo.astolfocommunity.menus.selectionBuilder
import xyz.astolfo.astolfocommunity.messages.*
import xyz.astolfo.astolfocommunity.synchronized2
import java.awt.Color
import java.io.File
import java.net.MalformedURLException
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MusicManager(val application: AstolfoCommunityApplication, properties: AstolfoProperties) {

    val lavaLink = Lavalink(
            properties.bot_user_id,
            properties.shard_count
    ) { shardId -> application.shardManager.getShardById(shardId) }

    private val saveFolder = File("./tempMusic/sessions/")

    class MusicSessionSave(var voiceChannel: Long, var boundChannel: Long, val currentSong: String?, val currentSongPosition: Long, val songQueue: List<String>, val repeatSongQueue: List<String>, val repeatMode: MusicSession.RepeatMode)

    val musicManagerListener = object : ListenerAdapter() {
        override fun onReady(event: ReadyEvent?) {
            val shardFile = File(saveFolder, "${event!!.jda.shardInfo.shardId}.json")
            println("Loading music session file from ${shardFile.absolutePath}")
            if (!shardFile.exists()) return
            val data = ASTOLFO_GSON.fromJson<MutableMap<Long, MusicSessionSave>>(shardFile.readText())
            for (guild in event.jda.guilds) {
                try {
                    val musicSessionSave = data[guild.idLong] ?: continue
                    if (guild.getVoiceChannelById(musicSessionSave.voiceChannel) == null) continue
                    val boundChannel = guild.getTextChannelById(musicSessionSave.boundChannel) ?: continue

                    val musicSession = getMusicSession(guild, boundChannel)
                    musicSession.loadSave(musicSessionSave)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            FileUtils.deleteQuietly(shardFile)
        }

        override fun onGuildLeave(event: GuildLeaveEvent?) {
            stopMusicSession(event!!.guild)
        }
    }

    val audioPlayerManager = DefaultAudioPlayerManager()

    private val musicSessionMap = ConcurrentHashMap<Guild, MusicSession>()

    val sessionCount: Int
        get() = musicSessionMap.size
    val queuedSongCount: Int
        get() = musicSessionMap.toMap().values.map { it.songQueue().size }.sum()
    val listeningCount: Int
        get() = musicSessionMap.toMap().values.map {
            it.channel()?.members?.filter { !it.user.isBot }?.size ?: 0
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
                        val currentVoiceChannel = session.channel()
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

        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                println("Saving music sessions to ${saveFolder.absolutePath}")
                val musicMap = mutableMapOf<Int, MutableMap<Long, MusicSessionSave>>()
                musicSessionMap.toMap().forEach { guild, musicSession ->
                    musicMap.computeIfAbsent(guild.jda.shardInfo.shardId) { mutableMapOf() }[guild.idLong] = MusicSessionSave(musicSession.channel()?.idLong
                            ?: 0,
                            musicSession.boundChannel.idLong,
                            musicSession.playingTrack()?.let { LavalinkUtil.toMessage(it) },
                            musicSession.trackPosition,
                            musicSession.songQueue().map { LavalinkUtil.toMessage(it) },
                            musicSession.repeatSongQueue().map { LavalinkUtil.toMessage(it) },
                            musicSession.repeatMode)
                    stopMusicSession(guild)
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
        })
    }

    fun getMusicSession(guild: Guild) = musicSessionMap[guild]
    fun getMusicSession(guild: Guild, boundChannel: TextChannel) = musicSessionMap.computeIfAbsent(guild) { MusicSession(this, guild, boundChannel) }
    fun hasMusicSession(guild: Guild) = musicSessionMap.containsKey(guild)

    fun stopMusicSession(guild: Guild) {
        musicSessionMap.remove(guild)?.destroy()
        lavaLink.getLink(guild).destroy()
    }
}

class MusicSession(private val musicManager: MusicManager, guild: Guild, var boundChannel: TextChannel) : AudioEventAdapterWrapped() {

    companion object {
        private val musicContext = newFixedThreadPoolContext(100, "Music Session")
    }

    private var destroyed = false

    private val player = musicManager.lavaLink.getPlayer(guild)
    private val songQueue = LinkedBlockingDeque<AudioTrack>()
    private val repeatSongQueue = LinkedBlockingDeque<AudioTrack>()
    private val nowPlayingMessage = MusicNowPlayingMessage(this)
    val musicLoader = MusicLoader()

    private var internalRepeatMode = RepeatMode.NOTHING
    private var songToRepeat: AudioTrack? = null

    private interface MusicEvent
    private object NextSong : MusicEvent
    private object Shuffle : MusicEvent
    private class QueueSongs(val member: Member, val audioTracks: List<AudioTrack>, val top: Boolean, val completableDeferred: CompletableDeferred<Int>) : MusicEvent
    private class ChangeRepeatMode(val newMode: RepeatMode) : MusicEvent
    private class TrackEnd(val audioTrack: AudioTrack, val endReason: AudioTrackEndReason) : MusicEvent
    private class SkipTracks(val amount: Int, val completableDeferred: CompletableDeferred<List<AudioTrack>>) : MusicEvent
    private class LoadSave(val save: MusicManager.MusicSessionSave) : MusicEvent
    private class Connect(val voiceChannel: VoiceChannel?) : MusicEvent

    private val musicActor = actor<MusicEvent>(capacity = Channel.UNLIMITED, context = musicContext) {
        for (event in channel) {
            if(destroyed) continue
            handleEvent(event)
        }
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
                var songCount = 0
                val supportLevel = musicManager.application.donationManager.getByMember(event.member)
                for (audioTrack in event.audioTracks) {
                    if (songQueue.size < supportLevel.queueSize) {
                        if (event.top) songQueue.offerFirst(audioTrack)
                        else songQueue.offer(audioTrack)
                        songCount++
                    }
                }
                event.completableDeferred.complete(songCount)
                handleEvent(NextSong)
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
                synchronized(repeatSongQueue) { repeatSongQueue.addAll(save.repeatSongQueue.map { LavalinkUtil.toAudioTrack(it) }) }
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
                        player.link.connect(voiceChannel)
                    }
                } else player.link.disconnect()
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

    fun queue(member: Member, tracks: List<AudioTrack>, top: Boolean = false, completableDeferred: CompletableDeferred<Int>) = musicActor.sendBlocking(QueueSongs(member, tracks, top, completableDeferred))

    fun skip(amountToSkip: Int): CompletableDeferred<List<AudioTrack>> {
        val completableDeferred = CompletableDeferred<List<AudioTrack>>()
        musicActor.sendBlocking(SkipTracks(amountToSkip, completableDeferred))
        return completableDeferred
    }

    fun stop() {
        repeatMode = RepeatMode.NOTHING
        songQueue.clear()
        player.stopTrack()
    }

    internal fun loadSave(save: MusicManager.MusicSessionSave) = musicActor.sendBlocking(LoadSave(save))

    override fun onTrackStart(player: AudioPlayer?, track: AudioTrack) {
        nowPlayingMessage.update(track)
    }

    override fun onTrackEnd(player: AudioPlayer?, track: AudioTrack, endReason: AudioTrackEndReason) {
        musicActor.sendBlocking(TrackEnd(track, endReason))
    }

    fun destroy() {
        destroyed = true
        player.removeListener(this)
        player.link.resetPlayer()
        nowPlayingMessage.dispose()
        musicLoader.destroy()
        musicActor.close()
    }

    private interface MusicLoaderEvent
    private class QueueTaskEvent(val member: Member, val query: String, val textChannel: TextChannel) : MusicLoaderEvent
    private class TaskFinishEvent(val musicLoaderTask: MusicLoader.MusicLoaderTask, val cancelled: Boolean) : MusicLoaderEvent

    inner class MusicLoader {

        private var destroyed = false
        private val tasks = mutableListOf<MusicLoaderTask>()

        private val musicLoaderActor = actor<MusicLoaderEvent>(capacity = Channel.UNLIMITED, context = musicContext) {
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
                    tasks.add(MusicLoaderTask(event.member, event.textChannel, event.query))
                }
                is TaskFinishEvent -> {
                    event.musicLoaderTask.run(event.cancelled)
                    tasks.remove(event.musicLoaderTask)
                }
            }
        }

        suspend fun load(member: Member, query: String, textChannel: TextChannel) = musicLoaderActor.send(QueueTaskEvent(member, query, textChannel))

        fun destroy() {
            destroyed = true
            musicLoaderActor.close()
        }

        inner class MusicLoaderTask(val member: Member, val textChannel: TextChannel, val query: String) {
            private val message = textChannel.sendMessage(embed("\uD83D\uDD0E Loading **$query** to queue...")).sendCached()
            private val completableDeferred = musicManager.audioPlayerManager.loadItemSync(query)

            init {
                completableDeferred.invokeOnCompletion(onCancelling = false) {
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
                if (audioItem is AudioTrack) {
                    // If the track returned is a normal audio track
                    val audioTrack: AudioTrack = audioItem
                    boundChannel = textChannel
                    val completableDeferred = CompletableDeferred<Int>()
                    queue(member, listOf(audioTrack), false, completableDeferred)
                    withTimeout(1, TimeUnit.MINUTES) {
                        if (completableDeferred.await() == 0) {
                            message.editMessage(errorEmbed("❗ [${audioTrack.info.title}](${audioTrack.info.uri}) couldn't be added to the queue since the queue is full! " +
                                        "To increase the size of the queue consider donating to our [patreon.com/theprimedtnt](https://www.patreon.com/theprimedtnt)"))
                        } else {
                            message.editMessage(embed("\uD83D\uDCDD [${audioTrack.info.title}](${audioTrack.info.uri}) has been added to the queue"))
                        }
                    }
                } else if (audioItem is AudioPlaylist) {
                    val audioPlaylist: AudioPlaylist = audioItem
                    // If the tracks are from directly from a url
                    boundChannel = textChannel
                    val completableDeferred = CompletableDeferred<Int>()
                    val tracks = audioPlaylist.tracks
                    queue(member, tracks, false, completableDeferred)
                    withTimeout(1, TimeUnit.MINUTES) {
                        val amountAdded = completableDeferred.await()
                        when {
                            amountAdded == 0 ->
                                message.editMessage(errorEmbed("❗ No songs from the playlist [${audioPlaylist.name}]($query) could be added to the queue since the queue is full! " +
                                            "To increase the size of the queue consider donating to our [patreon.com/theprimedtnt](https://www.patreon.com/theprimedtnt)"))
                            amountAdded < tracks.size ->
                                message.editMessage(errorEmbed("❗ Only **$amountAdded** of **${tracks.size}** songs from the playlist [${audioPlaylist.name}]($query) could be added to the queue since the queue is full! " +
                                            "To increase the size of the queue consider donating to our [patreon.com/theprimedtnt](https://www.patreon.com/theprimedtnt)"))
                            else ->
                                message.editMessage(embed("\uD83D\uDCDD The playlist [${audioPlaylist.name}]($query) has been added to the queue"))
                        }
                    }
                }
            }
        }
    }

}

fun Lavalink.connect(voiceChannel: VoiceChannel) = getLink(voiceChannel.guild).connect(voiceChannel)
fun Lavalink.getPlayer(guild: Guild) = getLink(guild).player!!

fun CommandExecution.selectMusic(results: List<AudioTrack>) = selectionBuilder<AudioTrack>()
        .title("\uD83D\uDD0E Music Search Results:")
        .results(results)
        .noResultsMessage("Unknown Song!")
        .resultsRenderer { "**${it.info.title}** *by ${it.info.author}*" }
        .description("Type the number of the song you want")

class MusicNoMatchException : RuntimeException()

fun AudioPlayerManager.loadItemSync(searchQuery: String): CompletableDeferred<AudioItem> {
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
    future.cancelFutureOnCompletion(loadTask)
    return future
}

object MusicUtils {

    private val allowedHosts = listOf("youtube.com", "youtu.be", "music.youtube.com", "soundcloud.com", "bandcamp.com", "beam.pro", "mixer.com", "vimeo.com")

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