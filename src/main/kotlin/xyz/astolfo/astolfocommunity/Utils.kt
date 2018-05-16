package xyz.astolfo.astolfocommunity

import com.github.salomonbrys.kotson.fromJson
import com.google.gson.Gson
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

val ASTOLFO_GSON = Gson()
val ASTOLFO_HTTP_CLIENT = OkHttpClient()

inline fun <reified T : Any> webJson(url: String, accept: String? = "application/json"): T? = ASTOLFO_GSON.fromJson(web(url, accept))

fun web(url: String, accept: String? = null): String {
    val requestBuilder = Request.Builder().url(url)
    if (accept != null) requestBuilder.header("Accept", accept)
    return ASTOLFO_HTTP_CLIENT.newCall(requestBuilder.build()).execute().use { String(it.body()!!.bytes()) }
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