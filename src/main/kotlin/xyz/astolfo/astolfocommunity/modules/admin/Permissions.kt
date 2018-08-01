package xyz.astolfo.astolfocommunity.modules.admin

import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Role
import net.dv8tion.jda.core.entities.TextChannel
import xyz.astolfo.astolfocommunity.PermissionSetting
import xyz.astolfo.astolfocommunity.commands.ArgsIterator
import xyz.astolfo.astolfocommunity.commands.CommandExecution
import xyz.astolfo.astolfocommunity.commands.argsIterator
import xyz.astolfo.astolfocommunity.commands.next
import xyz.astolfo.astolfocommunity.menus.*
import xyz.astolfo.astolfocommunity.modules.ModuleBuilder

internal fun ModuleBuilder.permissionCommand() = command("permissions") {
    permission(Permission.ADMINISTRATOR)

    class PermissionData {
        lateinit var role: Role
        var textChannel: TextChannel? = null
        lateinit var permission: String
    }

    suspend fun CommandExecution.parseScope(args: ArgsIterator, data: PermissionData, includePermission: Boolean): Boolean {
        val scopeQuery = args.next("")
        val scope = selectionBuilder<String>()
                .results(listOf("guild", "channel").filter { it.contains(scopeQuery, ignoreCase = true) })
                .noResultsMessage("Unknown scope! Valid scopes: **guild**,**channel**")
                .description("Type the number of the scope you want.")
                .execute() ?: return false

        if (scope == "channel") data.textChannel = textChannelSelectionBuilder(args.next("")).execute() ?: return false

        if (includePermission) {
            data.permission = args.next("").let {
                if (it.isBlank()) chatInput("Input a permission").execute() ?: return false
                else it
            }
        }

        data.role = roleSelectionBuilder(args.next("")).execute() ?: return false
        return true
    }

    command("grant") {
        usage("channel <channel> <permission> <role>", "guild <permission> <role>")
        description("Grants permission to a role in a guild/channel")
        stageActions<PermissionData> {
            action({ parseScope(args.argsIterator(), it, true) })
            basicAction {
                val (role, channel, permission) = Triple(it.role, it.textChannel, it.permission)
                withPermissions { permissions ->
                    permissions[PermissionSetting(role.idLong, channel?.idLong ?: 0, permission)] = true
                }
                messageAction(embed("You have granted the permission **$permission** to the role **${role.name.let { if (it.startsWith("@")) it.substring(1) else it }}** under the ${if (channel == null) "guild" else "channel ${channel.asMention}"} scope")).queue()
            }
        }
    }

    command("deny") {
        usage("channel <channel> <permission> <role>", "guild <permission> <role>")
        description("Denies permission to a role in a guild/channel")
        stageActions<PermissionData> {
            action({ parseScope(args.argsIterator(), it, true) })
            basicAction {
                val (role, channel, permission) = Triple(it.role, it.textChannel, it.permission)
                withPermissions { permissions ->
                    permissions[PermissionSetting(role.idLong, channel?.idLong ?: 0, permission)] = false
                }
                messageAction(embed("You have denied the permission **$permission** to the role **${role.name.let { if (it.startsWith("@")) it.substring(1) else it }}** under the ${if (channel == null) "guild" else "channel ${channel.asMention}"} scope")).queue()
            }
        }
    }

    command("reset") {
        usage("channel <channel> <permission> <role>", "guild <permission> <role>")
        description("Defaults permission to a role in a guild/channel")
        stageActions<PermissionData> {
            action({ parseScope(args.argsIterator(), it, true) })
            basicAction {
                val (role, channel, permission) = Triple(it.role, it.textChannel, it.permission)
                withPermissions { permissions ->
                    permissions.remove(PermissionSetting(role.idLong, channel?.idLong ?: 0, permission))
                }
                messageAction(embed("You have defaulted the permission **$permission** to the role **${role.name.let { if (it.startsWith("@")) it.substring(1) else it }}** under the ${if (channel == null) "guild" else "channel ${channel.asMention}"} scope")).queue()
            }
        }
    }

    command("info") {
        usage("channel <channel> <role>", "guild <role>")
        description("Displays info about a role in a guild/channel scope")
        stageActions<PermissionData> {
            action({ parseScope(args.argsIterator(), it, false) })
            basicAction {
                val (role, channel) = Pair(it.role, it.textChannel)

                val permissions = getGuildSettings().permissions.filter {
                    @Suppress("CascadeIf")
                    if (it.key.role != role.idLong) false
                    else if (channel != null) it.key.channel == channel.idLong
                    else true
                }

                paginator("Permission Info - ${if (channel != null) "channel/${channel.name}" else "guild"} - ${role.name}") {
                    provider(10, permissions.map { entry ->
                        val setting = entry.key
                        val allow = entry.value
                        "${if (allow) "✅" else "❌"} ${setting.node}"
                    })
                }
            }
        }
    }
}

private suspend inline fun <E> CommandExecution.withPermissions(block: (MutableMap<PermissionSetting, Boolean>) -> E): E = withGuildSettings {
    val permissions = it.permissions.toMutableMap()
    val result = block.invoke(permissions)
    it.permissions = permissions
    result
}