package xyz.astolfo.astolfocommunity.support

import com.github.salomonbrys.kotson.fromJson
import com.google.gson.JsonObject
import kotlinx.coroutines.experimental.channels.Channel
import kotlinx.coroutines.experimental.channels.actor
import kotlinx.coroutines.experimental.channels.sendBlocking
import net.dv8tion.jda.core.entities.Member
import net.dv8tion.jda.core.entities.Message
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import xyz.astolfo.astolfocommunity.*
import xyz.astolfo.astolfocommunity.messages.message
import java.util.concurrent.TimeUnit

class DonationManager(private val application: AstolfoCommunityApplication,
                      private val properties: AstolfoProperties) {

    class PatreonEntry(val id: Long,
                       val discord_id: Long?,
                       val reward_id: Long?,
                       val amount_cents: Long) {
        val supportLevel
            get() = SupportLevel.toLevel(reward_id, false)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PatreonEntry

            if (id != other.id) return false

            return true
        }

        override fun hashCode(): Int {
            return id.hashCode()
        }
    }

    private val entries = mutableSetOf<PatreonEntry>()

    private interface WSEvent
    private class ReadyEvent(val entries: List<PatreonEntry>) : WSEvent
    private class ConsumeEvent(val entry: PatreonEntry) : WSEvent
    private class CreateEvent(val entry: PatreonEntry) : WSEvent
    private class UpdateEvent(val entry: PatreonEntry) : WSEvent
    private class DeleteEvent(val entryId: Long) : WSEvent

    private val wsActor = actor<WSEvent>(capacity = Channel.UNLIMITED) {
        for (event in channel) {
            handleEvent(event)
        }
    }

    private fun handleEvent(event: WSEvent) {
        when (event) {
            is ReadyEvent -> {
                synchronized(entries) {
                    if (entries.isEmpty()) {
                        // First time
                        entries.addAll(event.entries)
                    } else {
                        // Reconnect
                        for (entry in event.entries) {
                            handleEvent(ConsumeEvent(entry))
                        }
                    }
                }
            }
            is ConsumeEvent -> {
                val exists = synchronized(entries) { entries.contains(event.entry) }
                if (exists) {
                    handleEvent(UpdateEvent(event.entry))
                } else {
                    handleEvent(CreateEvent(event.entry))
                }
            }
            is CreateEvent -> {
                val entry = event.entry
                // Add the entry
                synchronized(entries) { entries.add(entry) }
                println("[Patreon WS] NEW PATRON: ${event.entry}")
                application.shardManager.getUserById(141403816309293057L).openPrivateChannel().queue {
                    it.sendMessage("[Patreon WS] New Patreon: ${entry.discord_id}").queue()
                }
                val supportLevel = SupportLevel.toLevel(entry.reward_id, false)
                val stringBuilder = StringBuilder("Thank you for donating of **${Utils.formatMoney(entry.amount_cents / 100.0)}**! ")
                if (supportLevel.cost > 0) stringBuilder.append(" You now have access to all the features as a **${supportLevel.rewardName}**.")
                stringBuilder.append(" As a patron you __***must***__ pay monthly in order to keep all the premium features. If you have any questions, please feel free to" +
                        " contact me (*ThePrimedTNT#5190*) via discord or ask in our support server <https://astolfo.xyz/support>")
                entry.sendMessage(message(stringBuilder.toString()))
            }
            is UpdateEvent -> {
                val newEntry = event.entry
                val oldEntry = synchronized(entries) { entries.find { it.id == newEntry.id } ?: return }
                // Update entry
                synchronized(entries) {
                    entries.remove(oldEntry)
                    entries.add(newEntry)
                }
                val oldDiscordId = oldEntry.discord_id
                val newDiscordId = newEntry.discord_id
                if (oldDiscordId != newDiscordId) {
                    val oldUser = oldDiscordId?.let { application.shardManager.getUserById(it) }
                    val newUser = newDiscordId?.let { application.shardManager.getUserById(it) }

                    if (oldUser != null) {
                        val stringBuilderOld = StringBuilder("Your patreon donation is no longer linked to the discord account ${oldUser.name}#${oldUser.discriminator}.")
                        if (newUser != null) stringBuilderOld.append(" It is now linked to the discord account ${newUser.name}#${newUser.discriminator}.")
                        oldEntry.sendMessage(message(stringBuilderOld.toString()))
                    }

                    if (newUser != null) {
                        val stringBuilderNew = StringBuilder("Your patreon donation is now linked to the discord account ${newUser.name}#${newUser.discriminator}.")
                        if (oldUser != null) stringBuilderNew.append(" It is no longer linked to the discord account ${oldUser.name}#${oldUser.discriminator}.")
                        newEntry.sendMessage(message(stringBuilderNew.toString()))
                    }
                }
            }
            is DeleteEvent -> {
                val entry = synchronized(entries) { entries.find { it.id == event.entryId } } ?: return
                synchronized(entry) { entries.remove(entry) }
                entry.sendMessage(message("Awwww, sorry to see you leave being a patron!" +
                        " If you haven't already, please tell us why your no longer a patron in our support server: <https://astolfo.xyz/support>"))
                println("[Patreon WS] LOST PATRON: ${entry.discord_id}")
                application.shardManager.getUserById(141403816309293057L).openPrivateChannel().queue {
                    it.sendMessage("[Patreon WS] Lost Patreon: ${entry.discord_id}").queue()
                }
            }
            else -> error("Got invalid event: ${event::class.simpleName}")
        }
    }

    private fun PatreonEntry.sendMessage(message: Message) {
        if (discord_id == null) return
        if (!canSendAll && !canSendList.contains(discord_id)) return
        val user = application.shardManager.getUserById(discord_id)
        user?.openPrivateChannel()?.queue { privateChannel ->
            privateChannel.sendMessage(message).queue()
        }
    }

    private val canSendAll: Boolean
    private val canSendList: List<Long>

    init {
        if (properties.patreon_users.equals("all", true)) {
            canSendAll = true
            canSendList = listOf()
        } else {
            canSendAll = false
            canSendList = properties.patreon_users.split(",").mapNotNull { it.trim().toLongOrNull() }
        }
        connect()
    }

    private fun connect() {
        val wsRequest = Request.Builder()
                .url(properties.patreon_url)
                .header("Authorization", properties.patreon_auth)
                .build()

        ASTOLFO_HTTP_CLIENT.newWebSocket(wsRequest, object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                reconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                reconnect()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = ASTOLFO_GSON.fromJson<JsonObject>(text)
                val t = json["t"].asString!!
                val d = json["d"]!!

                //println("Patreon WS: $text")
                when (t) {
                    "READY" -> {
                        val entries = ASTOLFO_GSON.fromJson<List<PatreonEntry>>(d)
                        wsActor.sendBlocking(ReadyEvent(entries))
                    }
                    "CREATE", "UPDATE" -> {
                        val entry = ASTOLFO_GSON.fromJson<PatreonEntry>(d)
                        wsActor.sendBlocking(ConsumeEvent(entry))
                    }
                    "DELETE" -> {
                        val entryId = ASTOLFO_GSON.fromJson<Long>(d)
                        wsActor.sendBlocking(DeleteEvent(entryId))
                    }
                    else -> TODO("Unsupported trigger: $t")
                }
            }
        })
    }

    private fun reconnect() {
        Thread.sleep(TimeUnit.SECONDS.toMillis(30))
        connect()
    }

    fun getByMember(member: Member): SupportLevel {
        val guild = getByDiscordId(member.guild.owner.user.idLong)?.supportLevel ?: SupportLevel.DEFAULT
        val user = getByDiscordId(member.user.idLong)?.supportLevel ?: SupportLevel.DEFAULT
        return guild.max(user)
    }

    fun getByDiscordId(discordId: Long) = synchronized(entries) { entries.find { it.discord_id == discordId } }


}

enum class SupportLevel(val rewardId: Long?, val rewardName: String, val upvote: Boolean, val cost: Long, val queueSize: Long) {
    DEFAULT(null, "Default", false, 0, 200),
    UPVOTER(null, "Upvoter", true, 0, 400),
    SUPPORTER(2117513L, "Supporter", false, 5 * 100, 1000),
    ADVANCED_SUPPORTER(2117519L, "Advanced Supporter", false, 10 * 100, 2000),
    SERVANT(2117523L, "Servant", false, 20 * 100, 5000),
    MASTER(2117526L, "Astolfo's Master", false, 40 * 100, 10000);

    fun max(other: SupportLevel) = if (ordinal < other.ordinal) other else this

    companion object {
        fun toLevel(rewardId: Long?, hasUpvoted: Boolean): SupportLevel = when {
            rewardId != null -> values().first { it.rewardId == rewardId }
            hasUpvoted -> UPVOTER
            else -> DEFAULT
        }
    }
}