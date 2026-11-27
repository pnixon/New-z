package newz.core

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PAGE = "https://daily.example.com/"

private fun page(cards: Int = 8): String {
    val items = (1..cards).joinToString("\n") { i ->
        """
        <div class="x9f2 card" data-track="abc$i" style="color:red" onclick="go()">
          <a href="/news/story-$i"><img src="/img/$i.jpg"></a>
          <span class="kicker">World</span>
          <a class="headline" href="/news/story-$i">Headline number $i about something that happened today and ran long enough to be cut down</a>
          <time datetime="2026-11-2${i % 10}T09:00:00Z">recent</time>
        </div>
        """
    }
    return """
    <html><head><title>Daily</title><script>var secret = "tracking code";</script><style>.x{}</style></head>
    <body><!-- a comment -->
      <nav><a href="/world">World</a><a href="/politics">Politics</a></nav>
      <main>$items</main>
      <aside><div class="promo"><a href="https://ads.example.net/1">Buy this thing now, a sponsored message</a></div></aside>
    </body></html>
    """
}

/** Replies in order, recording every request. */
private class ScriptedLlm(private vararg val replies: String) : LlmClient {
    val requests = ArrayList<LlmRequest>()
    override fun complete(request: LlmRequest): LlmReply {
        requests.add(request)
        val reply = replies[minOf(requests.size - 1, replies.size - 1)]
        if (reply.startsWith("ERROR:")) throw LlmException(reply.removePrefix("ERROR:"))
        return LlmReply(reply, promptTokens = 100, completionTokens = 20)
    }
}

private const val GOOD = """{"item": "div.card", "title": "a.headline", "link": "a.headline", "summary": "", "image": "img", "date": "time", "tags": "span.kicker", "exclude": ""}"""

class ModelTest {
    private val settings = ModelSettings(model = "small/model", fallbackModel = "big/model")

    @Test
    fun reducerKeepsStructureAndDropsNoise() {
        val out = HtmlReducer.reduce(page(), PAGE)
        assertFalse("tracking code" in out, "scripts are removed")
        assertFalse("a comment" in out)
        assertFalse("onclick" in out)
        assertFalse("data-track" in out)
        assertFalse("style=" in out)
        assertTrue("class=\"x9f2 card\"" in out)
        assertTrue("href=\"/news/story-1\"" in out)
        assertTrue("datetime=" in out)
        assertTrue("…" in out, "long text is truncated")
        assertFalse("ran long enough to be cut down" in out)
        // Eight look-alike cards become five plus a note.
        assertEquals(5, Regex("class=\"x9f2 card\"").findAll(out).count())
        assertTrue("3 more <div.x9f2 card> elements" in out)
        assertTrue(out.length < page().length / 2)
    }

    @Test
    fun reducerRespectsTheBudget() {
        val out = HtmlReducer.reduce(page(), PAGE, budgetChars = 400)
        assertTrue(out.length < 460)
        assertTrue(out.endsWith("<!-- page cut here to fit the size limit -->"))
    }

    @Test
    fun validatorAcceptsARealListAndNamesEachFault() {
        fun items(n: Int, title: (Int) -> String, url: (Int) -> String) = (1..n).map { RawItem(url(it), title(it)) }
        assertTrue(RecipeValidator.problems(items(6, { "A proper headline number $it" }, { "$PAGE$it" }), PAGE).isEmpty())
        assertEquals(listOf("the selectors matched no articles"), RecipeValidator.problems(emptyList(), PAGE))
        assertTrue(RecipeValidator.problems(items(2, { "A proper headline number $it" }, { "$PAGE$it" }), PAGE).single().startsWith("only 2 articles"))
        assertTrue(RecipeValidator.problems(items(6, { "World" }, { "$PAGE$it" }), PAGE).any { "same title" in it })
        assertTrue(RecipeValidator.problems(items(6, { "Short $it" }, { "$PAGE$it" }), PAGE).any { "too short" in it })
        assertTrue(RecipeValidator.problems(items(6, { "A proper headline number $it" }, { "https://ads.example.net/$it" }), PAGE).any { "point away" in it })
    }

