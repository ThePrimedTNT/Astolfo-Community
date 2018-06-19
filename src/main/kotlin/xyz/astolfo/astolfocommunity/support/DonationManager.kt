package xyz.astolfo.astolfocommunity.support

import com.birbit.jsonapi.JsonApiDeserializer
import com.birbit.jsonapi.JsonApiRelationship
import com.birbit.jsonapi.JsonApiResourceDeserializer
import com.birbit.jsonapi.JsonApiResponse
import com.birbit.jsonapi.annotations.Relationship
import com.birbit.jsonapi.annotations.ResourceId
import com.github.salomonbrys.kotson.fromJson
import com.github.salomonbrys.kotson.gsonTypeToken
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.patreon.PatreonAPI
import com.patreon.PatreonOAuth
import com.patreon.resources.Pledge
import com.patreon.resources.RequestUtil
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.JDA
import okhttp3.Request
import org.springframework.data.repository.CrudRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import xyz.astolfo.astolfocommunity.*
import java.io.File
import java.io.InputStream
import java.util.*
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.persistence.Column
import javax.persistence.Entity
import javax.persistence.Id
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaField


@Controller
@RequestMapping(value = ["/patreon"])
class DonationManager(private val application: AstolfoCommunityApplication,
                      private val donationRepository: DonationRepository,
                      properties: AstolfoProperties) {

    private val gson: Gson = JsonApiDeserializer.register(GsonBuilder(),
            JsonApiResourceDeserializer("pledge", PatronPledge::class.java),
            JsonApiResourceDeserializer("user", PatronUser::class.java),
            JsonApiResourceDeserializer("campaign", PatronCampaign::class.java),
            JsonApiResourceDeserializer("reward", PatronReward::class.java)
    ).create()
    private val mac: Mac

    init {
        val algorithm = "HmacMD5"
        mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(properties.patreon_webhook_secret.toByteArray(), algorithm))
    }

    fun generateSignature(data: String): String {
        val signature = synchronized(mac) {
            mac.doFinal(data.toByteArray())
        }
        // convert to hex
        val formatter = Formatter()
        for (b in signature) formatter.format("%02x", b)
        return formatter.toString()
    }

    @RequestMapping(method = [RequestMethod.POST], value = ["/webhook"])
    fun discordBotList(@RequestHeader("X-Patreon-Event") event: String,
                       @RequestHeader("X-Patreon-Signature") signature: String,
                       @RequestBody body: String): ResponseEntity<Any> {
        if (signature != generateSignature(body)) return ResponseEntity(HttpStatus.UNAUTHORIZED)
        println(body)
        when (event) {
            "pledges:create" -> {
                val response = gson.fromJson<JsonApiResponse<PatronPledge>>(body, gsonTypeToken<JsonApiResponse<PatronPledge>>())
                val pledgeId = response.data.id.toLong()
                if (!donationRepository.existsById(pledgeId)) {
                    newPatron(DonationEntry.fromResponse(response), response.data.amount_cents)
                } else {
                    println("Duplicate pledge: $pledgeId")
                }
            }
            "pledges:update" -> {
                val response = gson.fromJson<JsonApiResponse<PatronPledge>>(body, gsonTypeToken<JsonApiResponse<PatronPledge>>())
                val pledgeId = response.data.id.toLong()
                val entry = DonationEntry.fromResponse(response)
                if (!donationRepository.existsById(pledgeId)) {
                    // Existing patron but not in database yet
                    newPatron(entry, response.data.amount_cents)
                } else {
                    // Something changed. Ex: amount, tier, discord name
                    // TODO add special messages
                    donationRepository.save(entry)
                }
            }
            "pledges:delete" -> {
                // Awww lost patron
                val response = gson.fromJson<JsonApiResponse<PatronPledge>>(body, gsonTypeToken<JsonApiResponse<PatronPledge>>())
                val discord_id = response.getIncluded<PatronUser>(response.data.patron)!!
                        .social_connections.discord?.user_id
                if (discord_id != null) {
                    val user = application.shardManager.getUserById(discord_id)
                    user?.openPrivateChannel()?.queue { privateChannel ->
                        privateChannel.sendMessage(message("Awwww, sorry to see you leave being a patron!" +
                                " If you haven't already, please tell us why your no longer a patron in our support server: <https://astolfo.xyz/support>")).queue()
                    }
                }
                donationRepository.deleteById(response.data.id.toLong())
            }
            else -> return ResponseEntity(HttpStatus.BAD_REQUEST)
        }
        return ResponseEntity(HttpStatus.OK)
    }

    fun update(donationEntry: DonationEntry) {
        val oldEntry = donationRepository.findById(donationEntry.pledgeId).orElse(null) ?: return
        val oldDiscordId = oldEntry.discordId
        val newDiscordId = donationEntry.discordId
        if (oldDiscordId != newDiscordId) {
            val oldUser = oldDiscordId?.let { application.shardManager.getUserById(it) }
            val newUser = newDiscordId?.let { application.shardManager.getUserById(it) }

            oldUser?.openPrivateChannel()?.queue { privateChannel ->
                val stringBuilder = StringBuilder("Your patreon donation is no longer linked to the discord account ${oldUser.name}#${oldUser.discriminator}.")
                if (newUser != null) stringBuilder.append(" It is now linked to the discord account ${newUser.name}#${newUser.discriminator}.")
                privateChannel.sendMessage(stringBuilder.toString()).queue()
            }
            newUser?.openPrivateChannel()?.queue { privateChannel ->
                val stringBuilder = StringBuilder("Your patreon donation is now linked to the discord account ${newUser.name}#${newUser.discriminator}.")
                if (oldUser != null) stringBuilder.append(" It is no longer linked to the discord account ${oldUser.name}#${oldUser.discriminator}.")
                privateChannel.sendMessage(stringBuilder.toString()).queue()
            }
        }
        donationRepository.save(donationEntry)
    }

    fun newPatron(donationEntry: DonationEntry, amountDonated: Long) {
        val discordId = donationEntry.discordId
        println("NEW PATRON: $discordId")
        if (discordId != null) {
            val user = application.shardManager.getUserById(discordId)
            user?.openPrivateChannel()?.queue { privateChannel ->
                val supportLevel = SupportLevel.toLevel(donationEntry.rewardName, false)
                val stringBuilder = StringBuilder("Thank you for donating of **${Utils.formatMoney(amountDonated / 100.0)}**! ")
                if (supportLevel.cost > 0) stringBuilder.append(" You now have access to all the features as a **${supportLevel.rewardName}**.")
                stringBuilder.append(" As a patron you __***must***__ pay monthly in order to keep all the premium features. If you have any questions, please feel free to" +
                        " contact me (*ThePrimedTNT#5190*) via discord or ask in our support server <https://astolfo.xyz/support>")
                privateChannel.sendMessage(message(stringBuilder.toString())).queue()
            }
        }
        donationRepository.save(donationEntry)
    }
}

