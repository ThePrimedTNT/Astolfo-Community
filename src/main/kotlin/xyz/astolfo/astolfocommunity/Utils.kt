package xyz.astolfo.astolfocommunity

import com.github.salomonbrys.kotson.fromJson
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.dv8tion.jda.core.requests.RequestFuture
import net.dv8tion.jda.core.requests.RestAction
import net.dv8tion.jda.core.requests.RestFuture
import net.dv8tion.jda.core.utils.Checks
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.NumberFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

val ASTOLFO_GSON = Gson()
val ASTOLFO_HTTP_CLIENT = OkHttpClient()

suspend inline fun <reified T : Any> webJson(url: String, accept: String? = "application/json"): T? {
    val json = web(url, accept)
    try {
        return ASTOLFO_GSON.fromJson(json)
    } catch (e: JsonSyntaxException) {
        println(json)
        throw e
    }
}

suspend inline fun web(url: String, accept: String? = null): String {
    val requestBuilder = Request.Builder().url(url)
    if (accept != null) requestBuilder.header("Accept", accept)
    return async { ASTOLFO_HTTP_CLIENT.newCall(requestBuilder.build()).execute() }.await().use { String(it.body()!!.bytes()) }
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

    /**
     * Backing map of which the rate-limiter keeps track of the keys.
     */
    private val map = ConcurrentHashMap<K, MutableList<Long>>()

    fun add(key: K) {
        synchronized(map) {
            val data = map.computeIfAbsent(key, { mutableListOf() })
            val timestamp = System.currentTimeMillis()
            data.add(timestamp)
            launch {
                delay(timeout, TimeUnit.SECONDS)
                synchronized(map) {
                    data.remove(timestamp)
                    if (data.isEmpty()) map.remove(key)
                }
            }
        }
    }

    fun remainingTime(key: K) = map[key]?.takeLast(threshold)?.firstOrNull()?.let { (timeout * 1000) - (System.currentTimeMillis() - it) }

    fun isLimited(key: K) = (map[key]?.count() ?: 0) >= threshold

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
