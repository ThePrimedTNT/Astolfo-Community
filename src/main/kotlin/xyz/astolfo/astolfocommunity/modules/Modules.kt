package xyz.astolfo.astolfocommunity.modules

import xyz.astolfo.astolfocommunity.commands.Command
import xyz.astolfo.astolfocommunity.commands.CommandBuilder
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

    return listOf(infoModule, funModule, musicModule, adminModule, casinoModule, staffModule)
}

class Module(val name: String, val hidden: Boolean, val commands: List<Command>)

class ModuleBuilder(val name: String, val hidden: Boolean) {
    var commands = mutableListOf<Command>()
    fun build() = Module(name, hidden, commands)
}

inline fun module(name: String, hidden: Boolean = false, builder: ModuleBuilder.() -> Unit): Module {
    val moduleBuilder = ModuleBuilder(name, hidden)
    builder.invoke(moduleBuilder)
    return moduleBuilder.build()
}

inline fun ModuleBuilder.command(name: String, vararg alts: String, builder: CommandBuilder.() -> Unit) {
    val commandBuilder = CommandBuilder(this.name, name, alts.toList())
    builder.invoke(commandBuilder)
    commands.add(commandBuilder.build())
}