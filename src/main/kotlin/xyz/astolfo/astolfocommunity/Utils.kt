package xyz.astolfo.astolfocommunity

import com.github.salomonbrys.kotson.fromJson
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request

val ASTOLFO_GSON = Gson()
val ASTOLFO_HTTP_CLIENT = OkHttpClient()

inline fun <reified T : Any> webJson(url: String, accept: String? = "application/json"): T? = ASTOLFO_GSON.fromJson(web(url, accept))

fun web(url: String, accept: String? = null): String {
    val requestBuilder = Request.Builder().url(url)
    if (accept != null) requestBuilder.header("Accept", accept)
    return ASTOLFO_HTTP_CLIENT.newCall(requestBuilder.build()).execute().use { String(it.body()!!.bytes()) }
}
