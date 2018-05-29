package xyz.astolfo.astolfocommunity

import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.Role
import net.dv8tion.jda.core.entities.TextChannel

class AstolfoPermission(path: String, vararg val permissionDefaults: Permission) {
    val path = path.toLowerCase()
    val node: String

    init {
        val (_, node) = path.splitLast(".")
        this.node = node
    }

    constructor(path: String, node: String, vararg permissionDefaults: Permission) :
            this("${if (path.isNotBlank()) "$path." else ""}$node", *permissionDefaults)

    override fun hashCode(): Int {
        return path.hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (other is String) return path.equals(other, true)
        if (other is AstolfoPermission) return other.path.equals(path, true)
        return false
    }
}

object AstolfoPermissionUtils {

    fun hasPermission(member: Member, textChannel: TextChannel, permissions: Map<PermissionSetting, Boolean>, permissionToCheck: AstolfoPermission): Boolean? {
        var hasPermission: Boolean? = null
        val roles = member.roles.reversed().toMutableList()
        roles.add(0, member.guild.publicRole)
        roles.forEach { role -> hasPermission(role, textChannel, permissions, permissionToCheck)?.let { hasPermission = it } }
        println(hasPermission)
        return hasPermission
    }

    fun hasPermission(role: Role, textChannel: TextChannel, permissions: Map<PermissionSetting, Boolean>, permissionToCheck: AstolfoPermission): Boolean? {
        var hasPermission: Boolean? = null

        val rolePermissions = permissions.filterKeys { it.role == role.idLong }
        // Guild scope
        if (findPermission(rolePermissions.filter { it.key.channel == 0L && !it.value }.keys, permissionToCheck)) hasPermission = false
        if (findPermission(rolePermissions.filter { it.key.channel == 0L && it.value }.keys, permissionToCheck)) hasPermission = true

        // Channel scope
        if (findPermission(rolePermissions.filter { it.key.channel == textChannel.idLong && !it.value }.keys, permissionToCheck)) hasPermission = false
        if (findPermission(rolePermissions.filter { it.key.channel == textChannel.idLong && it.value }.keys, permissionToCheck)) hasPermission = true

        return hasPermission
    }

    fun findPermission(permissions: Set<PermissionSetting>, permissionToCheck: AstolfoPermission): Boolean {
        permissions.forEach { setting -> if (permissionMatches(setting.node, permissionToCheck)) return true }
        return false
    }

    fun permissionMatches(permission: String, permissionToCheck: AstolfoPermission): Boolean {
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