@Component
class AstolfoPatreonWrapper(private val application: AstolfoCommunityApplication,
                            private val properties: AstolfoProperties,
                            private val donationRepository: DonationRepository,
                            private val donationManager: DonationManager) {

    private val gson = Gson()
    private val api = PatreonAPI(null)
    private val tokenDataFile = File("patreonRefreshToken.json")

    private var token: PatreonOAuth.TokensResponse? = null
    private var tokenSince = 0L

    init {
        val requestUtilReflect = api::class.memberProperties.first { it.name.equals("requestUtil", true) }
        requestUtilReflect.isAccessible = true
        requestUtilReflect.javaField!!.set(api, object : RequestUtil() {
            override fun request(pathSuffix: String?, accessToken: String?): InputStream {
                synchronized(tokenDataFile) {
                    if (token == null || System.currentTimeMillis() - tokenSince > token!!.expiresIn * 1000) {
                        token = refreshToken()
                        tokenSince = System.currentTimeMillis()
                    }
                    return super.request(pathSuffix, token!!.accessToken)
                }
            }
        })
        requestUtilReflect.isAccessible = false

        launch {
            while (!application.shardManager.shards.all { it.status == JDA.Status.CONNECTED }) delay(5, TimeUnit.SECONDS)
            fetchMissedPledges()
            while (isActive) {
                delay(10, TimeUnit.MINUTES)
                updateData()
            }
        }
    }

    private fun refreshToken(): PatreonOAuth.TokensResponse {
        val tokenData = if (tokenDataFile.exists()) gson.fromJson(tokenDataFile.readText())
        else PatreonTokenData(properties.patreon_creators_refresh_token)

        val request = Request.Builder().url("${PatreonAPI.BASE_URI}/api/oauth2/token" +
                "?grant_type=refresh_token" +
                "&client_id=${properties.patreon_client_id}" +
                "&client_secret=${properties.patreon_client_secret}" +
                "&refresh_token=${tokenData.creatorsRefreshToken}").post(okhttp3.RequestBody.create(null, byteArrayOf())).build()

        val response = ASTOLFO_HTTP_CLIENT.newCall(request).execute()
        val string = response.body()!!.string()
        val responseToken: PatreonOAuth.TokensResponse = gson.fromJson(string)

        tokenDataFile.writeText(gson.toJson(PatreonTokenData(responseToken.refreshToken)))

        return responseToken
    }

    private data class PatreonTokenData(val creatorsRefreshToken: String)

    private fun getPledges(): List<Pledge> {
        val campaignIds = api.fetchCampaigns().get().map { it.id }
        val pledges = campaignIds.map { api.fetchAllPledges(it) }.flatten()
        return pledges
    }

    private fun fetchMissedPledges() {
        getPledges().forEach { pledge ->
            if (!application.staffMemberIds.contains(pledge.patron.socialConnections.discord?.user_id?.toLong())) return@forEach
            if (!donationRepository.existsById(pledge.id.toLong())) {
                // Missed it somehow
                donationManager.newPatron(DonationEntry.fromPledge(pledge), pledge.amountCents.toLong())
            }
        }
    }

    private fun updateData() {
        // update pledge data
        getPledges().forEach { pledge -> donationManager.update(DonationEntry.fromPledge(pledge)) }
    }

}

