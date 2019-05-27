package xyz.astolfo.astolfocommunity

import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.Role
import net.dv8tion.jda.core.entities.TextChannel
import xyz.astolfo.astolfocommunity.commands.CommandPermission

object AstolfoPermissionUtils {

    fun hasPermission(
        member: Member,
        textChannel: TextChannel,
        permissions: Map<PermissionSetting, Boolean>,
        permissionToCheck: CommandPermission
    ): Boolean? {
        var hasPermission: Boolean? = null
        val roles = member.roles.reversed().toMutableList()
        roles.add(0, member.guild.publicRole)
        roles.forEach { role ->
            hasPermission(role, textChannel, permissions, permissionToCheck)?.let {
                hasPermission = it
            }
        }
        return hasPermission
    }

    fun hasPermission(
        role: Role,
        textChannel: TextChannel,
        permissions: Map<PermissionSetting, Boolean>,
        permissionToCheck: CommandPermission
    ): Boolean? {
        var hasPermission: Boolean? = null

        val rolePermissions = permissions.filterKeys { it.role == role.idLong }
        // Guild scope
        if (findPermission(
                rolePermissions.filter { it.key.channel == 0L && !it.value }.keys,
                permissionToCheck
            )
        ) hasPermission = false
        if (findPermission(
                rolePermissions.filter { it.key.channel == 0L && it.value }.keys,
                permissionToCheck
            )
        ) hasPermission = true

        // Channel scope
        if (findPermission(
                rolePermissions.filter { it.key.channel == textChannel.idLong && !it.value }.keys,
                permissionToCheck
            )
        ) hasPermission = false
        if (findPermission(
                rolePermissions.filter { it.key.channel == textChannel.idLong && it.value }.keys,
                permissionToCheck
            )
        ) hasPermission = true

        return hasPermission
    }

    fun findPermission(permissions: Set<PermissionSetting>, permissionToCheck: CommandPermission): Boolean {
        permissions.forEach { setting -> if (permissionMatches(setting.node, permissionToCheck)) return true }
        return false
    }

    fun permissionMatches(permission: String, permissionToCheck: CommandPermission): Boolean {
        // music == music
        if (permission.equals(permissionToCheck.path, true)) return true
        val checkPath = permissionToCheck.path.split(".")
        val settingPath = permission.split(".")
        settingPath.forEachIndexed { index, node ->
            // * == create
            if (node == "*") return true
            // music.play == music
            if (checkPath.size <= index) return true
            // music != fun
            if (!checkPath[index].equals(node, true)) return false
        }
        // music != music.play
        return false
    }

}