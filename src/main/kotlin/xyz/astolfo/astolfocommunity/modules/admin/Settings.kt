package xyz.astolfo.astolfocommunity.modules.admin

import net.dv8tion.jda.core.Permission
import xyz.astolfo.astolfocommunity.GuildSettings
import xyz.astolfo.astolfocommunity.commands.CommandBuilder
import xyz.astolfo.astolfocommunity.commands.CommandExecution
import xyz.astolfo.astolfocommunity.menus.textChannelSelectionBuilder
import xyz.astolfo.astolfocommunity.messages.description
import xyz.astolfo.astolfocommunity.messages.field
import xyz.astolfo.astolfocommunity.messages.title
import xyz.astolfo.astolfocommunity.modules.ModuleBuilder

fun ModuleBuilder.settingsCommand() = command("settings") {
    permission(Permission.ADMINISTRATOR)
    usage("[name]")

    val prefixSetting = settingBuilder<String>("Prefix")
            .about("Changes the prefix of the bot in the guild. To reset the prefix just type **reset** as the prefix.")
            .usage("[prefix]")
            .default { application.properties.default_prefix }
            .get { it.prefix.takeIf { it.isNotBlank() } }
            .set { data, newPrefix ->
                data.prefix = if (newPrefix.equals("reset", true)) "" else newPrefix
                true
            }
            .setMessage { data ->
                messageAction(embed("Prefix successfully changed to **${data.getEffectiveGuildPrefix(application)}**")).queue()
            }
            .build()

    val blacklistChannelSetting = settingBuilder<List<Long>>("Blacklist Channel")
            .command("blacklist")
            .about("Denies usage of the bot in the specified channels, to remove the blacklist just type this command again.")
            .usage("[mention/name/id]")
            .default { listOf() }
            .toString { value ->
                value.mapNotNull { event.guild.getTextChannelById(it) }.joinToString { it.name }.takeIf { it.isNotEmpty() }
                        ?: "none"
            }
            .get { it.blacklistedChannels.takeIf { it.isNotEmpty() } }
            .set { data, newChannelQuery ->
                val textChannel = textChannelSelectionBuilder(newChannelQuery)
                        .title("Astoflo Settings")
                        .description("Type the number of the text channel you want to blacklist.")
                        .execute() ?: return@set false

                val textChannelId = textChannel.idLong
                val currentList = data.blacklistedChannels.toMutableList()
                if (currentList.contains(textChannelId)) {
                    currentList.remove(textChannelId)
                    messageAction(embed("Channel **${textChannel.name}** has been removed to the blacklist")).queue()
                } else {
                    currentList.add(textChannelId)
                    messageAction(embed("Channel **${textChannel.name}** has been added to the blacklist")).queue()
                }
                data.blacklistedChannels = currentList
                true
            }.setMessage { _ -> }.build()

    val settings = listOf(prefixSetting, blacklistChannelSetting)

    action {
        val guildPrefix = getGuildSettings().getEffectiveGuildPrefix(application)
        messageAction(embed {
            title("Astolfo Settings")
            description("Type **${guildPrefix}settings <name>** to get more information about the setting.")
            for (setting in settings) {
                field(setting.name, setting.command, true)
            }
        }).queue()
    }

    for (setting in settings) {
        settingCommand(setting)
    }
}

private fun <T> CommandBuilder.settingCommand(setting: Setting<T>) {
    command(setting.command) {
        action {
            if (args.isEmpty()) {
                val guildSettings = getGuildSettings()
                val guildPrefix = guildSettings.getEffectiveGuildPrefix(application)
                messageAction(embed {
                    title("Astolfo Settings - ${setting.name}")
                    description(setting.about)
                    val default = setting.default(this@action)
                    val currentSetting = setting.get(this@action, guildSettings)
                    val defaultAsString = setting.toString(this@action, default)
                    val currentAsString = if (currentSetting != null) setting.toString(this@action, currentSetting) else defaultAsString

                    field("Usage", "**${guildPrefix}settings ${setting.command} ${setting.usage}**", false)
                    field("Current", currentAsString, true)
                    field("Default", defaultAsString, true)
                }).queue()
            } else {
                withGuildSettings { data ->
                    if (setting.set(this@action, data, args)) setting.setMessage(this@action, data)
                }
            }
        }
    }
}

private fun <T> settingBuilder(name: String) = SettingsBuilder<T>(name)

private class Setting<T>(val command: String,
                         val name: String,
                         val about: String,
                         val usage: String,
                         val default: CommandExecution.() -> T,
                         val toString: CommandExecution.(T) -> String,
                         val setMessage: CommandExecution.(GuildSettings) -> Unit,
                         val set: suspend CommandExecution.(GuildSettings, String) -> Boolean,
                         val get: CommandExecution.(GuildSettings) -> T?)

private class SettingsBuilder<T>(val name: String) {
    private var command = name.toLowerCase().replace(" ", "_")
    private var about = "No description set"
    private var usage = "No specified usage"
    private lateinit var default: CommandExecution.() -> T
    private var set: suspend CommandExecution.(GuildSettings, String) -> Boolean = { _, _ -> false }
    private lateinit var get: CommandExecution.(GuildSettings) -> T?
    private var setMessage: CommandExecution.(GuildSettings) -> Unit = {
        messageAction(embed("Setting successfully set!")).queue()
    }
    private var toString: CommandExecution.(T) -> String = { obj -> obj.toString() }

    fun command(value: String) = also { command = value }
    fun about(value: String) = also { about = value }
    fun default(value: CommandExecution.() -> T) = also { default = value }
    fun set(value: suspend CommandExecution.(GuildSettings, String) -> Boolean) = also { set = value }
    fun get(value: CommandExecution.(GuildSettings) -> T?) = also { get = value }
    fun usage(value: String) = also { usage = value }
    fun setMessage(value: CommandExecution.(GuildSettings) -> Unit) = also { setMessage = value }
    fun toString(value: CommandExecution.(T) -> String) = also { toString = value }

    fun build() = Setting(command, name, about, usage, default, toString, setMessage, set, get)
}