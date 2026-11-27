package newz.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_795_000_000_000L // late 2026

private val RSS = """
<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/" xmlns:content="http://purl.org/rss/1.0/modules/content/">
<channel>
  <title>Example News</title>
  <link>https://news.example.com/</link>
  <item>
    <title><![CDATA[Senate passes budget &amp; avoids shutdown]]></title>
    <link>https://news.example.com/2026/11/26/budget?utm_source=rss</link>
    <description><![CDATA[<p>The vote was <b>61-39</b>.</p>]]></description>
    <pubDate>Thu, 26 Nov 2026 10:00:00 GMT</pubDate>
    <media:thumbnail url="https://img.example.com/budget.jpg"/>
    <category>Politics</category>
    <category><![CDATA[US news, Budget]]></category>
    <category>politics</category>
  </item>
  <item>
    <title>Storm closes coastal roads</title>
    <link>/2026/11/26/storm</link>
    <pubDate>Thu, 26 Nov 2026 08:30:00 -0500</pubDate>
    <content:encoded><![CDATA[<img src="https://img.example.com/storm.jpg"><p>Roads shut.</p>]]></content:encoded>
  </item>
  <item>
    <title>Duplicate of the budget story</title>
    <link>https://news.example.com/2026/11/26/budget</link>
  </item>
  <item><title>No link here</title></item>
</channel>
</rss>
""".trim()

private val ATOM = """
<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <title>Atom Example</title>
  <entry>
    <title>Rover finds ice</title>
    <link rel="self" href="https://atom.example.com/self/1"/>
    <link rel="alternate" href="https://atom.example.com/posts/rover"/>
    <summary>Ice was found.</summary>
    <category term="Space"/>
    <published>2026-11-25T12:00:00Z</published>
  </entry>
</feed>
""".trim()

private fun listingPage(): String {
    val cards = (1..6).joinToString("\n") { i ->
        """
        <article class="story-card promo">
          <a class="thumb" href="/news/story-$i"><img data-src="/img/$i.jpg" alt=""></a>
          <h3><a href="/news/story-$i?utm_campaign=home#top">Headline number $i about something that happened today</a></h3>
          <p class="teaser">Teaser text $i.</p>
          <span class="section">World</span> <span class="section">Region $i</span>
          <time datetime="2026-11-2${i}T09:00:00Z">Nov 2$i</time>
        </article>
        """
    }
    return """
    <html><head><title>Example Daily</title><meta property="og:site_name" content="Example Daily"></head><body>
      <nav>
        <a href="/section/world">World news and international coverage section</a>
        <a href="/section/politics">Politics news and government coverage section</a>
        <a href="/section/business">Business news and markets coverage section</a>
        <a href="/section/science">Science news and research coverage section</a>
      </nav>
      <main>
        $cards
        <div class="sponsored story-card"><h3><a href="/ads/1">Sponsored: a product you definitely need right now</a></h3></div>
      </main>
      <aside><ul>
        <li><a href="/opinion/1">Short one</a></li>
        <li><a href="https://other.example.org/x">An external link with a long enough headline text</a></li>
      </ul></aside>
      <footer><a href="/about">About this publication and its editorial standards</a></footer>
    </body></html>
    """
}

class ScrapingTest {
    @Test
    fun rssItemsAreParsedCleanedAndDeduplicated() {
        val feed = FeedParser.parse(RSS, "https://news.example.com/feed.xml", NOW)
        assertEquals("Example News", feed.title)
        assertEquals(2, feed.items.size)

        val first = feed.items[0]
        assertEquals("Senate passes budget & avoids shutdown", first.title)
        assertEquals("The vote was 61-39.", first.summary)
        assertEquals("https://img.example.com/budget.jpg", first.imageUrl)
        assertEquals(Dates.parse("2026-11-26T10:00:00Z"), first.publishedAt)

        assertEquals(listOf("Politics", "US news", "Budget"), first.tags)

        val second = feed.items[1]
        assertEquals(emptyList(), second.tags)
        assertEquals("https://news.example.com/2026/11/26/storm", second.url)
        assertEquals("https://img.example.com/storm.jpg", second.imageUrl)
        assertEquals(Dates.parse("2026-11-26T13:30:00Z"), second.publishedAt)
    }

