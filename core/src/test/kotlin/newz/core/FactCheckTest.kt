package newz.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW = 1_795_000_000_000L
private const val ARTICLE = "https://www.daily.example.com/news/budget"

class FactCheckTest {
    private val settings = ModelSettings(model = "check/model", maxClaims = 2)

    private class Llm(private vararg val replies: String) : LlmClient {
        val requests = ArrayList<LlmRequest>()
        override fun complete(request: LlmRequest): LlmReply {
            requests.add(request)
            val reply = replies[minOf(requests.size - 1, replies.size - 1)]
            if (reply.startsWith("ERROR:")) throw LlmException(reply.removePrefix("ERROR:"))
            return LlmReply(reply)
        }
    }

    private fun results(vararg sites: String) = SearchOutcome(
        sites.mapIndexed { i, site -> SearchResult("https://$site/story-$i", "Coverage from $site", "Snippet $i") }, "raw", null,
    )

    private val trust = mapOf("npr.org" to TrustLevel.TRUSTED, "spamnews.net" to TrustLevel.BLOCKED)
    private fun trustOf(site: String) = trust[site] ?: TrustLevel.UNRATED

    private val claimsReply = """{"claims": ["The Senate passed the budget 61-39 on Tuesday.", "The bill adds 40 billion in spending.", "A third claim over the limit."]}"""

    @Test
    fun labelsEachClaimFromTheSearchEvidence() {
        val llm = Llm(
            claimsReply,
            """{"claims": [
                {"claim": 1, "status": "corroborated", "supports": [1, 2], "contradicts": [], "same_origin": true, "note": "Both repeat the AP report."},
                {"claim": 2, "status": "disputed", "supports": [], "contradicts": [1], "same_origin": false, "note": "NPR reports 35 billion."}
            ]}""",
        )
        val queries = ArrayList<String>()
        val search = { q: String ->
            queries.add(q)
            // The article's own site and a blocked site are in the results and must be dropped.
            results("www.npr.org", "bbc.com", "daily.example.com", "spamnews.net")
        }
        val result = FactChecker(llm, search, settings, ::trustOf).check("Senate passes budget", "Full article text.", ARTICLE, NOW)
        val check = result.check
        assertNull(check.error)
        assertEquals(2, check.claims.size, "limited to maxClaims")
        assertEquals(listOf("The Senate passed the budget 61-39 on Tuesday.", "The bill adds 40 billion in spending."), queries)
        assertEquals(2, check.searches)

        val first = check.claims[0]
        assertEquals(ClaimStatus.CORROBORATED, first.status)
        assertEquals(listOf("npr.org", "bbc.com"), first.sources.map { it.site })
        assertEquals(listOf(Stance.SUPPORTS, Stance.SUPPORTS), first.sources.map { it.stance })
        assertEquals(2, first.supportingSites)
        assertEquals(1, first.trustedSupportingSites)
        assertTrue(first.sameOrigin)
        assertEquals("Both repeat the AP report.", first.note)

        val second = check.claims[1]
        assertEquals(ClaimStatus.DISPUTED, second.status)
        assertEquals(Stance.CONTRADICTS, second.sources[0].stance)
        assertEquals(Stance.UNRELATED, second.sources[1].stance)
        assertEquals(0, second.supportingSites)
        assertFalse(second.sameOrigin)

        // Prompts carry the article and the evidence, with placeholders filled.
        assertTrue("Senate passes budget" in llm.requests[0].user && "Full article text." in llm.requests[0].user)
        assertFalse("{max}" in llm.requests[0].user || "{text}" in llm.requests[0].user)
        val compare = llm.requests[1].user
        assertTrue("Claim 1: The Senate passed" in compare && "[1] npr.org: Coverage from www.npr.org — Snippet 0" in compare)
        assertFalse("daily.example.com" in compare || "spamnews.net" in compare)
        assertEquals(listOf("claims", "compare"), result.calls.map { it.task })
        assertTrue(result.calls.all { it.accepted })
    }

