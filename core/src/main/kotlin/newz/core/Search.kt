package newz.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

/**
 * How to call a web search API. Search providers differ in address, method
 * and how the key is sent, so each part is a setting; the presets are
 * starting points to check against the provider's own documentation.
 */
@Serializable
data class SearchSettings(
    val endpoint: String = "https://kagi.com/api/v1/search",
    /** GET or POST. */
    val method: String = "POST",
    /** The header the key is sent in. */
    val authHeader: String = "Authorization",
    /** Text placed before the key in that header, such as "Bearer " or "Bot ". */
    val authPrefix: String = "Bearer ",
    /**
     * For POST, the JSON body. For GET, the query string. {query} and {limit}
     * are replaced; the query is escaped to suit whichever it is.
     */
    val request: String = "{\"query\": \"{query}\"}",
    val resultsPerSearch: Int = 10,
    /** Stop after this many searches in a day. 0 means no limit. */
    val dailyLimit: Int = 0,
) {
    val enabled: Boolean get() = endpoint.isNotBlank()

    companion object {
        val PRESETS: List<Pair<String, SearchSettings>> = listOf(
            "Kagi" to SearchSettings(),
            "Kagi (older v0 format)" to SearchSettings(
                endpoint = "https://kagi.com/api/v0/search", method = "GET", authPrefix = "Bot ",
                request = "q={query}&limit={limit}",
            ),
            "Brave Search" to SearchSettings(
                endpoint = "https://api.search.brave.com/res/v1/web/search", method = "GET",
                authHeader = "X-Subscription-Token", authPrefix = "", request = "q={query}&count={limit}",
            ),
            "Tavily" to SearchSettings(
                endpoint = "https://api.tavily.com/search", method = "POST",
                request = "{\"query\": \"{query}\", \"max_results\": {limit}, \"topic\": \"news\"}",
            ),
        )
    }
}

data class SearchResult(val url: String, val title: String, val snippet: String? = null, val publishedAt: Long? = null)

/** What one search returned: the parsed results, the provider's raw reply, and any error. */
data class SearchOutcome(val results: List<SearchResult>, val raw: String, val error: String?)

class SearchClient(
    private val fetcher: HttpFetcher,
    private val poster: HttpPoster,
    private val settings: SearchSettings,
    private val apiKey: String,
) {
    fun search(query: String, now: Long = System.currentTimeMillis()): SearchOutcome {
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        if (apiKey.isNotBlank() && settings.authHeader.isNotBlank()) headers[settings.authHeader.trim()] = settings.authPrefix + apiKey.trim()
        val limit = settings.resultsPerSearch.coerceIn(1, 50).toString()
        val endpoint = settings.endpoint.trim()

        val response = try {
            if (settings.method.trim().equals("GET", ignoreCase = true)) {
                val queryString = settings.request
                    .replace("{query}", URLEncoder.encode(query, "UTF-8"))
                    .replace("{limit}", limit)
                fetcher.get(endpoint + (if ('?' in endpoint) "&" else "?") + queryString, headers)
            } else {
                // The query goes inside a JSON string, so it is escaped as one.
                val escaped = JsonPrimitive(query).toString().let { it.substring(1, it.length - 1) }
                poster.postJson(endpoint, headers, settings.request.replace("{query}", escaped).replace("{limit}", limit))
            }
        } catch (e: Exception) {
            return SearchOutcome(emptyList(), "", "Could not reach the search provider: ${e.message ?: e.javaClass.simpleName}")
        }
        if (response.code !in 200..299) {
            return SearchOutcome(emptyList(), response.body, "HTTP ${response.code}: ${response.body.take(200)}")
        }
        val results = try {
            SearchResults.parse(response.body, now)
        } catch (e: Exception) {
            return SearchOutcome(emptyList(), response.body, "The reply was not JSON")
        }
        return SearchOutcome(results, response.body, null)
    }
}

/**
 * Reads results out of a search provider's JSON without knowing its exact
 * layout: it takes the list in the reply holding the most objects that have
 * both a web address and a title.
 */
object SearchResults {
    private val urlKeys = listOf("url", "link", "href")
    private val titleKeys = listOf("title", "name", "t")
    private val snippetKeys = listOf("snippet", "description", "content", "text", "summary")
    private val dateKeys = listOf("published", "published_date", "publishedDate", "date", "page_age", "age", "time")

    fun parse(json: String, now: Long = System.currentTimeMillis()): List<SearchResult> {
        val root = Json.parseToJsonElement(json)
        require(root is JsonObject || root is JsonArray) { "not a JSON object or list" }
        var best: List<SearchResult> = emptyList()
        fun visit(element: JsonElement) {
            when (element) {
                is JsonArray -> {
                    val found = element.mapNotNull { (it as? JsonObject)?.let { o -> result(o, now) } }
                    if (found.size > best.size) best = found
                    element.forEach { visit(it) }
                }
                is JsonObject -> element.values.forEach { visit(it) }
                else -> Unit
            }
        }
        visit(root)
        return best.distinctBy { Urls.canonical(it.url) }
    }

    private fun JsonObject.text(keys: List<String>): String? {
        for (k in keys) {
            val value = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return null
    }

    private fun result(o: JsonObject, now: Long): SearchResult? {
        val url = o.text(urlKeys)?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return null
        val title = o.text(titleKeys) ?: return null
        val plain = org.jsoup.Jsoup.parseBodyFragment(title).text().trim()
        if (plain.isEmpty()) return null
        return SearchResult(
            url = url,
            title = plain,
            snippet = o.text(snippetKeys)?.let { org.jsoup.Jsoup.parseBodyFragment(it).text().trim().take(500) }?.ifEmpty { null },
            publishedAt = Dates.parse(o.text(dateKeys), now),
        )
    }
}
