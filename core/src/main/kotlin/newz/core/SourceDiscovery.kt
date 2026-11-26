package newz.core

import org.jsoup.Jsoup

/** What adding a URL found: a ready source, how it was found, and a preview. */
data class Discovery(
    val source: SourceConfig,
    val method: String,
    val preview: List<RawItem>,
    /** Other page layouts the heuristic considered, best first after the chosen one. */
    val alternatives: List<RecipeCandidate> = emptyList(),
)

class DiscoveryException(message: String) : Exception(message)

/**
 * Works out how to follow a site from its URL, cheapest method first:
 * the URL is itself a feed, the page advertises a feed, a feed sits at a
 * common path, and finally the page's article list is detected by layout.
 */
object SourceDiscovery {
    private val commonFeedPaths = listOf("/feed", "/rss", "/rss.xml", "/feed.xml", "/atom.xml", "/index.xml", "/feeds/all.rss")

    fun discover(inputUrl: String, http: HttpFetcher, now: Long = System.currentTimeMillis()): Discovery {
        val url = inputUrl.trim().let { if ("://" in it) it else "https://$it" }
        val headers = mapOf("User-Agent" to DEFAULT_USER_AGENT)
        val page = try {
            http.get(url, headers)
        } catch (e: Exception) {
            throw DiscoveryException("Could not reach $url: ${e.message ?: e.javaClass.simpleName}")
        }
        if (page.code !in 200..299) throw DiscoveryException("$url answered HTTP ${page.code}")
        val pageUrl = page.finalUrl

        if (FeedParser.looksLikeFeed(page.body)) {
            val feed = FeedParser.parse(page.body, pageUrl, now)
            if (feed.items.isNotEmpty()) {
                return feedDiscovery(feed, pageUrl, Urls.origin(pageUrl) ?: pageUrl, null, "The address is a feed")
            }
        }

        val doc = Jsoup.parse(page.body, pageUrl)
        val siteName = doc.selectFirst("meta[property=og:site_name]")?.attr("content")?.trim()?.ifEmpty { null }
            ?: doc.title().trim().ifEmpty { null }
            ?: Urls.host(pageUrl)
            ?: pageUrl

        val advertised = doc.select("link[rel~=(?i)alternate][href]")
            .filter { it.attr("type").lowercase().let { t -> "rss" in t || "atom" in t } }
            .mapNotNull { Urls.resolve(pageUrl, it.attr("href")) }
        for (feedUrl in advertised.distinct().take(3)) {
            tryFeed(feedUrl, http, headers, now)?.let {
                return feedDiscovery(it.second, it.first, pageUrl, siteName, "The page links to a feed")
            }
        }

        val origin = Urls.origin(pageUrl)
        if (origin != null) {
            for (path in commonFeedPaths) {
                tryFeed(origin + path, http, headers, now)?.let {
                    return feedDiscovery(it.second, it.first, pageUrl, siteName, "Found a feed at $path")
                }
            }
        }

        val candidates = HeuristicDetector.detect(page.body, pageUrl, now)
        val best = candidates.firstOrNull()
            ?: throw DiscoveryException(
                "No feed and no recognisable article list on this page. " +
                    "It may be drawn by JavaScript; you can still add it with your own selectors."
            )
        return Discovery(
            source = SourceConfig(name = siteName, homeUrl = pageUrl, recipe = best.recipe),
            method = "No feed found. Detected the article list from the page layout (${best.recipe.item})",
            preview = best.items,
            alternatives = candidates.drop(1),
        )
    }

    private fun tryFeed(
        feedUrl: String,
        http: HttpFetcher,
        headers: Map<String, String>,
        now: Long,
    ): Pair<String, ParsedFeed>? {
        val response = try {
            http.get(feedUrl, headers)
        } catch (e: Exception) {
            return null
        }
        if (response.code !in 200..299 || !FeedParser.looksLikeFeed(response.body)) return null
        val feed = try {
            FeedParser.parse(response.body, response.finalUrl, now)
        } catch (e: Exception) {
            return null
        }
        return if (feed.items.isEmpty()) null else response.finalUrl to feed
    }

    private fun feedDiscovery(feed: ParsedFeed, feedUrl: String, homeUrl: String, siteName: String?, method: String) =
        Discovery(
            source = SourceConfig(
                name = siteName ?: feed.title ?: Urls.host(feedUrl) ?: feedUrl,
                homeUrl = homeUrl,
                recipe = Recipe(kind = FetchKind.FEED, url = feedUrl),
            ),
            method = method,
            preview = feed.items,
        )
}
