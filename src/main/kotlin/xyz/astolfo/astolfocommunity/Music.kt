package xyz.astolfo.astolfocommunity

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import lavalink.client.io.Lavalink
import lavalink.client.player.event.AudioEventAdapterWrapped
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.TextChannel
import net.dv8tion.jda.core.entities.VoiceChannel
import net.dv8tion.jda.core.utils.PermissionUtil
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque

class MusicManager(astolfoCommunityApplication: AstolfoCommunityApplication, properties: AstolfoProperties) {
    val lavaLink = Lavalink(
            properties.bot_user_id,
            properties.shard_count,
            { shardId -> astolfoCommunityApplication.shardManager.getShardById(shardId) }
    )

    val audioPlayerManager = DefaultAudioPlayerManager()

    private val musicSessionMap = ConcurrentHashMap<Guild, MusicSession>()

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
                // Song Queue Finished
                return
            }

            val track = songQueue.poll() ?: return

            player.playTrack(track)
        }
    }

    override fun onTrackEnd(player: AudioPlayer?, track: AudioTrack?, endReason: AudioTrackEndReason?) {
        pollNextTrack()
    }

    fun destroy() {
        player.removeListener(this)
        player.link.resetPlayer()
    }

}

fun createMusicModule() = module("Music") {
    command("join") {
        musicAction { joinAction(true) }
    }
    command("play") {
        musicAction {
            // Make the play command work like the join command as well
            if (!joinAction()) return@musicAction
            val guild = event.guild
            message("Searching for **$args**...").queue()
            application.musicManager.audioPlayerManager.loadItem(args, object : AudioLoadResultHandler {
                override fun trackLoaded(track: AudioTrack?) {
                    application.musicManager.getMusicSession(guild)?.queue(track!!)
                }

                override fun loadFailed(exception: FriendlyException?) {
                    message("Failed due to an error: **${exception!!.message}**").queue()
                }

                override fun noMatches() {
                    message("No matches found for **$args**").queue()
                }

                override fun playlistLoaded(playlist: AudioPlaylist?) {
                    playlist!!.tracks.forEach {
                        application.musicManager.getMusicSession(guild)?.queue(it)
                    }
                }
            })
        }
    }
    command("leave") {
        musicAction {
            application.musicManager.stopMusicSession(event.guild)
            message("I have disconnected").queue()
        }
    }
    command("nowplaying") {
        musicAction(memberInVoice = false, activeSession = true) {
            val musicSession = application.musicManager.getMusicSession(event.guild)!!
            message(embed {
                title("Astolfo-Community Music Queue")
                field("Now Playing", false) {
                    val currentTrack = musicSession.player.playingTrack
                    if (currentTrack == null) {
                        "No song currently playing"
                    } else {
                        "[${currentTrack.info.title}](${currentTrack.info.uri})"
                    }
                }
                field("\uD83C\uDFBC Queue", false) {
                    val songs = musicSession.songQueue
                    val songsPerPage = 8
                    if (songs.isEmpty()) {
                        "No songs in queue"
                    } else {
                        val songList = songs.take(songsPerPage).map { "[${it.info.title}](${it.info.uri})" }.fold("", { a, b -> "$a\n$b" })
                        if (songs.size > songsPerPage) {
                            "$songList\n**and ${songs.size - songsPerPage} more...**"
                        } else {
                            songList
                        }
                    }
                }
            }).queue()
        }
    }
    command("skip") {
        musicAction(activeSession = true) {
            val musicSession = application.musicManager.getMusicSession(event.guild)!!
            val amountToSkip = args.takeIf { it.isNotBlank() }?.let {
                val amountNum = it.toBigIntegerOrNull()?.toInt()
                if (amountNum == null) {
                    message("Amount to skip must be a whole number!").queue()
                    return@musicAction
                }
                if (amountNum < 1) {
                    message("Amount to skip must be a greater then zero!").queue()
                    return@musicAction
                }
                amountNum
            } ?: 1
            val songsSkipped = musicSession.skip(amountToSkip)
            when {
                songsSkipped.isEmpty() -> message("No songs where skipped.")
                songsSkipped.size == 1 -> {
                    val skippedSong = songsSkipped.first()
                    message("⏩ [${skippedSong.info.title}](${skippedSong.info.uri}) was skipped.")
                }
                else -> message("⏩ ${songsSkipped.size} songs where skipped")
            }.queue()
        }
    }
}

fun Lavalink.connect(voiceChannel: VoiceChannel) = getLink(voiceChannel.guild).connect(voiceChannel)
fun Lavalink.getPlayer(guild: Guild) = getLink(guild).player!!

fun CommandBuilder.musicAction(memberInVoice: Boolean = true, sameVoiceChannel: Boolean = true, activeSession: Boolean = false, musicAction: CommandExecution.() -> Unit) {
    action {
        val author = event.member!!
        if (activeSession && !application.musicManager.hasMusicSession(event.guild)) {
            message("There is no active music session!").queue()
            return@action
        }
        if (memberInVoice && !author.voiceState.inVoiceChannel()) {
            message("You must join a voice channel to use music commands!").queue()
            return@action
        }
        if (memberInVoice && sameVoiceChannel && application.musicManager.hasMusicSession(event.guild) && event.guild.selfMember.voiceState.inVoiceChannel()) {
            if (author.voiceState.channel !== event.guild.selfMember.voiceState.channel) {
                message("You must be in the same voice channel as Astolfo to use music commands!").queue()
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
        message("I cannot join a afk channel.").queue()
        return false
    }
    if (!PermissionUtil.checkPermission(vc, guild.selfMember, Permission.VOICE_MOVE_OTHERS) && vc !== guild.selfMember.voiceState.audioChannel && vc.userLimit > 0 && vc.members.size >= vc.userLimit) {
        message("I cannot join a full channel.").queue()
        return false
    }
    if (!PermissionUtil.checkPermission(vc, guild.selfMember, Permission.VOICE_CONNECT)) {
        message("I don't have permission to connect to **${vc.name}**").queue()
        return false
    }
    if (!PermissionUtil.checkPermission(vc, guild.selfMember, Permission.VOICE_SPEAK)) {
        message("I don't have permission to speak in **${vc.name}**").queue()
        return false
    }
    val changedChannels = application.musicManager.lavaLink.getLink(vc.guild).channel != vc
    application.musicManager.lavaLink.connect(vc)
    application.musicManager.getMusicSession(guild, event.textChannel)
    if (changedChannels || forceJoinMessage) message("I have joined your voice channel!").queue()
    return true
}
