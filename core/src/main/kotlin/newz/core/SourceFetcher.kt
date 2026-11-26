package newz.core

import org.jsoup.Jsoup

sealed class FetchOutcome {
    data class Success(val items: List<RawItem>, val etag: String?, val lastModified: String?) : FetchOutcome()
    object NotModified : FetchOutcome()
    data class Failed(val message: String) : FetchOutcome()
}

/** Fetches one source and runs its recipe. */
object SourceFetcher {
    fun requestHeaders(source: SourceConfig, etag: String? = null, lastModified: String? = null): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        headers["User-Agent"] = source.userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
        headers["Accept"] = when (source.recipe.kind) {
            FetchKind.FEED -> "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.5"
            FetchKind.SELECTORS -> "text/html, application/xhtml+xml;q=0.9, */*;q=0.5"
        }
        if (etag != null) headers["If-None-Match"] = etag
        if (lastModified != null) headers["If-Modified-Since"] = lastModified
        headers.putAll(source.headers)
        return headers
    }

    fun fetch(
        source: SourceConfig,
        http: HttpFetcher,
        etag: String? = null,
        lastModified: String? = null,
        now: Long = System.currentTimeMillis(),
    ): FetchOutcome {
        val response = try {
            http.get(source.recipe.url, requestHeaders(source, etag, lastModified))
        } catch (e: Exception) {
            return FetchOutcome.Failed("Network error: ${e.message ?: e.javaClass.simpleName}")
        }
        if (response.code == 304) return FetchOutcome.NotModified
        if (response.code !in 200..299) return FetchOutcome.Failed("HTTP ${response.code}")

        return try {
            val items = when (source.recipe.kind) {
                FetchKind.FEED -> FeedParser.parse(response.body, response.finalUrl, now).items
                FetchKind.SELECTORS ->
                    RecipeExtractor.extract(Jsoup.parse(response.body, response.finalUrl), source.recipe, now)
            }
            FetchOutcome.Success(items, response.etag, response.lastModified)
        } catch (e: RecipeException) {
            FetchOutcome.Failed(e.message ?: "Recipe error")
        } catch (e: Exception) {
            FetchOutcome.Failed("Could not read the page: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
