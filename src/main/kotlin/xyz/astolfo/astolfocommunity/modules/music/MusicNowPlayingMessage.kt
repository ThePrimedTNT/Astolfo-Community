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

    private val parent = Job()
    private val messageActor = actor<AudioTrack>(context = nowPlayingContext, parent = parent, capacity = Channel.UNLIMITED) {
        for (track in channel) {
            updateInternal(track)
        }
    }

    private var internalTrack: AudioTrack? = null
    private var nowPlayingMessage: CachedMessage? = null

    private suspend fun updateInternal(track: AudioTrack) {
        val newMessage = message {
            embed {
                author("\uD83C\uDFB6 Now Playing: ${track.info.title}", track.info.uri)
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
        messageActor.close()
        parent.cancel()
    }

}