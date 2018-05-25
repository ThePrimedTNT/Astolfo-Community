package xyz.astolfo.astolfocommunity.modules.music

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
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import lavalink.client.io.Lavalink
import lavalink.client.player.event.AudioEventAdapterWrapped
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.Message
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.entities.VoiceChannel
import net.dv8tion.jda.core.events.guild.GuildLeaveEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import net.dv8tion.jda.core.requests.RequestFuture
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

    val musicManagerListener = object : ListenerAdapter() {
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
    val repeatSongQueue = LinkedBlockingDeque<AudioTrack>()

    var lastSeenMember = System.currentTimeMillis()

    private var nowPlayingMessage: RequestFuture<Message>? = null

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

    private fun pollNextTrack() {
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

        nowPlayingMessage = if (nowPlayingMessage == null) {
            boundChannel.sendMessage(newMessage).submit()
        } else {
            val oldMessage = nowPlayingMessage!!.get()
            if (boundChannel.hasLatestMessage() && boundChannel.latestMessageIdLong == oldMessage.idLong) {
                oldMessage.editMessage(newMessage).submit()
            } else {
                boundChannel.sendMessage(newMessage).submit()
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
        nowPlayingMessage?.thenAccept { it.delete().queue() }
    }

}

fun Lavalink.connect(voiceChannel: VoiceChannel) = getLink(voiceChannel.guild).connect(voiceChannel)
fun Lavalink.getPlayer(guild: Guild) = getLink(guild).player!!

fun AudioPlayerManager.loadItemSync(searchQuery: String, timeout: Long = 1L, timeUnit: TimeUnit = TimeUnit.MINUTES): Pair<AudioItem?, FriendlyException?> {
    val future = CompletableFuture<Pair<AudioItem?, FriendlyException?>>()
    loadItem(searchQuery, object : AudioLoadResultHandler {
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
    return future.get(timeout, timeUnit)!!
}

object MusicUtils {

    private val allowedHosts = listOf("youtube.com", "youtu.be", "music.youtube.com", "soundcloud.com", "bandcamp.com", "beam.pro", "mixer.com", "vimeo.com")

    fun getEffectiveSearchQuery(query: String): String? {
        return try {
            val url = URL(query)
            val host = url.host.let { if (it.startsWith("www")) it.substring(4) else it }
            if (!allowedHosts.any { it.equals(host, true) }) {
                return null
            }
            query
        } catch (e: MalformedURLException) {
            "ytsearch: $query"
        }
    }

}