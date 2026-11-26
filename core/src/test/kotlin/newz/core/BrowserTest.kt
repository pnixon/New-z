package newz.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val EASY = FilterSource(
    "Test list",
    """
    [Adblock Plus 2.0]
    ! Title: test
    ||ads.example.net^
    ||tracker.example.org^${'$'}third-party
    ||cdn.example.com/ads/
    ||metrics.example.com/collect^${'$'}script,image
    ||widgets.example.io/*/banner
    ||siteonly.example.com^${'$'}domain=news.example.com
    ||popups.example.com^${'$'}popup
    @@||good.ads.example.net^
    /banner/ads/*
    ##.ad-banner
    ##div[id^="sponsored-"]
    news.example.com,blog.example.com##.promo-rail
    ~news.example.com##.not-here
    example.com#@#.ad-banner
    ##+js(abort-on-property-read, foo)
    ##.thing:has-text(Advert)
    """.trimIndent(),
)

private val HOSTS = FilterSource(
    "Hosts list",
    """
    # a hosts file
    127.0.0.1 localhost
    0.0.0.0 hostsfile.example.net
    127.0.0.1 other.example.net  # trailing comment
    plainhost.example.org
    not a host line
    """.trimIndent(),
)

private const val PAGE = "https://www.news.example.com/world/story"

class BrowserTest {
    private val blocker = AdBlocker.build(listOf(EASY, HOSTS))

    @Test
    fun hostRulesBlockTheHostAndItsSubdomains() {
        assertEquals(BlockDecision("||ads.example.net^", "Test list"), blocker.check("https://ads.example.net/x.js", PAGE))
        assertNotNull(blocker.check("https://eu.ads.example.net/pixel.gif?id=1", PAGE))
        assertNull(blocker.check("https://example.net/x.js", PAGE), "the parent domain is not blocked")
        assertNull(blocker.check("https://notads.example.net/x.js", PAGE))
    }

    @Test
    fun exceptionsWin() {
        assertNull(blocker.check("https://good.ads.example.net/x.js", PAGE))
    }

    @Test
    fun thirdPartyOptionSparesTheSitesOwnRequests() {
        assertNotNull(blocker.check("https://tracker.example.org/t.js", PAGE))
        assertNull(blocker.check("https://tracker.example.org/t.js", "https://www.example.org/page"))
        assertNotNull(blocker.check("https://tracker.example.org/t.js", null), "no page means third party")
    }

    @Test
    fun pathRulesMatchByPrefixOnly() {
        assertEquals("||cdn.example.com/ads/", blocker.check("https://cdn.example.com/ads/banner.png", PAGE)?.rule)
        assertNull(blocker.check("https://cdn.example.com/images/photo.png", PAGE))
        assertNotNull(blocker.check("https://metrics.example.com/collect?v=1", PAGE))
        assertNull(blocker.check("https://metrics.example.com/other", PAGE))
    }

    @Test
    fun unsupportedRulesAreSkippedNotGuessed() {
        // Wildcard mid-path, per-site option, pop-up only, and a bare path pattern.
        assertNull(blocker.check("https://widgets.example.io/a/banner", PAGE))
        assertNull(blocker.check("https://siteonly.example.com/x", PAGE))
        assertNull(blocker.check("https://popups.example.com/x", PAGE))
        assertNull(blocker.check("https://innocent.example.com/banner/ads/1", PAGE))
        assertTrue(blocker.skippedRules >= 7, "skipped ${blocker.skippedRules}")
    }

    @Test
    fun hostsFilesAndPlainHostLinesAreRead() {
        assertEquals("Hosts list", blocker.check("https://hostsfile.example.net/a", PAGE)?.list)
        assertNotNull(blocker.check("https://other.example.net/a", PAGE))
        assertNotNull(blocker.check("https://plainhost.example.org/a", PAGE))
        assertNull(blocker.check("https://localhost/a", PAGE))
    }

    @Test
    fun malformedAddressesAreLeftAlone() {
        assertNull(blocker.check("data:image/png;base64,AAAA", PAGE))
        assertNull(blocker.check("not a url", PAGE))
    }

