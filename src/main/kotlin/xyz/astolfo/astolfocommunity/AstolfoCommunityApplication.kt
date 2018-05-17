package xyz.astolfo.astolfocommunity

import com.github.natanbc.weeb4j.TokenType
import com.github.natanbc.weeb4j.Weeb4J
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
import org.springframework.cache.annotation.EnableCaching
import xyz.astolfo.astolfocommunity.games.GameHandler
import xyz.astolfo.astolfocommunity.modules.MusicManager
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.context.annotation.Bean
import org.springframework.web.client.RestTemplate



@SpringBootApplication
@EnableCaching
@EnableConfigurationProperties(AstolfoProperties::class)
class AstolfoCommunityApplication(final val astolfoRepositories: AstolfoRepositories, final val properties: AstolfoProperties) {

    final val musicManager = MusicManager(this, properties)
    final val weeb4J = Weeb4J.Builder().setToken(TokenType.WOLKE, properties.weeb_token).build()
    final val gameHandler = GameHandler()
    final val commandHandler = CommandHandler(this)
    final val shardManager: ShardManager
    // TODO: Move this to a better location
    final val staffMemberIds = properties.staffMemberIds.split(",").map { it.toLong() }

    init {
        shardManager = DefaultShardManagerBuilder()
                .setCompressionEnabled(true)
                .setToken(properties.token)
                .setStatus(OnlineStatus.DO_NOT_DISTURB)
                .setGame(Game.watching("myself boot"))
                .addEventListeners(commandHandler, musicManager.lavaLink)
                .setShardsTotal(properties.shard_count)
                .build()
        launch {
            while (isActive && shardManager.shardsRunning != shardManager.shardsTotal) delay(1000)
            shardManager.setGame(Game.listening("the community"))
            shardManager.setStatus(OnlineStatus.ONLINE)
        }
    }
}

@ConfigurationProperties
class AstolfoProperties {
    var token = ""
    var bot_user_id = ""
    var staffMemberIds = ""
    var shard_count = 0
    var default_prefix = ""
    var lavalink_nodes = ""
    var lavalink_password = ""
    var osu_api_token = ""
    var weeb_token = ""
    var dialogflow_token = ""
}

fun main(args: Array<String>) {
    runApplication<AstolfoCommunityApplication>(*args)
}
