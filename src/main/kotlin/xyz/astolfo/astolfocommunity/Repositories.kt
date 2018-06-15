package xyz.astolfo.astolfocommunity

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import lavalink.client.LavalinkUtil
import org.hibernate.annotations.GenericGenerator
import org.hibernate.annotations.LazyCollection
import org.hibernate.annotations.LazyCollectionOption
import org.hibernate.engine.spi.SharedSessionContractImplementor
import org.hibernate.id.IdentifierGenerator
import org.springframework.cache.annotation.CacheConfig
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Component
import java.io.Serializable
import java.sql.SQLException
import java.util.*
import javax.persistence.*


@Component
class AstolfoRepositories(val guildSettingsRepository: GuildSettingsRepository,
                          val userProfileRepository: UserProfileRepository,
                          val radioRepository: RadioRepository,
                          val guildPlaylistRepository: GuildPlaylistRepository) {
    fun getEffectiveGuildSettings(id: Long): GuildSettings = guildSettingsRepository.findById(id).orElse(null)
            ?: GuildSettings(guildId = id)

    fun getEffectiveUserProfile(id: Long): UserProfile = userProfileRepository.findById(id).orElse(null)
            ?: UserProfile(userId = id)

    fun findRadioStation(query: String): List<RadioEntry> {
        query.toLongOrNull()?.let { id ->
            radioRepository.findById(id).orElse(null).let { return listOf(it) }
        }
        return radioRepository.findByNameLike("%${query.toCharArray().joinToString(separator = "%")}%")
    }
}

@CacheConfig(cacheNames = ["guildSettings"])
interface GuildSettingsRepository : CrudRepository<GuildSettings, Long> {
    @CacheEvict("guildSettings", key = "#entity.guildId")
    override fun <S : GuildSettings?> save(entity: S): S

    @Cacheable("guildSettings", key = "#id")
    override fun findById(id: Long): Optional<GuildSettings>
}

@CacheConfig(cacheNames = ["userProfiles"])
interface UserProfileRepository : CrudRepository<UserProfile, Long> {
    @CacheEvict("userProfiles", key = "#entity.userId")
    override fun <S : UserProfile?> save(entity: S): S

    @Cacheable("userProfiles", key = "#id")
    override fun findById(id: Long): Optional<UserProfile>

    fun findTop50ByOrderByCreditsDesc(): List<UserProfile>

    @Query("select p from UserProfile p where p.userUpvote.lastUpvote > 0 AND NOW() - p.userUpvote.lastUpvote > 17280000 AND p.userUpvote.remindedUpvote = false")
    fun findUpvoteReminder(): List<UserProfile>
}

@CacheConfig(cacheNames = ["radios"])
interface RadioRepository : CrudRepository<RadioEntry, Long> {
    @CacheEvict("radios", key = "#entity.id")
    override fun <S : RadioEntry?> save(entity: S): S

    @Cacheable("radios", key = "#id")
    override fun findById(id: Long): Optional<RadioEntry>

    fun findByNameLike(name: String): List<RadioEntry>
}

@CacheConfig(cacheNames = ["guildPlaylists"])
interface GuildPlaylistRepository : CrudRepository<GuildPlaylistEntry, Long> {
    @CacheEvict("guildPlaylists", key = "#entity.playlistKey")
    override fun <S : GuildPlaylistEntry?> save(entity: S): S

    @Cacheable("guildPlaylists", key = "#playlistKey", condition = "#result != null")
    fun findByPlaylistKey(playlistKey: String): GuildPlaylistEntry?

    @Cacheable("guildPlaylists", key = "#result.playlistKey", condition = "#result != null")
    fun findByGuildIdAndNameIgnoreCase(guildId: Long, name: String): GuildPlaylistEntry?

    @Cacheable("guildPlaylists", key = "#result.playlistKey", condition = "#result != null")
    fun findByGuildId(guildId: Long): List<GuildPlaylistEntry>
}

@Entity
data class GuildPlaylistEntry(@Id
                              @GenericGenerator(name = "gpl_key_gen", strategy = "xyz.astolfo.astolfocommunity.GuildPlaylistKeyGen")
                              @GeneratedValue(generator = "gpl_key_gen")
                              @Column(length = 32)
                              val playlistKey: String? = null,
                              var guildId: Long = 0,
                              val name: String = "",
                              @ElementCollection
                              @CollectionTable(name = "guild_playlist_songs", joinColumns = [(JoinColumn(name = "playlistKey"))])
                              @Column(name = "songs", columnDefinition = "LONGTEXT")
                              @LazyCollection(LazyCollectionOption.FALSE)
                              var songs: List<String> = mutableListOf()) {

    var lavaplayerSongs: MutableList<AudioTrack>
        get() = songs.map { LavalinkUtil.toAudioTrack(it) }.toMutableList()
        set(value) {
            songs = value.map { LavalinkUtil.toMessage(it) }
        }

}

class GuildPlaylistKeyGen : IdentifierGenerator {

    override fun generate(session: SharedSessionContractImplementor?, key: Any?): Serializable? {
        try {
            val query = session!!.createQuery("FROM GuildPlaylistEntry p WHERE p.playlistKey=:playlistKey")
            while (true) {
                val randomUUID = UUID.randomUUID().toString().replace("-", "")
                query.setParameter("playlistKey", randomUUID)
                val rs = query.uniqueResultOptional()

                if (!rs.isPresent) {
                    println("Generated Id: $randomUUID")
                    return randomUUID
                }
            }
        } catch (e: SQLException) {
            e.printStackTrace()
        }
        return null
    }

}

@Entity
data class RadioEntry(@Id @GeneratedValue(strategy = GenerationType.AUTO)
                      val id: Long? = null,
                      var name: String = "",
                      val url: String = "",
                      val category: String = "",
                      val genre: String = "")

@Entity
data class UserProfile(@Id val userId: Long = 0L,
                       var credits: Long = 0L,
                       val daily: UserDaily = UserDaily(),
                       val userUpvote: UserUpvote = UserUpvote())

@Embeddable
data class UserDaily(var lastDaily: Long = -1L)

@Embeddable
data class UserUpvote(var lastUpvote: Long = -1L, var remindedUpvote: Boolean = false) {
    val timeSinceLastUpvote
        get() = System.currentTimeMillis() - lastUpvote
}

@Entity
data class GuildSettings(@Id val guildId: Long = 0L,
                         var prefix: String = "",
                         @ElementCollection
                         @LazyCollection(LazyCollectionOption.FALSE)
                         @CollectionTable(name = "guild_settings_permissions", joinColumns = [(JoinColumn(name = "guildId"))])
                         @MapKeyClass(PermissionSetting::class)
                         @Column(name = "allow")
                         var permissions: Map<PermissionSetting, Boolean> = mutableMapOf())

@Embeddable
data class PermissionSetting(val role: Long = 0L, val channel: Long = 0L, @Column(length = 45) val node: String = "")