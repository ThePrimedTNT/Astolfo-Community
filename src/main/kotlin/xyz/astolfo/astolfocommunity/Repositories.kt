package xyz.astolfo.astolfocommunity

import org.springframework.cache.annotation.CacheConfig
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.CachePut
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Component
import java.util.*
import javax.persistence.Entity
import javax.persistence.Id

@Component
class AstolfoRepositories(val guildSettingsRepository: GuildSettingsRepository) {
    fun getEffectiveGuildSettings(id: Long): GuildSettings = guildSettingsRepository.findById(id).orElse(null)
            ?: GuildSettings(guildId = id)
}

@CacheConfig(cacheNames = ["guildsettings"])
interface GuildSettingsRepository : CrudRepository<GuildSettings, Long> {
    @CachePut("guildSettings")
    override fun <S : GuildSettings?> save(entity: S): S

    @Cacheable("guildSettings")
    override fun findById(id: Long): Optional<GuildSettings>
}

@Entity
data class GuildSettings(@Id val guildId: Long = 0L, var prefix: String = "")