    @Test
    fun elementHidingUsesGenericAndSiteRules() {
        val news = blocker.elementHidingCss("www.news.example.com")
        assertTrue(".ad-banner" in news)
        assertTrue("div[id^=\"sponsored-\"]" in news)
        assertTrue(".promo-rail" in news)
        assertTrue(news.endsWith("{display:none!important}"))
        val other = blocker.elementHidingCss("other.example.com")
        assertTrue(".ad-banner" in other)
        assertFalse(".promo-rail" in other)
        assertFalse(".not-here" in other || "+js" in other || "has-text" in other)
        assertEquals(4, blocker.elementRuleCount)
    }

    @Test
    fun builtInListWorksAlone() {
        val builtIn = AdBlocker.build(listOf(AdBlocker.BUILT_IN))
        assertNotNull(builtIn.check("https://securepubads.g.doubleclick.net/tag/js/gpt.js", PAGE))
        assertNull(builtIn.check("https://www.news.example.com/app.js", PAGE))
        assertEquals(0, builtIn.skippedRules)
        assertTrue(builtIn.requestRuleCount > 20)
    }

    @Test
    fun customRulesUseTheSameSyntax() {
        val custom = AdBlocker.build(listOf(FilterSource("Your rules", "||annoying.example.com^\n##.newsletter-popup")))
        assertEquals("Your rules", custom.check("https://annoying.example.com/x", PAGE)?.list)
        assertTrue(".newsletter-popup" in custom.elementHidingCss(null))
    }

    @Test
    fun siteIsTheRegistrableDomain() {
        assertEquals("example.com", Urls.site("www.news.example.com"))
        assertEquals("example.co.uk", Urls.site("news.example.co.uk"))
        assertEquals("example.com", Urls.site("example.com"))
    }

    @Test
    fun readerExtractsTheArticleAndStripsScripts() {
        val paragraphs = (1..8).joinToString("\n") {
            "<p>Paragraph $it of the story. The council met on Tuesday and debated the transit plan for several hours before voting.</p>"
        }
        val html = """
            <html><head><title>Council approves transit plan - Example Daily</title></head><body>
            <nav><a href="/">Home</a><a href="/world">World</a></nav>
            <article>
              <h1>Council approves transit plan</h1>
              <p class="byline">By A. Reporter</p>
              $paragraphs
              <img src="/img/council.jpg" onerror="steal()">
              <script>track()</script>
              <p><a href="/related" onclick="x()">Related coverage of the vote</a></p>
            </article>
            <aside class="ad">Buy things</aside>
            <footer>Copyright</footer>
            </body></html>
        """
        val article = assertNotNull(Reader.extract(html, "https://daily.example.com/news/council"))
        assertTrue("Council approves transit plan" in article.title)
        assertTrue("Paragraph 8 of the story" in article.contentHtml)
        assertFalse("<script" in article.contentHtml || "track()" in article.contentHtml)
        assertFalse("onerror" in article.contentHtml || "onclick" in article.contentHtml)
        assertTrue("https://daily.example.com/img/council.jpg" in article.contentHtml, "image addresses are made absolute")
        assertTrue(article.textLength > 500)

        val page = Reader.page(article, "https://daily.example.com/news/council", dark = true)
        assertTrue("#121212" in page && "daily.example.com" in page)
        assertFalse("<script" in page)
    }

    @Test
    fun readerDeclinesPagesWithoutAnArticle() {
        assertNull(Reader.extract("<html><body><nav><a href='/'>Home</a></nav><p>Short.</p></body></html>", "https://x.example.com/"))
    }

    @Test
    fun browserSettingsTravelInTheConfigFile() {
        val config = AppConfig(browser = BrowserSettings(customRules = "||x.example.com^", allowedSites = listOf("npr.org")))
        assertEquals(config, ConfigJson.decode(ConfigJson.encode(config)))
        assertEquals(3, ConfigJson.decode("{}").browser.filterLists.size)
    }
}
