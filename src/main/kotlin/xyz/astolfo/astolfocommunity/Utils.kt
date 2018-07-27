package xyz.astolfo.astolfocommunity

import com.github.salomonbrys.kotson.fromJson
import com.google.gson.Gson
import kotlinx.coroutines.experimental.*
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import net.dv8tion.jda.bot.sharding.ShardManager
import net.dv8tion.jda.core.Permission
import net.dv8tion.jda.core.entities.Guild
import net.dv8tion.jda.core.entities.TextChannel
import okhttp3.*
import java.io.IOException
import java.text.NumberFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

val ASTOLFO_GSON = Gson()
val ASTOLFO_HTTP_CLIENT = OkHttpClient()

inline fun <reified T : Any> webJson(
        url: String,
        accept: String? = "application/json"
) = web(url, accept).wrap {
    ASTOLFO_GSON.fromJson<T>(it)
}

fun web(url: String, accept: String? = null): CompletableDeferred<String> {
    val requestBuilder = Request.Builder().url(url)
    if (accept != null) requestBuilder.header("Accept", accept)
    val call = ASTOLFO_HTTP_CLIENT.newCall(requestBuilder.build())
    return call.enqueueDeferred()
}

fun Call.enqueueDeferred(): CompletableDeferred<String> {
    val completableDeferred = CompletableDeferred<String>()
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            response.body()!!.use { body ->
                completableDeferred.complete(body.string())
            }
        }

        override fun onFailure(call: Call, e: IOException) {
            completableDeferred.completeExceptionally(e)
        }
    })
    completableDeferred.invokeOnCompletion { cancel() }
    return completableDeferred
}

fun <T, K> CompletableDeferred<T>.wrap(wrapper: (T) -> K): CompletableDeferred<K> {
    val resultCompletable = CompletableDeferred<K>()
    this.invokeOnCompletion { error ->
        try {
            if (error != null) resultCompletable.completeExceptionally(error)
            else {
                val value = this@wrap.getCompleted()
                val result = wrapper(value)
                resultCompletable.complete(result)
            }
        } catch (t: Throwable) {
            resultCompletable.completeExceptionally(t)
        }
    }
    resultCompletable.invokeOnCompletion { this@wrap.cancel(it) }
    return resultCompletable
}

fun ShardManager.getEffectiveName(userId: Long) = getEffectiveName(null, userId)
fun ShardManager.getEffectiveName(guild: Guild?, userId: Long): String? {
    val member = guild?.getMemberById(userId)
    return if (member != null) member.effectiveName
    else getUserById(userId)?.name
}

class RateLimiter<K>(
        /**
         * The number of checks allowed before rate-limited status.
         */
        val threshold: Int,
        /**
         * How long the key will be timed out for.
         */
        val timeout: Long) {

    companion object {
        private val rateLimitContext = newFixedThreadPoolContext(10, "Rate Limiter Context")
    }

    /**
     * Backing map of which the rate-limiter keeps track of the keys.
     */
    private val map = ConcurrentHashMap<K, MutableList<Long>>()
    private val mapMutex = Mutex()

    suspend fun add(key: K) {
        mapMutex.withLock {
            val data = map.computeIfAbsent(key) { mutableListOf() }
            val timestamp = System.currentTimeMillis()
            data.add(timestamp)
            launch(rateLimitContext) {
                delay(timeout, TimeUnit.SECONDS)
                mapMutex.withLock {
                    data.remove(timestamp)
                    if (data.isEmpty()) map.remove(key)
                }
            }
        }
    }

    suspend fun remainingTime(key: K): Long? {
        mapMutex.withLock {
            return map[key]?.takeLast(threshold)?.firstOrNull()
                    ?.let { (timeout * 1000) - (System.currentTimeMillis() - it) }
        }
    }

    suspend fun isLimited(key: K): Boolean {
        mapMutex.withLock {
            return (map[key]?.count() ?: return false) >= threshold
        }
    }

}

object Utils {

    private val TIMESTAMP_PATTERN = Regex("^(?:(?:(?<hours>\\d+):)?(?:(?<minutes>\\d+):))?(?<seconds>\\d+)$")
    private val WORD_PATTERN = Regex("^((?:(?<hours>\\d+)(?:h))|(?:(?<minutes>\\d+)(?:m))|(?:(?<seconds>\\d+)(?:s)))+$", RegexOption.IGNORE_CASE)

