package xyz.astolfo.astolfocommunity

import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.bot.sharding.DefaultShardManagerBuilder
import net.dv8tion.jda.bot.sharding.ShardManager
import net.dv8tion.jda.core.OnlineStatus
import net.dv8tion.jda.core.entities.Game
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(AstolfoProperties::class)
class AstolfoCommunityApplication(properties: AstolfoProperties) {

    val shardManager: ShardManager = DefaultShardManagerBuilder()
            .setCompressionEnabled(true)
            .setToken(properties.token)
            .setStatus(OnlineStatus.DO_NOT_DISTURB)
            .setGame(Game.watching("myself boot"))
            .addEventListeners(MessageListener(this))
            .build()

    init {
        launch {
            while (isActive && shardManager.shardsTotal != shardManager.shardsTotal) delay(1000)
            shardManager.setGame(Game.listening("the community"))
            shardManager.setStatus(OnlineStatus.ONLINE)
        }
    }
}

@ConfigurationProperties
class AstolfoProperties {
    var token = ""
}

fun main(args: Array<String>) {
    runApplication<AstolfoCommunityApplication>(*args)
}
