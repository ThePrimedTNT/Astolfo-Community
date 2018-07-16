package xyz.astolfo.astolfocommunity.modules

import xyz.astolfo.astolfocommunity.commands.Command
import xyz.astolfo.astolfocommunity.commands.CommandBuilder
import xyz.astolfo.astolfocommunity.commands.CommandExecution
import xyz.astolfo.astolfocommunity.modules.admin.createAdminModule
import xyz.astolfo.astolfocommunity.modules.music.createMusicModule

val modules = initModules()

fun initModules(): List<Module> {
    val infoModule = createInfoModule()
    val funModule = createFunModule()
    val musicModule = createMusicModule()
    val adminModule = createAdminModule()
    val casinoModule = createCasinoModule()
    val staffModule = createStaffModule()
    val nsfwModule = createNSFWModule()

    return listOf(infoModule, funModule, musicModule, adminModule, casinoModule, staffModule, nsfwModule)
}

class Module(
        val name: String,
        val hidden: Boolean,
        val nsfw: Boolean,
        val inheritedActions: List<suspend CommandExecution.() -> Boolean>,
        val commands: List<Command>
)

class ModuleBuilder(val name: String, val hidden: Boolean, val nsfw: Boolean) {
    var commands = mutableListOf<Command>()
    val inheritedActions = mutableListOf<suspend CommandExecution.() -> Boolean>()

    fun inheritedAction(inheritedAction: suspend CommandExecution.() -> Boolean) = apply { this.inheritedActions.add(inheritedAction) }
    fun command(name: String, vararg alts: String, builder: CommandBuilder.() -> Unit) = apply {
        val commandBuilder = CommandBuilder(this.name, name, alts.toList())
        builder.invoke(commandBuilder)
        commands.add(commandBuilder.build())
    }

    fun build() = Module(name, hidden, nsfw, inheritedActions, commands)
}

inline fun module(name: String, hidden: Boolean = false, nsfw: Boolean = false, builder: ModuleBuilder.() -> Unit): Module {
    val moduleBuilder = ModuleBuilder(name, hidden, nsfw)
    builder.invoke(moduleBuilder)
    return moduleBuilder.build()
}