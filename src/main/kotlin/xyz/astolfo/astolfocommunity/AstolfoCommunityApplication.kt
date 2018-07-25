package xyz.astolfo.astolfocommunity

import com.github.natanbc.weeb4j.TokenType
import com.github.natanbc.weeb4j.Weeb4J
import com.timgroup.statsd.NonBlockingStatsDClient
import io.sentry.Sentry
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.bot.sharding.DefaultShardManagerBuilder
import net.dv8tion.jda.bot.sharding.ShardManager
import net.dv8tion.jda.core.OnlineStatus
import net.dv8tion.jda.core.entities.Game
import net.dv8tion.jda.core.events.guild.GuildJoinEvent
import net.dv8tion.jda.core.events.guild.GuildLeaveEvent
import net.dv8tion.jda.core.hooks.ListenerAdapter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.boot.web.servlet.ServletContextInitializer
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.web.servlet.HandlerExceptionResolver
import xyz.astolfo.astolfocommunity.commands.CommandHandler
import xyz.astolfo.astolfocommunity.games.GameHandler
import xyz.astolfo.astolfocommunity.messages.MessageCache
import xyz.astolfo.astolfocommunity.modules.admin.JoinLeaveManager
import xyz.astolfo.astolfocommunity.modules.music.MusicManager
import xyz.astolfo.astolfocommunity.support.DonationManager
import java.util.concurrent.TimeUnit


@Suppress("LeakingThis")
@SpringBootApplication
@EnableCaching
@EnableConfigurationProperties(AstolfoProperties::class)
class AstolfoCommunityApplication(val astolfoRepositories: AstolfoRepositories,
                                  val properties: AstolfoProperties) {

    final val donationManager = DonationManager(this, properties)
    final val musicManager = MusicManager(this, properties)
    final val weeb4J = Weeb4J.Builder().setToken(TokenType.WOLKE, properties.weeb_token).build()
    final val commandHandler = CommandHandler(this)
    final val shardManager: ShardManager
    // TODO: Move this to a better location
    final val staffMemberIds = properties.staffMemberIds.split(",").mapNotNull { it.toLongOrNull() }
    final val statsDClient = NonBlockingStatsDClient(properties.datadog_prefix, "localhost", 8125, "tag:value")

    init {
        if (properties.sentry_dsn.isNotBlank()) Sentry.init(properties.sentry_dsn)

        val statsListener = StatsListener(this)
        val shardManagerBuilder = DefaultShardManagerBuilder()
                .setCompressionEnabled(true)
                .setToken(properties.token)
                .setStatus(OnlineStatus.DO_NOT_DISTURB)
                .setGame(Game.watching("myself boot"))
                .addEventListeners(commandHandler, statsListener, musicManager.lavaLink, musicManager.musicManagerListener, JoinLeaveManager(this).listener)
                .setEnableShutdownHook(false)
                .setShardsTotal(properties.shard_count)
        if (properties.custom_gateway_enabled) shardManagerBuilder.setSessionController(AstolfoSessionController(properties.custom_gateway_url, properties.custom_gateway_delay))
        shardManager = shardManagerBuilder.build()
        statsListener.init()
        launch {
            while (isActive && shardManager.shardsRunning != shardManager.shardsTotal) delay(1000)
            shardManager.setGame(Game.listening("the community"))
            shardManager.setStatus(OnlineStatus.ONLINE)
        }
    }

    @Bean
    fun sentryExceptionResolver(): HandlerExceptionResolver {
        return io.sentry.spring.SentryExceptionResolver()
    }

    @Bean
    fun sentryServletContextInitializer(): ServletContextInitializer {
        return io.sentry.spring.SentryServletContextInitializer()
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
    var discordbotlist_token = ""
    var discordbotlist_password = ""
    var datadog_prefix = ""
    var custom_gateway_enabled = false
    var custom_gateway_url = ""
    var custom_gateway_delay = 0
    var patreon_users = ""
    var patreon_url = ""
    var patreon_auth = ""
    var sentry_dsn = ""
    var genius_token = ""
}

class StatsListener(val application: AstolfoCommunityApplication) : ListenerAdapter() {
    internal fun init() {
        launch {
            while (isActive) {
                application.statsDClient.recordGaugeValue("guilds", application.shardManager.guilds.size.toLong())
                application.statsDClient.recordGaugeValue("musicsessions", application.musicManager.sessionCount.toLong())
                application.statsDClient.recordGaugeValue("musiclinks", application.musicManager.lavaLink.links.size.toLong())
                application.statsDClient.recordGaugeValue("musiclinksconnected", application.musicManager.lavaLink.links.filter {
                    it.jda.getGuildById(it.guildIdLong)?.selfMember?.voiceState?.inVoiceChannel() == true
                }.size.toLong())
                application.statsDClient.recordGaugeValue("users", application.shardManager.users.toSet().size.toLong())
                application.statsDClient.recordGaugeValue("cachedmessages", MessageCache.cachedMessageCount.toLong())
                delay(1, TimeUnit.MINUTES)
            }
        }
    }

    override fun onGuildJoin(event: GuildJoinEvent?) {
        application.statsDClient.incrementCounter("guildjoin")
    }

    override fun onGuildLeave(event: GuildLeaveEvent?) {
        application.statsDClient.incrementCounter("guildleave")
    }
}

fun main(args: Array<String>) {
    runApplication<AstolfoCommunityApplication>(*args)
}