    @Test
    fun atomUsesTheAlternateLink() {
        val feed = FeedParser.parse(ATOM, "https://atom.example.com/feed", NOW)
        assertEquals(1, feed.items.size)
        assertEquals("https://atom.example.com/posts/rover", feed.items[0].url)
        assertEquals("Ice was found.", feed.items[0].summary)
        assertEquals(listOf("Space"), feed.items[0].tags)
        assertNotNull(feed.items[0].publishedAt)
    }

    @Test
    fun datesInCommonFormats() {
        val expected = Dates.parse("2026-11-26T00:00:00Z")
        assertEquals(expected, Dates.parse("November 26, 2026"))
        assertEquals(expected, Dates.parse("Nov. 26, 2026"))
        assertEquals(expected, Dates.parse("26 November 2026"))
        assertEquals(expected, Dates.parse("2026-11-26"))
        assertEquals(Dates.parse("2026-11-26T15:00:00Z"), Dates.parse("Thu, 26 Nov 2026 10:00:00 EST"))
        assertEquals(NOW - 3 * 3_600_000L, Dates.parse("3 hrs ago", NOW))
        assertEquals(NOW - 60_000L, Dates.parse("a minute ago", NOW))
        assertNull(Dates.parse("not a date"))
        assertNull(Dates.parse(null))
    }

    @Test
    fun canonicalUrlDropsTrackingAndFragment() {
        assertEquals(
            "https://example.com/a?id=7",
            Urls.canonical("https://Example.com/a?utm_source=x&id=7&fbclid=abc#section"),
        )
        assertEquals("https://example.com/", Urls.canonical("https://example.com"))
    }

    @Test
    fun selectorRecipeExtractsEveryField() {
        val recipe = Recipe(
            kind = FetchKind.SELECTORS,
            url = "https://daily.example.com/",
            item = "article.story-card",
            title = "h3",
            link = "h3 a",
            summary = "p.teaser",
            image = "img",
            date = "time",
            tags = "span.section",
        )
        val items = RecipeExtractor.extract(listingPage(), recipe, NOW)
        assertEquals(6, items.size)
        val first = items[0]
        assertEquals("Headline number 1 about something that happened today", first.title)
        assertEquals("https://daily.example.com/news/story-1?utm_campaign=home#top", first.url)
        assertEquals("Teaser text 1.", first.summary)
        assertEquals("https://daily.example.com/img/1.jpg", first.imageUrl)
        assertEquals(Dates.parse("2026-11-21T09:00:00Z"), first.publishedAt)
        assertEquals(listOf("World", "Region 1"), first.tags)
    }

    @Test
    fun blankSelectorsFallBackToHeadingAndItsLink() {
        val recipe = Recipe(kind = FetchKind.SELECTORS, url = "https://daily.example.com/", item = "article.story-card")
        val items = RecipeExtractor.extract(listingPage(), recipe, NOW)
        assertEquals(6, items.size)
        assertEquals("Headline number 2 about something that happened today", items[1].title)
        assertTrue(items[1].url.startsWith("https://daily.example.com/news/story-2"))
    }

    @Test
    fun excludeSelectorRemovesSponsoredBlocks() {
        val base = Recipe(kind = FetchKind.SELECTORS, url = "https://daily.example.com/", item = ".story-card")
        assertEquals(7, RecipeExtractor.extract(listingPage(), base, NOW).size)
        assertEquals(6, RecipeExtractor.extract(listingPage(), base.copy(exclude = ".sponsored"), NOW).size)
    }

    @Test
    fun badSelectorIsReportedNotThrownRaw() {
        val recipe = Recipe(kind = FetchKind.SELECTORS, url = "https://daily.example.com/", item = "div[[")
        assertFailsWith<RecipeException> { RecipeExtractor.extract(listingPage(), recipe, NOW) }
    }

    @Test
    fun heuristicFindsTheArticleListAndIgnoresNavigation() {
        val candidates = HeuristicDetector.detect(listingPage(), "https://daily.example.com/", NOW)
        assertTrue(candidates.isNotEmpty(), "expected a candidate")
        val best = candidates.first()
        assertEquals("article.story-card.promo", best.recipe.item)
        assertEquals(6, best.items.size)
        assertTrue(best.items.all { it.title.startsWith("Headline number") })
        assertTrue(best.items.all { it.imageUrl != null })
    }

    @Test
    fun heuristicHandlesBareLinkLists() {
        val links = (1..8).joinToString("\n") {
            """<li><a href="/post/$it">A plain list headline number $it with enough words</a></li>"""
        }
        val html = "<html><body><ul class=\"river\">$links</ul></body></html>"
        val best = HeuristicDetector.detect(html, "https://plain.example.com/", NOW).first()
        assertEquals("ul.river > li", best.recipe.item)
        assertEquals(8, best.items.size)
    }

