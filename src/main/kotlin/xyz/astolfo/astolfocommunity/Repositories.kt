package xyz.astolfo.astolfocommunity

import org.springframework.cache.annotation.CacheConfig
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Component
import java.util.*
import javax.persistence.Embeddable
import javax.persistence.Entity
import javax.persistence.Id

@Component
class AstolfoRepositories(val guildSettingsRepository: GuildSettingsRepository,
                          val userProfileRepository: UserProfileRepository) {
    fun getEffectiveGuildSettings(id: Long): GuildSettings = guildSettingsRepository.findById(id).orElse(null)
            ?: GuildSettings(guildId = id)

    fun getEffectiveUserProfile(id: Long): UserProfile = userProfileRepository.findById(id).orElse(null)
            ?: UserProfile(userId = id)
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
}

@Entity
data class UserProfile(@Id val userId: Long = 0L,
                       var credits: Long = 0L,
                       val daily: UserDaily = UserDaily())

@Embeddable
data class UserDaily(var lastDaily: Long = -1L)

@Entity
data class GuildSettings(@Id val guildId: Long = 0L,
                         var prefix: String = "")