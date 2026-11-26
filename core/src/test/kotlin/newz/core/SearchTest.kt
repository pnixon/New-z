package newz.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_795_000_000_000L

class SearchTest {
    @Test
    fun readsAFlatDataArrayAndSkipsNonResults() {
        val json = """{"meta":{"ms":120},"data":[
            {"t":0,"url":"https://a.example.com/1","title":"First <b>result</b>","snippet":"About &amp; around","published":"2026-11-25T10:00:00Z"},
            {"t":1,"list":["related one","related two"]},
            {"t":0,"url":"https://a.example.com/1?utm_source=x","title":"Duplicate of the first"},
            {"t":0,"url":"ftp://nope.example.com/x","title":"Not a web address"},
            {"t":0,"url":"https://b.example.org/2","title":"Second result"}
        ]}"""
        val results = SearchResults.parse(json, NOW)
        assertEquals(listOf("https://a.example.com/1", "https://b.example.org/2"), results.map { it.url })
        assertEquals("First result", results[0].title)
        assertEquals("About & around", results[0].snippet)
        assertEquals(Dates.parse("2026-11-25T10:00:00Z"), results[0].publishedAt)
        assertNull(results[1].snippet)
    }

    @Test
    fun findsTheResultListWhereverItIsNested() {
        val brave = """{"query":{"original":"x"},"mixed":{"main":[{"type":"web","index":0}]},
            "web":{"results":[
              {"title":"Brave one","url":"https://one.example.com/","description":"Desc one","age":"3 hours ago"},
              {"title":"Brave two","url":"https://two.example.com/","description":"Desc two"}]},
            "news":{"results":[{"title":"Only one news item","url":"https://news.example.com/"}]}}"""
        val results = SearchResults.parse(brave, NOW)
        assertEquals(listOf("Brave one", "Brave two"), results.map { it.title })
        assertEquals(NOW - 3 * 3_600_000L, results[0].publishedAt)

        val tavily = """{"results":[{"url":"https://t.example.com/a","title":"Tavily","content":"Body text","published_date":"Thu, 26 Nov 2026 10:00:00 GMT"}]}"""
        assertEquals("Body text", SearchResults.parse(tavily, NOW).single().snippet)
        assertNotNull(SearchResults.parse(tavily, NOW).single().publishedAt)
        assertEquals(emptyList(), SearchResults.parse("{}", NOW))
        assertFailsWith<Exception> { SearchResults.parse("<html>", NOW) }
    }

    private class Recorder : HttpFetcher, HttpPoster {
        var url = ""
        var headers = emptyMap<String, String>()
        var body: String? = null
        var reply = HttpResponse(200, """{"data":[{"url":"https://r.example.com/","title":"R"}]}""", finalUrl = "")
        override fun get(url: String, headers: Map<String, String>): HttpResponse {
            this.url = url; this.headers = headers; this.body = null
            return reply
        }
        override fun postJson(url: String, headers: Map<String, String>, body: String): HttpResponse {
            this.url = url; this.headers = headers; this.body = body
            return reply
        }
    }

    @Test
    fun postRequestEscapesTheQueryIntoTheJsonBody() {
        val http = Recorder()
        val outcome = SearchClient(http, http, SearchSettings(), " key ").search("\"rust\" language\nnews", NOW)
        assertNull(outcome.error)
        assertEquals(1, outcome.results.size)
        assertEquals("https://kagi.com/api/v1/search", http.url)
        assertEquals("Bearer key", http.headers["Authorization"])
        assertEquals("""{"query": "\"rust\" language\nnews"}""", http.body)
    }

    @Test
    fun getRequestEncodesTheQueryAndUsesTheConfiguredHeader() {
        val http = Recorder()
        val brave = SearchSettings.PRESETS.first { it.first == "Brave Search" }.second.copy(resultsPerSearch = 5)
        SearchClient(http, http, brave, "tok").search("city council & transit", NOW)
        assertEquals("https://api.search.brave.com/res/v1/web/search?q=city+council+%26+transit&count=5", http.url)
        assertEquals("tok", http.headers["X-Subscription-Token"])
        assertNull(http.headers["Authorization"])
        assertNull(http.body)
    }

    @Test
    fun errorsAreReportedWithTheRawReply() {
        val http = Recorder()
        http.reply = HttpResponse(401, """{"error":"bad token"}""", finalUrl = "")
        val denied = SearchClient(http, http, SearchSettings(), "k").search("x", NOW)
        assertTrue(denied.error!!.startsWith("HTTP 401"))
        assertEquals("""{"error":"bad token"}""", denied.raw)

        http.reply = HttpResponse(200, "<html>login</html>", finalUrl = "")
        assertEquals("The reply was not JSON", SearchClient(http, http, SearchSettings(), "k").search("x", NOW).error)

        val offline = HttpPoster { _, _, _ -> throw java.io.IOException("offline") }
        assertTrue(SearchClient(http, offline, SearchSettings(), "k").search("x", NOW).error!!.contains("offline"))
    }

    @Test
    fun searchSettingsTravelInTheConfigFile() {
        val config = AppConfig(search = SearchSettings.PRESETS[2].second)
        assertEquals(config, ConfigJson.decode(ConfigJson.encode(config)))
        assertTrue("apiKey" !in ConfigJson.encode(config))
    }
}