    @Test
    fun aVerdictWithoutListedSourcesIsNotBelieved() {
        val llm = Llm(
            claimsReply,
            """{"claims": [{"claim": 1, "status": "corroborated", "supports": [], "contradicts": []},
                           {"claim": 2, "status": "corroborated", "supports": [9], "contradicts": []}]}""",
        )
        val check = FactChecker(llm, { results("bbc.com") }, settings, ::trustOf).check("T", "x", ARTICLE, NOW).check
        assertEquals(listOf(ClaimStatus.UNCONFIRMED, ClaimStatus.UNCONFIRMED), check.claims.map { it.status })
    }

    @Test
    fun unconfirmedWhenASearchFindsNothingForThatClaim() {
        val llm = Llm(claimsReply, """{"claims": [{"claim": 1, "status": "corroborated", "supports": [1]}, {"claim": 2, "status": "unconfirmed"}]}""")
        var n = 0
        val search = { _: String -> if (n++ == 0) results("bbc.com") else SearchOutcome(emptyList(), "", null) }
        val check = FactChecker(llm, search, settings, ::trustOf).check("T", "x", ARTICLE, NOW).check
        assertEquals(ClaimStatus.CORROBORATED, check.claims[0].status)
        assertEquals(ClaimStatus.UNCONFIRMED, check.claims[1].status)
        assertTrue(check.claims[1].sources.isEmpty())
        assertTrue("(no results)" in llm.requests[1].user)
    }

    @Test
    fun failuresAreReportedNotGuessed() {
        fun run(llm: Llm, search: (String) -> SearchOutcome) = FactChecker(llm, search, settings, ::trustOf).check("T", "x", ARTICLE, NOW)

        val noKey = run(Llm("ERROR:HTTP 401: bad key")) { results("bbc.com") }
        assertEquals("HTTP 401: bad key", noKey.check.error)
        assertTrue(noKey.check.claims.isEmpty())
        assertFalse(noKey.calls.single().accepted)

        val noClaims = run(Llm("""{"claims": []}""")) { results("bbc.com") }
        assertEquals("The model found no checkable claims in this article", noClaims.check.error)

        val searchDown = run(Llm(claimsReply)) { SearchOutcome(emptyList(), "", "HTTP 402: out of credit") }
        assertEquals("Web search failed: HTTP 402: out of credit", searchDown.check.error)
        assertEquals(1, searchDown.calls.size, "the comparison is not attempted without evidence")

        val onlyOwnSite = run(Llm(claimsReply)) { results("daily.example.com") }
        assertEquals("The searches returned no other coverage to compare with", onlyOwnSite.check.error)

        val badCompare = run(Llm(claimsReply, "I could not decide.")) { results("bbc.com") }
        assertEquals("The model's comparison could not be read", badCompare.check.error)
        assertNotNull(badCompare.calls.last().response)
    }

    @Test
    fun resultsAndPromptsAreStorableAndEditable() {
        val check = FactCheck(ARTICLE, NOW, "m", listOf(ClaimCheck("c", ClaimStatus.DISPUTED, "n", listOf(ClaimSource("https://a.example.com/", "t", "a.example.com", TrustLevel.LOW, Stance.CONTRADICTS)), 0, 0, false)), 1)
        val json = ConfigJson.json.encodeToString(FactCheck.serializer(), check)
        assertEquals(check, ConfigJson.json.decodeFromString(FactCheck.serializer(), json))

        val llm = Llm(claimsReply, """{"claims": []}""")
        FactChecker(llm, { results("bbc.com") }, settings.copy(claimsPrompt = "CUSTOM {title}", comparePrompt = "MINE {claims}"), ::trustOf).check("Headline", "x", ARTICLE, NOW)
        assertEquals("CUSTOM Headline", llm.requests[0].user)
        assertTrue(llm.requests[1].user.startsWith("MINE Claim 1:"))
    }
}
