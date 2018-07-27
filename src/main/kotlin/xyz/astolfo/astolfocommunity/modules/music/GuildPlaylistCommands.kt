package xyz.astolfo.astolfocommunity.modules.music

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import net.dv8tion.jda.core.Permission
import xyz.astolfo.astolfocommunity.GuildPlaylistEntry
import xyz.astolfo.astolfocommunity.commands.CommandSession
import xyz.astolfo.astolfocommunity.menus.paginator
import xyz.astolfo.astolfocommunity.menus.provider
import xyz.astolfo.astolfocommunity.menus.renderer
import xyz.astolfo.astolfocommunity.messages.*
import xyz.astolfo.astolfocommunity.modules.ModuleBuilder
import xyz.astolfo.astolfocommunity.splitFirst

internal fun ModuleBuilder.createGuildPlaylistCommands() {
    command("guildplaylist", "gpl") {
        command("create", "c") {
            permission(Permission.MANAGE_SERVER)
            action {
                if (args.isBlank()) {
                    messageAction("Enter a name to give the playlist!").queue()
                    return@action
                }
                val effectiveName = args.replace(Regex("\\s+"), "-")
                if (application.astolfoRepositories.guildPlaylistRepository.findByGuildIdAndNameIgnoreCase(event.guild.idLong, effectiveName) != null) {
                    messageAction("Playlist by that name already exists!").queue()
                    return@action
                }
                if (effectiveName.length > 20) {
                    messageAction("Max name length is 20 characters!").queue()
                    return@action
                }
                val playlist = application.astolfoRepositories.guildPlaylistRepository.save(GuildPlaylistEntry(name = effectiveName, guildId = event.guild.idLong))
                messageAction("Playlist **${playlist.name}** (*${playlist.playlistKey!!}*) has been created!").queue()
            }
        }
        command("list") {
            action {
                if (args.isBlank()) {
                    paginator("\uD83C\uDFBC __**Guild Playlists:**__") {
                        provider(10, application.astolfoRepositories.guildPlaylistRepository.findByGuildId(event.guild.idLong).map { "**${it.name}** (${it.songs.size} Songs)" })
                    }
                } else {
                    val playlist = application.astolfoRepositories.guildPlaylistRepository.findByGuildIdAndNameIgnoreCase(event.guild.idLong, args)
                    if (playlist == null) {
                        messageAction("That playlist doesn't exist!").queue()
                        return@action
                    }
                    paginator("\uD83C\uDFBC __**${playlist.name} Playlist:**__") {
                        provider(10, playlist.lavaplayerSongs.map { "[${it.info.title}](${it.info.uri})" })
                    }
                }
            }
        }
        command("delete") {
            permission(Permission.MANAGE_SERVER)
            action {
                if (args.isBlank()) {
                    messageAction("Enter a playlist name!").queue()
                    return@action
                }
                val playlist = application.astolfoRepositories.guildPlaylistRepository.findByGuildIdAndNameIgnoreCase(event.guild.idLong, args)
                if (playlist == null) {
                    messageAction("I couldn't find a playlist with that name!").queue()
                    return@action
                }
                application.astolfoRepositories.guildPlaylistRepository.delete(playlist)
                messageAction("I have deleted the playlist **${playlist.name}** (*${playlist.playlistKey}*)").queue()
            }
        }
        command("info") {
            action {
                if (args.isBlank()) {
                    messageAction("Enter a playlist name!").queue()
                    return@action
                }
                val playlist = application.astolfoRepositories.guildPlaylistRepository.findByGuildIdAndNameIgnoreCase(event.guild.idLong, args)
                        ?: application.astolfoRepositories.guildPlaylistRepository.findByPlaylistKey(args)
                if (playlist == null) {
                    messageAction("I couldn't find a playlist with that name!").queue()
                    return@action
                }
                messageAction(embed {
                    title("Guild Playlist Info")
                    description("**Name:** *${playlist.name}*\n" +
                            "**Key:** ${playlist.playlistKey}")
                    field("Details", "**Guild:** *${application.shardManager.getGuildById(playlist.guildId)?.name
                            ?: "Not Found"}*\n" +
                            "**Song Count:** *${playlist.songs.size}*", false)
                }).queue()
            }
        }
        command("add") {
            permission(Permission.MANAGE_SERVER)
            action {
                val (playlistName, songQuery) = args.splitFirst(" ")
                if (playlistName.isBlank()) {
                    messageAction("Enter a playlist name!").queue()
                    return@action
                }
                if (songQuery.isBlank()) {
                    messageAction("Enter a song/playlist to add!").queue()
                    return@action
                }
                val searchQuery = MusicUtils.getEffectiveSearchQuery(songQuery)
                if (searchQuery == null) {
                    messageAction("Either im not allowed to play music from that website or I do not support it!").queue()
                    return@action
                }
                val playlist = application.astolfoRepositories.guildPlaylistRepository.findByGuildIdAndNameIgnoreCase(event.guild.idLong, playlistName)
                        ?: application.astolfoRepositories.guildPlaylistRepository.findByPlaylistKey(playlistName)
                if (playlist == null) {
                    messageAction("I couldn't find a playlist with that name!").queue()
                    return@action
                }
                val audioItem = try {
                    tempMessage(message { embed("\uD83D\uDD0E Searching for **$searchQuery**...") }) {
                        application.musicManager.audioPlayerManager.loadItemDeferred(searchQuery.query).await()
                    }
                } catch (e: Throwable) {
                    when (e) {
                        is FriendlyException -> messageAction("Failed due to an error: **${e.message}**").queue()
                        is MusicNoMatchException -> messageAction("No matches found for **${searchQuery.query}**").queue()
                        else -> throw e
                    }
                    return@action
                }
                if (audioItem is AudioTrack) {
                    // If the track returned is a normal audio track
                    val audioTrack: AudioTrack = audioItem

                    val songs = playlist.lavaplayerSongs
                    songs.add(audioTrack)
                    playlist.lavaplayerSongs = songs
                    application.astolfoRepositories.guildPlaylistRepository.save(playlist)

                    messageAction(embed { description("[${audioTrack.info.title}](${audioTrack.info.uri}) has been added to the playlist **${playlist.name}**") }).queue()
                } else if (audioItem is AudioPlaylist) {
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
                                CommandSession.ResponseAction.UNREGISTER_LISTENER
                            } else if (args.matches("\\d+".toRegex())) {
                                val numSelection = args.toBigInteger().toInt()
                                if (numSelection < 1 || numSelection > audioPlaylist.tracks.size) {
                                    messageAction("Unknown Selection").queue()
                                    return@responseListener CommandSession.ResponseAction.IGNORE_COMMAND
                                }
                                val selectedTrack = audioPlaylist.tracks[numSelection - 1]

                                val songs = playlist.lavaplayerSongs
                                songs.add(selectedTrack)
                                playlist.lavaplayerSongs = songs
                                application.astolfoRepositories.guildPlaylistRepository.save(playlist)

                                messageAction(embed { description("[${selectedTrack.info.title}](${selectedTrack.info.uri}) has been added to the playlist **${playlist.name}**") }).queue()
                                menu.destroy()
                                CommandSession.ResponseAction.IGNORE_AND_UNREGISTER_LISTENER // Don't run the command since song was added
                            } else {
                                messageAction(embed { description("Please type the # of the song you want") }).queue()
                                CommandSession.ResponseAction.IGNORE_COMMAND // Still waiting for valid response
                            }
                        }
                    } else {
                        // If the tracks are from directly from a url

                        val songs = playlist.lavaplayerSongs
                        songs.addAll(audioPlaylist.tracks)
                        playlist.lavaplayerSongs = songs
                        application.astolfoRepositories.guildPlaylistRepository.save(playlist)

                        messageAction(embed { setDescription("The playlist [${audioPlaylist.name}]($searchQuery) has been added to the playlist **${playlist.name}**") }).queue()
                    }
                }
            }
        }
        command("play", "p", "queue", "q") {
            musicAction {
                val musicSession = joinAction() ?: return@musicAction
                if (args.isBlank()) {
                    messageAction("Enter a playlist name!").queue()
                    return@musicAction
                }
                val playlist = application.astolfoRepositories.guildPlaylistRepository.findByGuildIdAndNameIgnoreCase(event.guild.idLong, args)
                        ?: application.astolfoRepositories.guildPlaylistRepository.findByPlaylistKey(args)
                if (playlist == null) {
                    messageAction("I couldn't find a playlist with that name!").queue()
                    return@musicAction
                }
                val audioPlaylist = object : AudioPlaylist {
                    override fun isSearchResult(): Boolean = false
                    override fun getName(): String = playlist.name
                    override fun getSelectedTrack(): AudioTrack? = null
                    override fun getTracks(): MutableList<AudioTrack> = playlist.lavaplayerSongs
                }
                musicSession.await().queueItem(audioPlaylist, event.channel, event.member, playlist.name, false, false) {
                    messageAction(it).queue()
                }
            }
        }
    }
}