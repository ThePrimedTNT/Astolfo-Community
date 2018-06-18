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
import lavalink.client.LavalinkUtil
import lavalink.client.io.Lavalink
import lavalink.client.player.event.AudioEventAdapterWrapped
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.MessageChannel
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.entities.VoiceChannel
import net.dv8tion.jda.core.events.ReadyEvent
import net.dv8tion.jda.core.events.guild.GuildLeaveEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import org.apache.commons.io.FileUtils
import xyz.astolfo.astolfocommunity.*
import xyz.astolfo.astolfocommunity.commands.CommandExecution
import xyz.astolfo.astolfocommunity.menus.selectionBuilder
import java.io.File
import java.net.MalformedURLException
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MusicManager(astolfoCommunityApplication: AstolfoCommunityApplication, properties: AstolfoProperties) {

    val lavaLink = Lavalink(
            properties.bot_user_id,
            properties.shard_count
    ) { shardId -> astolfoCommunityApplication.shardManager.getShardById(shardId) }

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
                    val voiceChannel = guild.getVoiceChannelById(musicSessionSave.voiceChannel) ?: continue
                    val boundChannel = guild.getTextChannelById(musicSessionSave.boundChannel) ?: continue

                    val musicSession = getMusicSession(guild, boundChannel)
                    musicSession.player.link.connect(voiceChannel)
                    musicSession.songQueue.addAll(musicSessionSave.songQueue.map { LavalinkUtil.toAudioTrack(it) })
                    musicSession.repeatSongQueue.addAll(musicSessionSave.repeatSongQueue.map { LavalinkUtil.toAudioTrack(it) })
                    musicSession.repeatMode = musicSessionSave.repeatMode

                    val currentSong = musicSessionSave.currentSong?.let { LavalinkUtil.toAudioTrack(it) }
                    if (currentSong != null) {
                        if (currentSong.isSeekable) currentSong.position = musicSessionSave.currentSongPosition
                        musicSession.player.playTrack(currentSong)
                    }

                    musicSession.pollNextTrack()
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

        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                println("Saving music sessions to ${saveFolder.absolutePath}")
                val musicMap = mutableMapOf<Int, MutableMap<Long, MusicSessionSave>>()
                musicSessionMap.toMap().forEach { guild, musicSession ->
                    musicMap.computeIfAbsent(guild.jda.shardInfo.shardId, { mutableMapOf() })[guild.idLong] = MusicSessionSave(musicSession.player.link.channel?.idLong
                            ?: 0,
                            musicSession.boundChannel.idLong,
                            musicSession.player.playingTrack?.let { LavalinkUtil.toMessage(it) },
                            musicSession.player.playingTrack?.let { musicSession.player.trackPosition } ?: 0,
                            musicSession.songQueue.map { LavalinkUtil.toMessage(it) },
                            musicSession.repeatSongQueue.map { LavalinkUtil.toMessage(it) },
                            musicSession.repeatMode)
                    stopMusicSession(guild)
                }
                if (saveFolder.exists()) FileUtils.cleanDirectory(saveFolder)
                musicMap.forEach { shardId, sessions ->
                    val shardFile = File(saveFolder, "$shardId.json")
                    FileUtils.writeStringToFile(shardFile, ASTOLFO_GSON.toJson(sessions), Charsets.UTF_8)
                }
                println("Saved!")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        })
    }

    fun getMusicSession(guild: Guild) = musicSessionMap[guild]
    fun getMusicSession(guild: Guild, boundChannel: TextChannel) = musicSessionMap.computeIfAbsent(guild, { MusicSession(this, guild, boundChannel) })
    fun hasMusicSession(guild: Guild) = musicSessionMap.containsKey(guild)

    fun stopMusicSession(guild: Guild) {
        musicSessionMap.remove(guild)?.destroy()
        lavaLink.getLink(guild).destroy()
    }
}

class MusicSession(private val musicManager: MusicManager, guild: Guild, var boundChannel: MessageChannel) : AudioEventAdapterWrapped() {
    val player = musicManager.lavaLink.getPlayer(guild)

    private val queueLock = Any()
    val songQueue = LinkedBlockingDeque<AudioTrack>()
    val repeatSongQueue = LinkedBlockingDeque<AudioTrack>()

    var lastSeenMember = System.currentTimeMillis()

    private var nowPlayingMessage: AsyncMessage? = null
    val musicLoader = MusicLoader()

    var repeatMode = RepeatMode.NOTHING
        set(value) {
            if (value != RepeatMode.QUEUE) repeatSongQueue.clear()
            field = value
        }

    enum class RepeatMode {
        NOTHING,
        SINGLE,
        QUEUE
    }

    init {
        player.addListener(this)
    }

    fun queue(track: AudioTrack, top: Boolean = false) {
        if (top) songQueue.offerFirst(track)
        else songQueue.offer(track)
        pollNextTrack()
    }