    @Test
    fun acceptsAGoodFirstAnswerAndFixesTheAddress() {
        val llm = ScriptedLlm("```json\n$GOOD\n```")
        val result = RecipeWriter(llm, settings).write(PAGE, page())
        assertNull(result.failure)
        val recipe = assertNotNull(result.recipe)
        assertEquals("div.card", recipe.item)
        assertEquals(PAGE, recipe.url)
        assertEquals(FetchKind.SELECTORS, recipe.kind)
        assertEquals(8, result.items.size)
        assertNotNull(result.items[0].publishedAt)
        assertEquals("span.kicker", recipe.tags)
        assertEquals(listOf("World"), result.items[0].tags)

        val request = llm.requests.single()
        assertEquals("small/model", request.model)
        assertTrue(PAGE in request.user)
        assertTrue("class=\"x9f2 card\"" in request.user, "the reduced page is in the prompt")
        assertFalse("{html}" in request.user)
        val call = result.calls.single()
        assertTrue(call.accepted)
        assertEquals("add", call.task)
        assertEquals(100, call.promptTokens)
    }

    @Test
    fun aModelCannotRedirectTheRecipeToAnotherAddress() {
        val reply = """{"item": "div.card", "url": "https://evil.example.net/", "kind": "FEED", "title": "a.headline"}"""
        val recipe = assertNotNull(RecipeWriter(ScriptedLlm(reply), settings).write(PAGE, page()).recipe)
        assertEquals(PAGE, recipe.url)
        assertEquals(FetchKind.SELECTORS, recipe.kind)
    }

    @Test
    fun rejectedAnswersAreFedBackAndTheLastAttemptEscalates() {
        val llm = ScriptedLlm(
            """{"item": "div.nothing"}""",
            """{"item": "div.card", "title": "span.kicker", "link": "a.headline"}""",
            GOOD,
        )
        val result = RecipeWriter(llm, settings).write(PAGE, page())
        assertNotNull(result.recipe)
        assertEquals(listOf("small/model", "small/model", "big/model"), llm.requests.map { it.model })
        assertTrue("matched no articles" in llm.requests[1].user)
        assertTrue("div.nothing" in llm.requests[1].user, "the rejected answer is quoted back")
        assertTrue("same title" in llm.requests[2].user)
        assertEquals(listOf(false, false, true), result.calls.map { it.accepted })
        assertTrue(result.calls[0].outcome.startsWith("Rejected"))
    }

    @Test
    fun givesUpAfterTheConfiguredAttemptsWithAReason() {
        val llm = ScriptedLlm("I cannot help with that.")
        val result = RecipeWriter(llm, settings.copy(maxAttempts = 2, fallbackModel = "")).write(PAGE, page())
        assertNull(result.recipe)
        assertEquals(2, llm.requests.size)
        assertTrue(llm.requests.all { it.model == "small/model" })
        assertTrue("not a JSON object" in result.failure!!)
    }

    @Test
    fun aRecipeScrapingAnotherSiteIsRejected() {
        val llm = ScriptedLlm("""{"item": "div.promo"}""")
        val result = RecipeWriter(llm, settings.copy(maxAttempts = 1)).write(PAGE, page())
        assertNull(result.recipe)
    }

    @Test
    fun aProviderErrorStopsImmediatelyAndIsLogged() {
        val llm = ScriptedLlm("ERROR:HTTP 401: No auth credentials found")
        val result = RecipeWriter(llm, settings).write(PAGE, page())
        assertEquals("HTTP 401: No auth credentials found", result.failure)
        assertEquals(1, llm.requests.size)
        assertEquals("Error: HTTP 401: No auth credentials found", result.calls.single().outcome)
    }

    @Test
    fun repairPromptCarriesTheOldRecipeAndHeadlines() {
        val llm = ScriptedLlm(GOOD)
        val old = Recipe(FetchKind.SELECTORS, PAGE, item = "article.old-card", title = "h2")
        val result = RecipeWriter(llm, settings).write(PAGE, page(), RepairContext(old, listOf("Storm closes roads", "Budget passes")))
        assertNotNull(result.recipe)
        val prompt = llm.requests.single().user
        assertTrue("\"item\": \"article.old-card\"" in prompt)
        assertTrue("- Storm closes roads" in prompt)
        assertFalse("{old_recipe}" in prompt || "{old_titles}" in prompt)
        assertEquals("repair", result.calls.single().task)
    }

