package xyz.astolfo.astolfocommunity.modules

import xyz.astolfo.astolfocommunity.commands.Command
import xyz.astolfo.astolfocommunity.commands.CommandBuilder
import xyz.astolfo.astolfocommunity.commands.CommandContext
import xyz.astolfo.astolfocommunity.commands.findByName
import xyz.astolfo.astolfocommunity.modules.admin.createAdminModule
import xyz.astolfo.astolfocommunity.modules.music.createMusicModule
import xyz.astolfo.astolfocommunity.splitFirst

val modules = initModules()

fun initModules(): List<Module> {
    val funModule = createFunModule()
    val musicModule = createMusicModule()
    val adminModule = createAdminModule()
    val casinoModule = createCasinoModule()
    val staffModule = createStaffModule()
    val nsfwModule = createNSFWModule()

    return listOf(InfoModule, funModule, musicModule, adminModule, casinoModule, staffModule, nsfwModule)
}

abstract class Module(
    val name: String,
    val type: Type
) {

    private val _children = mutableListOf<Command>()
    val children: List<Command> = _children

    protected operator fun Command.unaryPlus() {
        this@Module._children += this
    }

    /**
     * Try to execute the command message in this module, returns true on success
     */
    suspend fun execute(context: CommandContext): Boolean {
        val (commandName, commandContent) = context.commandContent.splitFirst(" ")

        val subCommand = _children.findByName(commandName) ?: return false

        if (!executeInherited(context)) return true

        // TODO add permissions support

        subCommand.execute(
            CommandContext(
                context.application,
                "${context.commandPath} $commandName".trim(),
                commandContent.trim(),
                context.data
            )
        )
        return true
    }

    protected open suspend fun executeInherited(context: CommandContext): Boolean = true

    enum class Type {
        NSFW,
        ADMIN,
        GENERIC
    }
}

@Deprecated("Don't use the builder")
class ModuleBuilder(
    val name: String,
    val type: Module.Type
) {

    constructor(name: String, hidden: Boolean, nsfw: Boolean)
        : this(name, if (nsfw) Module.Type.NSFW else if (hidden) Module.Type.ADMIN else Module.Type.GENERIC)

    var commands = mutableListOf<Command>()
    val inheritedActions = mutableListOf<suspend CommandContext.() -> Boolean>()

    fun inheritedAction(inheritedAction: suspend CommandContext.() -> Boolean) =
        apply { this.inheritedActions.add(inheritedAction) }

    @Deprecated("Dont use the command builder")
    fun command(name: String, vararg alts: String, builder: CommandBuilder.() -> Unit) = apply {
        val commandBuilder = CommandBuilder(this.name, name, alts.toList())
        builder.invoke(commandBuilder)
        commands.add(commandBuilder.build())
    }

    fun build() = object : Module(name, type) {
        init {
            commands.forEach {
                +it
            }
        }

        override suspend fun executeInherited(context: CommandContext): Boolean {
            return inheritedActions.all { it.invoke(context) }
        }
    }
}

@Deprecated("Don't use the builder")
inline fun module(
    name: String,
    hidden: Boolean = false,
    nsfw: Boolean = false,
    builder: ModuleBuilder.() -> Unit
): Module {
    val moduleBuilder = ModuleBuilder(name, hidden, nsfw)
    builder.invoke(moduleBuilder)
    return moduleBuilder.build()
}