    fun parseTimeString(input: String): Long? {
        val inputNoSpaces = input.replace(Regex("\\s+"), "")
        return parse(TIMESTAMP_PATTERN, inputNoSpaces) ?: parse(WORD_PATTERN, inputNoSpaces)
    }

    private fun parse(pattern: Regex, input: String): Long? {
        val groupCollection = pattern.matchEntire(input)?.groups ?: return null
        return TimeUnit.HOURS.toMillis(groupCollection["hours"]?.value?.toLongOrNull() ?: 0) +
                TimeUnit.MINUTES.toMillis(groupCollection["minutes"]?.value?.toLongOrNull() ?: 0) +
                TimeUnit.SECONDS.toMillis(groupCollection["seconds"]?.value?.toLongOrNull() ?: 0)
    }

    fun formatSongDuration(duration: Long, isStream: Boolean = false): String {
        if (isStream) return "LIVE"

        val hours = TimeUnit.MILLISECONDS.toHours(duration)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(duration) % TimeUnit.HOURS.toMinutes(1)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) % TimeUnit.MINUTES.toSeconds(1)

        val list = hours.takeIf { it > 0 }?.let { arrayOf(hours, minutes, seconds) }
                ?: arrayOf(minutes, seconds)

        return list.mapIndexed { index, time ->
            if (index == 0) String.format("%d", time)
            else String.format("%02d", time)
        }.joinToString(separator = ":")
    }

    fun formatDuration(timeLeft: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(timeLeft)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(timeLeft) % TimeUnit.HOURS.toMinutes(1)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(timeLeft) % TimeUnit.MINUTES.toSeconds(1)

        var timeStr = ""
        if (hours > 0)
            timeStr += " " + hours + "h"
        if (minutes > 0)
            timeStr += " " + minutes + "m"
        if (seconds > 0)
            timeStr += " " + seconds + "s"
        timeStr = timeStr.trim { it <= ' ' }
        return timeStr
    }

    val currencyFormatter = NumberFormat.getCurrencyInstance();

    fun formatMoney(number: Double) = currencyFormatter.format(number)!!

}

private val WORD_SPLIT_REGEX = Regex("\\s+")

fun String.words(): List<String> {
    if (isEmpty()) return emptyList()
    if (!contains(WORD_SPLIT_REGEX)) listOf(this)
    return split(WORD_SPLIT_REGEX)
}

fun String.splitFirst(delimiter: String) =
        if (contains(delimiter)) substringBefore(delimiter).trim() to substringAfter(delimiter).trim()
        else this to ""

fun String.splitLast(delimiter: String) =
        if (contains(delimiter)) substringBeforeLast(delimiter).trim() to substringAfterLast(delimiter).trim()
        else this to ""

fun String.levenshteinDistance(t: String, ignoreCase: Boolean = false): Int {
    // degenerate cases
    if (this.equals(t, ignoreCase)) return 0
    if (this.isEmpty()) return t.length
    if (t.isEmpty()) return this.length

    // create two work vectors of integer distances
    val v0 = IntArray(t.length + 1)
    val v1 = IntArray(t.length + 1)

    // initialize v0 (the previous row of distances)
    // this row is A[0][i]: edit distance for an empty s
    // the distance is just the number of characters to delete from t
    for (i in v0.indices)
        v0[i] = i

    for (i in 0 until this.length) {
        // calculate v1 (current row distances) from the previous row v0

        // first element of v1 is A[i+1][0]
        // edit distance is delete (i+1) chars from s to match empty t
        v1[0] = i + 1

        // use formula to fill in the rest of the row
        for (j in 0 until t.length) {
            val cost = if (this[i].equals(t[j], ignoreCase)) 0 else 1
            v1[j + 1] = minOf(v1[j] + 1, v0[j + 1] + 1, v0[j] + cost)
        }

        // copy v1 (current row) to v0 (previous row) for next iteration
        System.arraycopy(v1, 0, v0, 0, v0.size)
    }

    return v1[t.length]
}

val <T> Deferred<T>.value: T?
    get() = if (isCompleted && !isCompletedExceptionally) getCompleted() else null

inline fun <T> synchronized2(lock1: Any, lock2: Any, block: () -> T): T =
        synchronized(lock1) {
            synchronized(lock2) {
                block()
            }
        }

fun TextChannel.hasPermission(vararg permissions: Permission) = guild.selfMember.hasPermission(this, *permissions)