    @Test
    fun editedPromptsAndParametersAreWhatIsSent() {
        val llm = ScriptedLlm(GOOD)
        val custom = settings.copy(systemPrompt = "SYS", addPrompt = "Look at {url} then {html}", temperature = 0.7, maxTokens = 55, extraBodyJson = """{"a": 1}""")
        RecipeWriter(llm, custom).write(PAGE, page())
        val request = llm.requests.single()
        assertEquals("SYS", request.system)
        assertTrue(request.user.startsWith("Look at $PAGE then <"))
        assertEquals(0.7, request.temperature)
        assertEquals(55, request.maxTokens)
        assertEquals("""{"a": 1}""", request.extraBodyJson)
    }

    @Test
    fun requestBodyMergesExtraFieldsWithTheUsersWinning() {
        val request = LlmRequest("m/one", "sys", "hello", 0.2, 300, """{"temperature": 1.5, "provider": {"sort": "price"}}""")
        val body = OpenAiCompatClient.buildBody(request)
        assertEquals("m/one", body.getValue("model").jsonPrimitive.content)
        assertEquals("1.5", body.getValue("temperature").jsonPrimitive.content)
        assertEquals("300", body.getValue("max_tokens").jsonPrimitive.content)
        assertEquals("price", body.getValue("provider").jsonObject.getValue("sort").jsonPrimitive.content)
        val messages = body.getValue("messages").jsonArray
        assertEquals(listOf("system", "user"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertFailsWith<LlmException> { OpenAiCompatClient.buildBody(request.copy(extraBodyJson = "[1]")) }
    }

    @Test
    fun clientSendsTheKeyOnlyToTheConfiguredEndpointAndReadsTheReply() {
        var seenUrl = ""
        var seenHeaders = emptyMap<String, String>()
        val poster = HttpPoster { url, headers, _ ->
            seenUrl = url
            seenHeaders = headers
            HttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"hi"}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}""", finalUrl = url)
        }
        val client = OpenAiCompatClient(poster, "https://llm.example.com/v1/", " sk-test ")
        val reply = client.complete(LlmRequest("m", "s", "u", 0.0, 10))
        assertEquals("https://llm.example.com/v1/chat/completions", seenUrl)
        assertEquals(mapOf("Authorization" to "Bearer sk-test"), seenHeaders)
        assertEquals(LlmReply("hi", 7, 2), reply)
    }

    @Test
    fun providerErrorsBecomeReadableMessages() {
        fun client(code: Int, body: String) =
            OpenAiCompatClient({ url, _, _ -> HttpResponse(code, body, finalUrl = url) }, "https://llm.example.com/v1", "k")
        val request = LlmRequest("m", "s", "u", 0.0, 10)
        assertEquals(
            "HTTP 402: Insufficient credits",
            assertFailsWith<LlmException> { client(402, """{"error":{"message":"Insufficient credits"}}""").complete(request) }.message,
        )
        assertEquals("HTTP 500", assertFailsWith<LlmException> { client(500, "oops").complete(request) }.message)
        assertEquals(
            "The provider's reply was not in the expected format",
            assertFailsWith<LlmException> { client(200, """{"unexpected": true}""").complete(request) }.message,
        )
        val offline = OpenAiCompatClient({ _, _, _ -> throw java.io.IOException("no route") }, "https://llm.example.com/v1", "k")
        assertTrue(assertFailsWith<LlmException> { offline.complete(request) }.message!!.contains("no route"))
    }

    @Test
    fun modelListIsReadAndSorted() {
        val client = OpenAiCompatClient({ _, _, _ -> error("unused") }, "https://llm.example.com/v1", "k")
        val models = client.listModels { url, _ ->
            assertEquals("https://llm.example.com/v1/models", url)
            HttpResponse(200, """{"data":[{"id":"zeta/large"},{"id":"alpha/small"},{"name":"no id"}]}""", finalUrl = url)
        }
        assertEquals(listOf("alpha/small", "zeta/large"), models)
    }

    @Test
    fun modelSettingsTravelInTheConfigFileWithoutAKeyField() {
        val config = AppConfig(models = ModelSettings(model = "m", addPrompt = "custom {html}"))
        val text = ConfigJson.encode(config)
        assertFalse("apiKey" in text || "api_key" in text)
        assertEquals(config, ConfigJson.decode(text))
        assertTrue(ModelSettings().systemPrompt.isNotBlank())
        assertFalse(ModelSettings().enabled, "remote models are off until a model is chosen")
    }
}
