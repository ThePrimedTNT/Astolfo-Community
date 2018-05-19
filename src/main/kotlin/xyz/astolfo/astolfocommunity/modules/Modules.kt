package xyz.astolfo.astolfocommunity.modules

import xyz.astolfo.astolfocommunity.Command
import xyz.astolfo.astolfocommunity.CommandBuilder
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