package xyz.astolfo.astolfocommunity.modules.music

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.channels.sendBlocking
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import xyz.astolfo.astolfocommunity.messages.*

class MusicNowPlayingMessage(private val musicSession: MusicSession) {

    companion object {
        private val nowPlayingContext = newFixedThreadPoolContext(20, "Now Playing Message")
    }

    private var destroyed = false

    private val messageActor = actor<AudioTrack>(context = nowPlayingContext, capacity = Channel.UNLIMITED) {
        for (track in channel) {
            if(destroyed) continue
            updateInternal(track)
        }
        nowPlayingMessage?.delete()
        nowPlayingMessage = null
    }

    private var internalTrack: AudioTrack? = null
    private var nowPlayingMessage: CachedMessage? = null

    private suspend fun updateInternal(track: AudioTrack) {
        val guildSettings = musicSession.musicManager.application.astolfoRepositories.getEffectiveGuildSettings(musicSession.guild.idLong)

        if(!guildSettings.announceSongs) {
            // Remove if the setting changed while it was playing
            nowPlayingMessage?.delete()
            nowPlayingMessage = null
            return
        }

        val newMessage = message {
            embed {
                author("\uD83C\uDFB6 Now Playing: ${track.info.title}", track.info.uri)
                val requesterId = track.requesterId
                footer("Requested by: ${musicSession.guild.getMemberById(requesterId)?.effectiveName ?: "Missing Member <$requesterId>"}")
            }
        }

        fun sendMessage() {
            if (musicSession.boundChannel.canTalk())
                nowPlayingMessage = musicSession.boundChannel.sendMessage(newMessage).sendCached()
        }

        if (nowPlayingMessage == null) {
            sendMessage()
        } else if (track != internalTrack) {
            val messageId = nowPlayingMessage?.idLong?.await()
            if (messageId != null && musicSession.boundChannel.hasLatestMessage() && musicSession.boundChannel.latestMessageIdLong == messageId) {
                nowPlayingMessage!!.editMessage(newMessage)
            } else {
                nowPlayingMessage!!.delete()
                nowPlayingMessage = null
                sendMessage()
            }
        }
        internalTrack = track
    }

    fun update(track: AudioTrack) {
        messageActor.sendBlocking(track)
    }

    fun dispose() {
        destroyed = true
        messageActor.close()
    }

}