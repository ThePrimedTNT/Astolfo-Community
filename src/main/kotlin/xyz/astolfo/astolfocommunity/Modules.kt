package xyz.astolfo.astolfocommunity

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
        command("help") {
            action {
                message(embed {
                    title("Astolfo Command Help")
                    description("If you're having  trouble with anything, you can always stop by our support server!" +
                            "\nInvite Link: https://discord.gg/23RB2Wc")
                    for (module in modules) {
                        val commandNames = module.commands.map { "`${it.name}` " }.fold("", {a, b -> "$a $b"})
                        field("${module.name} Commands", commandNames, false)
                    }
                }).queue()
            }
        }
    }
    val funmodule = createFunModule()
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

fun ModuleBuilder.command(name: String, vararg alts: String, builder: CommandBuilder.() -> Unit) {
    val commandBuilder = CommandBuilder(name, alts)
    builder.invoke(commandBuilder)
    commands.add(commandBuilder.build())
}