    fun skip(amountToSkip: Int): List<AudioTrack> {
        repeatMode = RepeatMode.NOTHING
        val skippedSongs = (0 until (amountToSkip - 1)).mapNotNull { songQueue.poll() }.toMutableList()
        val skippedPlayingSong = player.playingTrack
        if (skippedPlayingSong != null) {
            skippedSongs.add(0, skippedPlayingSong)
            player.stopTrack()
        }
        return skippedSongs
    }

    fun stop() {
        repeatMode = RepeatMode.NOTHING
        songQueue.clear()
        player.stopTrack()
    }

    internal fun pollNextTrack() {
        synchronized(queueLock) {
            if (player.playingTrack != null) return

            if (songQueue.isEmpty() && repeatSongQueue.isEmpty()) {
                boundChannel.sendMessage(embed {
                    description("\uD83C\uDFC1 Song Queue Finished!")
                }).queue()
                return
            }

            val track = songQueue.poll() ?: repeatSongQueue.poll() ?: return

            player.playTrack(track)
        }
    }

    override fun onTrackStart(player: AudioPlayer?, track: AudioTrack?) {
        val newMessage = message {
            embed {
                author("\uD83C\uDFB6 Now Playing: ${track!!.info.title}", track.info.uri)
            }
        }

        if (nowPlayingMessage == null) {
            nowPlayingMessage = boundChannel.sendMessage(newMessage).sendAsync()
        } else {
            val messageId = nowPlayingMessage?.getIdLong()
            if (messageId != null && boundChannel.hasLatestMessage() && boundChannel.latestMessageIdLong == messageId) {
                nowPlayingMessage!!.editMessage(newMessage)
            } else {
                nowPlayingMessage!!.delete()
                nowPlayingMessage = boundChannel.sendMessage(newMessage).sendAsync()
            }
        }
    }

    override fun onTrackEnd(player: AudioPlayer?, track: AudioTrack?, endReason: AudioTrackEndReason?) {
        if (endReason == AudioTrackEndReason.FINISHED) {
            if (repeatMode == RepeatMode.QUEUE) repeatSongQueue.add(track!!)
            if (repeatMode == RepeatMode.SINGLE) {
                synchronized(queueLock) {
                    this.player.playTrack(track)
                }
            }
        }
        pollNextTrack()
    }

    fun destroy() {
        player.removeListener(this)
        player.link.resetPlayer()
        nowPlayingMessage?.delete()
        musicLoader.destroy()
    }

    inner class MusicLoader {

        private val tasks: MutableList<MusicLoaderTask> = CopyOnWriteArrayList()
        private var destroyed = false

        fun add(musicQuery: MusicUtils.MusicQuery, messageChannel: MessageChannel) {
            synchronized(tasks) {
                if (destroyed) return
                tasks.add(MusicLoaderTask(musicQuery, messageChannel))
            }
        }

        fun destroy() {
            synchronized(tasks) {
                destroyed = true
                tasks.forEach { it.destroy() }
            }
        }

        inner class MusicLoaderTask(val query: MusicUtils.MusicQuery, val messageChannel: MessageChannel) {

            private val message = messageChannel.sendMessage(embed("\uD83D\uDD0E Loading **${query.query}** to queue...")).sendAsync()
            private val loaderJob = launch {
                val result = musicManager.audioPlayerManager.loadItemSync(query.query)
                val audioItem = result.first
                val exception = result.second
                if (audioItem != null && audioItem is AudioTrack) {
                    // If the track returned is a normal audio track
                    val audioTrack: AudioTrack = audioItem
                    boundChannel = messageChannel
                    queue(audioTrack)
                    message.editMessage(embed("[${audioTrack.info.title}](${audioTrack.info.uri}) has been added to the queue"))
                } else if (audioItem != null && audioItem is AudioPlaylist) {
                    val audioPlaylist: AudioPlaylist = audioItem
                    // If the tracks are from directly from a url
                    boundChannel = messageChannel
                    audioPlaylist.tracks.forEach { queue(it) }
                    message.editMessage(embed("The playlist [${audioPlaylist.name}](${query.query}) has been added to the queue"))
                } else if (exception != null) {
                    message.editMessage("Failed due to an error: **${exception.message}**")
                } else {
                    message.editMessage("No matches found for **${query.query}**")
                }
            }

            init {
                loaderJob.invokeOnCompletion {
                    // Remove task once its completed
                    tasks.remove(this@MusicLoaderTask)
                }
            }

            fun destroy() = runBlocking {
                loaderJob.cancelAndJoin()
                if (loaderJob.isCancelled) message.editMessage(embed("\uD83D\uDD0E Loading **${query.query}** to queue... **[CANCELLED]**"))
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

suspend fun AudioPlayerManager.loadItemSync(searchQuery: String): Pair<AudioItem?, FriendlyException?> {
    val future = CompletableDeferred<Pair<AudioItem?, FriendlyException?>>()
    val loadTask = loadItem(searchQuery, object : AudioLoadResultHandler {
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
    future.invokeOnCompletion { loadTask.cancel(true) }
    return future.await()
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