interface DonationRepository : CrudRepository<DonationEntry, Long> {
    fun findByDiscordId(discordId: Long): DonationEntry?
}

@Entity
data class DonationEntry(@Id val pledgeId: Long = 0L,
                         @Column(nullable = true)
                         val discordId: Long? = 0L,
                         val rewardName: String = "") {
    val supportLevel
        get() = SupportLevel.toLevel(rewardName, false)

    companion object {
        fun fromResponse(response: JsonApiResponse<PatronPledge>): DonationEntry {
            val pledgeId = response.data.id.toLong()
            val reward = response.getIncluded<PatronReward>(response.data.reward)!!
            val rewardName = reward.title
            val discord_id = response.getIncluded<PatronUser>(response.data.patron)!!
                    .social_connections.discord?.user_id?.toLong()
            return DonationEntry(pledgeId, discord_id, rewardName)
        }

        fun fromPledge(pledge: Pledge)= DonationEntry(pledge.id.toLong(),
                    pledge.patron.socialConnections.discord?.user_id?.toLong(),
                    pledge.reward.title)
    }
}

enum class SupportLevel(val rewardName: String, val upvote: Boolean, val cost: Long, val queueSize: Long) {
    DEFAULT("Default", false, 0, 200),
    UPVOTER("Upvoter", true, 0, 500),
    SUPPORTER("Supporter", false, 5 * 100, 1000),
    ADVANCED_SUPPORTER("Advanced Supporter", false, 10 * 100, 2000),
    SERVANT("Servant", false, 20 * 100, 5000),
    MASTER("Astolfo's Master", false, 40 * 100, 10000);

    companion object {
        fun toLevel(rewardName: String?, hasUpvoted: Boolean): SupportLevel = when {
            rewardName != null -> values().first { it.rewardName.equals(rewardName, true) }
            hasUpvoted -> UPVOTER
            else -> DEFAULT
        }
    }
}

inline fun <reified T> JsonApiResponse<*>.getIncluded(relationship: JsonApiRelationship) = getIncluded(T::class.java, relationship.data.id)

class PatronPledge(@ResourceId val id: String,
                   val amount_cents: Long,
                   @Relationship("patron") val patron: JsonApiRelationship,
                   @Relationship("reward") val reward: JsonApiRelationship)

class PatronUser(@ResourceId val id: String,
                 val social_connections: PatronSocialConnections,
                 @Relationship("campaign") val campaign: JsonApiRelationship)

class PatronReward(@ResourceId val id: String,
                   val title: String,
                   @Relationship("campaign") val campaign: JsonApiRelationship)

class PatronCampaign(@ResourceId val id: String,
                     @Relationship("creator") val creator: JsonApiRelationship)

class PatronSocialConnections(val discord: PatronDiscord?)

class PatronDiscord(val scopes: List<String>,
                    val user_id: String)