package xyz.astolfo.astolfocommunity

import com.oopsjpeg.osu4j.GameMode
import com.oopsjpeg.osu4j.backend.EndpointUsers
import com.oopsjpeg.osu4j.backend.Osu
import net.dv8tion.jda.core.JDAInfo
import java.awt.Color
import java.text.DecimalFormat


val modules = initModules()

fun initModules(): List<Module> {
    val infomodule = module("Info") {
        command("ping") {
            val format = DecimalFormat("#0.###")
            action {
                val pingStartTime = System.nanoTime()
                message("pinging").queue { message ->
                    val pingEndTime = System.nanoTime()
                    val pingTimeDifference = pingEndTime - pingStartTime
                    val processTime = pingStartTime - timeIssued
                    message.editMessage("REST Ping: **${format.format(pingTimeDifference / 1000000.0)}ms**" +
                            "\nDiscord WebSocket: **${message.jda.ping}ms**" +
                            "\nBot Process Time: **${format.format(processTime / 1000000.0)}ms**").queue()
                }
            }
        }
        command("about") {
            action {
                message(embed {
                    title("Astolfo Community Info")
                    val shardManager = application.shardManager
                    val guildCount = shardManager.guildCache.size()
                    val totalChannels = shardManager.textChannelCache.size() + shardManager.voiceChannelCache.size()
                    val userCount = shardManager.userCache.size()
                    field("Stats", "*$guildCount* servers" +
                            "\n*$totalChannels* channels," +
                            "\n*$userCount* users", false)
                    field("Library", JDAInfo.VERSION, false)
                }).queue()
            }
        }
        command("avatar") {
            action {
                message(embed {
                    val mentionedUser = event.message.mentionedUsers.getOrNull(0) ?: event.message.author

                    color(Color(173, 20, 87))
                    title("Astolfo Profile Pictures", mentionedUser.avatarUrl)
                    description("${mentionedUser.asMention} Profile Picture!")
                    image(mentionedUser.avatarUrl)
                }).queue()
            }
        }
    }
    val funmodule = module("Fun") {
        command("osu") {
            val purpleEmbedColor = Color(119, 60, 138)
            action {
                message(embed {
                    val osuPicture = "https://upload.wikimedia.org/wikipedia/commons/d/d3/Osu%21Logo_%282015%29.png"
                    color(purpleEmbedColor)
                    title("Astolfo Osu Integration")
                    description("**sig**  -  generates an osu signature of the user" +
                            "\n**profile**  -  gets user data from the osu api")
                    thumbnail(osuPicture)
                }).queue()
            }
            command("sig") {
                action {
                    val osuUsername = args
                    message(embed {
                        val url = "http://lemmmy.pw/osusig/sig.php?colour=purple&uname=$osuUsername&pp=1"
                        color(purpleEmbedColor)
                        title("Astolfo Osu Signature", url)
                        description("$osuUsername\'s Osu Signature!")
                        image(url)
                    }).queue()
                }
            }
            command("profile") {
                action {
                    message(embed {
                        val osu = Osu.getAPI(application.properties.osu_api_token)
                        val user = osu.users.query(EndpointUsers.ArgumentsBuilder(args)
                                .setMode(GameMode.STANDARD)
                                .build())
                        val topPlayBeatmap = user.getTopScores(1).get()[0].beatmap.get()
                        color(purpleEmbedColor)
                        title("Osu stats for ${user.username}", user.url.toString())

                        description("\nProfile url: ${user.url}" +
                                "\nGlobal Rank: **#${user.rank} (${user.pp}pp)**" +
                                "\nAccuracy: **${user.accuracy}%**" +
                                "\nTotal score: **${user.totalScore}**" +
                                "\nTop play: **$topPlayBeatmap** ${topPlayBeatmap.url}")
                    }).queue()
                }
            }
        }
    }
    val musicModule = createMusicModule()

    return listOf(infomodule, funmodule, musicModule)
}

class Module(val name: String, val commands: List<Command>)

class ModuleBuilder(val name: String) {
    var commands = mutableListOf<Command>()
    fun build() = Module(name, commands)
}

fun module(name: String, builder: ModuleBuilder.() -> Unit): Module {
    val moduleBuilder = ModuleBuilder(name)
    builder.invoke(moduleBuilder)
    return moduleBuilder.build()
}

fun ModuleBuilder.command(name: String, builder: CommandBuilder.() -> Unit) {
    val commandBuilder = CommandBuilder(name)
    builder.invoke(commandBuilder)
    commands.add(commandBuilder.build())
}