    @Test
    fun heuristicGivesUpOnAnEmptyShell() {
        val html = "<html><body><div id=\"root\"></div><script>render()</script></body></html>"
        assertTrue(HeuristicDetector.detect(html, "https://spa.example.com/", NOW).isEmpty())
    }

    private class FakeHttp(private val pages: Map<String, HttpResponse>) : HttpFetcher {
        val requested = ArrayList<Pair<String, Map<String, String>>>()
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            requested.add(url to headers)
            return pages[url] ?: HttpResponse(404, "", finalUrl = url)
        }
    }

    private fun ok(url: String, body: String, etag: String? = null) = HttpResponse(200, body, etag = etag, finalUrl = url)

    @Test
    fun discoveryPrefersAnAdvertisedFeed() {
        val home = "https://news.example.com/"
        val html = """<html><head><title>Example News</title>
            <link rel="alternate" type="application/rss+xml" href="/feed.xml"></head><body></body></html>"""
        val http = FakeHttp(mapOf(home to ok(home, html), "https://news.example.com/feed.xml" to ok("https://news.example.com/feed.xml", RSS)))
        val found = SourceDiscovery.discover("news.example.com/", http, NOW)
        assertEquals(FetchKind.FEED, found.source.recipe.kind)
        assertEquals("https://news.example.com/feed.xml", found.source.recipe.url)
        assertEquals("Example News", found.source.name)
        assertEquals(2, found.preview.size)
    }

    @Test
    fun discoveryTriesCommonFeedPaths() {
        val home = "https://atom.example.com/"
        val http = FakeHttp(mapOf(home to ok(home, "<html><body>nothing</body></html>"), "https://atom.example.com/atom.xml" to ok("https://atom.example.com/atom.xml", ATOM)))
        val found = SourceDiscovery.discover(home, http, NOW)
        assertEquals("https://atom.example.com/atom.xml", found.source.recipe.url)
    }

    @Test
    fun discoveryFallsBackToThePageLayout() {
        val home = "https://daily.example.com/"
        val http = FakeHttp(mapOf(home to ok(home, listingPage())))
        val found = SourceDiscovery.discover(home, http, NOW)
        assertEquals(FetchKind.SELECTORS, found.source.recipe.kind)
        assertEquals("Example Daily", found.source.name)
        assertEquals(6, found.preview.size)
    }

    @Test
    fun discoveryExplainsFailure() {
        val home = "https://spa.example.com/"
        val http = FakeHttp(mapOf(home to ok(home, "<html><body><div id=root></div></body></html>")))
        assertFailsWith<DiscoveryException> { SourceDiscovery.discover(home, http, NOW) }
        assertFailsWith<DiscoveryException> { SourceDiscovery.discover("https://missing.example.com/", http, NOW) }
    }

    @Test
    fun fetcherSendsValidatorsAndHandlesEachOutcome() {
        val url = "https://news.example.com/feed.xml"
        val source = SourceConfig("Example", "https://news.example.com/", Recipe(FetchKind.FEED, url), headers = mapOf("X-Test" to "1"))

        val http = FakeHttp(mapOf(url to ok(url, RSS, etag = "\"v2\"")))
        val success = SourceFetcher.fetch(source, http, etag = "\"v1\"", lastModified = "yesterday", now = NOW)
        assertTrue(success is FetchOutcome.Success)
        assertEquals(2, success.items.size)
        assertEquals("\"v2\"", success.etag)
        val sent = http.requested.single().second
        assertEquals("\"v1\"", sent["If-None-Match"])
        assertEquals("yesterday", sent["If-Modified-Since"])
        assertEquals("1", sent["X-Test"])

        val unchanged = FakeHttp(mapOf(url to HttpResponse(304, "", finalUrl = url)))
        assertEquals(FetchOutcome.NotModified, SourceFetcher.fetch(source, unchanged, now = NOW))

        val failed = SourceFetcher.fetch(source, FakeHttp(emptyMap()), now = NOW)
        assertEquals(FetchOutcome.Failed("HTTP 404"), failed)

        val broken = HttpFetcher { _, _ -> throw java.io.IOException("timeout") }
        assertEquals(FetchOutcome.Failed("Network error: timeout"), SourceFetcher.fetch(source, broken, now = NOW))
    }
}
