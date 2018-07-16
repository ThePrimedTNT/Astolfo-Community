package xyz.astolfo.astolfocommunity

import com.github.salomonbrys.kotson.fromJson
import com.google.common.cache.CacheBuilder
import kotlinx.coroutines.experimental.CompletableDeferred
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newFixedThreadPoolContext
import org.jsoup.parser.Parser
import xyz.astolfo.astolfocommunity.commands.CommandExecution
import java.util.*
import java.util.concurrent.TimeUnit


object ResourceManager {

    private val resourceContext = newFixedThreadPoolContext(50, "Resource Manager")

    private val webCache = CacheBuilder.newBuilder()
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build<String, String>()

    private val random = Random()

    suspend fun CommandExecution.getImage(tag: String, explicit: Boolean): ResolvedImageObject? {
        val completableDeferred = CompletableDeferred<ResolvedImageObject>()
        val mainJob = Job()
        ResourceType.values().forEach { type ->
            val job = launch(parent = mainJob, context = resourceContext) {
                val result = getImage(tag, explicit, type)
                if (result != null) completableDeferred.complete(result)
            }
            val handle = completableDeferred.invokeOnCompletion { job.cancel() }
            job.invokeOnCompletion(onCancelling = false, handler = { handle.dispose() })
        }
        mainJob.invokeOnCompletion { if (completableDeferred.isActive) completableDeferred.cancel() }
        destroyListener { completableDeferred.cancel() }
        return completableDeferred.await()
    }

    suspend fun CommandExecution.getImage(tag: String, explicit: Boolean, type: ResourceType): ResolvedImageObject? {
        val processedTag = tag.toLowerCase().let {
            if (type == ResourceType.E621) it.replace("yuri", "female/female", ignoreCase = true)
            else it
        }
        return downloadImages(processedTag, explicit, type).takeIf { it.isNotEmpty() }?.let { it.getOrNull(random.nextInt(it.size)) }
    }

    private suspend fun downloadImages(tag: String, explicit: Boolean, type: ResourceType): List<ResolvedImageObject> {
        val processedTag = tag.replace(' ', '_').toLowerCase().let {
            if (explicit) "rating%3Aexplicit+$it" else it
        }
        val website = when (type) {
            ResourceType.Safebooru -> "https://safebooru.org/index.php?page=dapi&s=post&q=index&limit=1000&tags=$processedTag"
            ResourceType.E621 -> "https://e621.net/post/index.json?limit=1000&tags=$processedTag"
            ResourceType.Danbooru -> "http://danbooru.donmai.us/posts.json?limit=100&tags=$processedTag"
            ResourceType.Gelbooru -> "http://gelbooru.com/index.php?page=dapi&s=post&q=index&limit=100&tags=$processedTag"
            ResourceType.Rule34 -> "https://rule34.xxx/index.php?page=dapi&s=post&q=index&limit=100&tags=$processedTag"
            ResourceType.Konachan -> "https://konachan.com/post.json?s=post&q=index&limit=100&tags=$processedTag"
            ResourceType.Yandere -> "https://yande.re/post.json?limit=100&tags=$processedTag"
        }
        return cachedWeb(website).let { data ->
            if (type.json) ASTOLFO_GSON.fromJson(data)
            else Parser.xmlParser().parseInput(data, website)
                    .getElementsByTag("posts").map { posts ->
                        posts.getElementsByTag("post").map { post ->
                            ImageObject(post.attr("file_url"),
                                    post.attr("tags"),
                                    post.attr("rating"))
                        }
                    }.flatten()
        }.filter {
            if (!explicit)
                it.rating == "s"
            else true
        }.map { ResolvedImageObject(it, type) }
    }

    private suspend fun cachedWeb(url: String): String {
        val cachedVersion = webCache.getIfPresent(url)
        if (cachedVersion != null) return cachedVersion
        val data = web(url).await()
        webCache.put(url, data)
        return data
    }


    data class ImageObject(val file_url: String, val tags: String, val rating: String)

    class ResolvedImageObject(val imageObject: ImageObject, val type: ResourceType) {
        val fileUrl = if (type == ResourceType.Danbooru)
            "https://danbooru.donmai.us${imageObject.file_url}"
        else
            imageObject.file_url.let { url -> if (url.startsWith("http")) url else "https:$url" }
    }

    enum class ResourceType(val json: Boolean = false) {
        Safebooru,
        E621(true),
        Danbooru(true),
        Gelbooru,
        Rule34,
        Konachan(true),
        Yandere(true)
    }

}