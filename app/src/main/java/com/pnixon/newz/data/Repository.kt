package com.pnixon.newz.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.runBlocking
import newz.core.HeuristicDetector
import newz.core.HttpResponse
import newz.core.RankItem
import newz.core.SearchClient
import newz.core.SearchOutcome
import newz.core.SearchSettings
import newz.core.Topics
import newz.core.FactCheck
import newz.core.FactChecker
import newz.core.Reader
import newz.core.AppConfig
import newz.core.PublishSettings
import newz.core.BrowserSettings
import newz.core.DEFAULT_USER_AGENT
import newz.core.DiscoveryException
import newz.core.HttpPoster
import newz.core.LlmException
import newz.core.LlmRequest
import newz.core.ModelCall
import newz.core.ModelSettings
import newz.core.OpenAiCompatClient
import newz.core.RecipeValidator
import newz.core.RecipeWriter
import newz.core.RepairContext
import newz.core.WriteResult
import newz.core.ConfigJson
import newz.core.DefaultSources
import newz.core.Discovery
import newz.core.FetchKind
import newz.core.FetchOutcome
import newz.core.HttpFetcher
import newz.core.RankingConfig
import newz.core.RawItem
import newz.core.Recipe
import newz.core.SourceConfig
import newz.core.SourceDiscovery
import newz.core.SourceFetcher
import newz.core.TrustLevel
import newz.core.Urls
import java.io.File

private const val DAY = 86_400_000L

/** How long articles are kept on the phone. */
const val KEEP_DAYS = 30

private val headerMap = MapSerializer(String.serializer(), String.serializer())

fun SourceEntity.trustLevel(): TrustLevel =
    runCatching { TrustLevel.valueOf(trust) }.getOrDefault(TrustLevel.UNRATED)

fun SourceEntity.recipe(): Recipe =
    runCatching { ConfigJson.decodeRecipe(recipeJson) }.getOrDefault(Recipe(FetchKind.FEED, homeUrl))

fun SourceEntity.toConfig(): SourceConfig = SourceConfig(
    name = name,
    homeUrl = homeUrl,
    recipe = recipe(),
    trust = trustLevel(),
    enabled = enabled,
    refreshMinutes = refreshMinutes,
    userAgent = userAgent,
    headers = runCatching { ConfigJson.json.decodeFromString(headerMap, headersJson) }.getOrDefault(emptyMap()),
)

/** A source is flagged as broken after this many failed or empty fetches in a row. */
const val BROKEN_AFTER = 3

fun SourceEntity.isBroken(): Boolean = failCount >= BROKEN_AFTER

data class RefreshSummary(
    val sourcesChecked: Int,
    val newArticles: Int,
    val failures: Int,
    val repaired: Int = 0,
    /** Topic web searches run in this check. */
    val searches: Int = 0,
    /** Articles first seen in this check, from sources and from searches. */
    val newIds: List<Long> = emptyList(),
)

data class TestResult(val items: List<RawItem>, val error: String?)

class Repository(
    private val context: Context,
    private val dao: NewsDao,
    private val logDao: LogDao,
    private val http: HttpFetcher,
    private val poster: HttpPoster,
    private val models: JsonStore<ModelSettings>,
    private val secrets: SecretStore,
    private val ranking: JsonStore<RankingConfig>,
    private val search: JsonStore<SearchSettings>,
    /** Loads a page in a hidden browser and returns its HTML after scripts have run. */
    private val render: suspend (url: String, userAgent: String?) -> String,
) {
    private val refreshLock = Mutex()
    private val prefs = context.getSharedPreferences("newz", Context.MODE_PRIVATE)

    val sources: Flow<List<SourceEntity>> = dao.sources()

    fun articles(): Flow<List<ArticleEntity>> =
        dao.articlesSince(System.currentTimeMillis() - KEEP_DAYS * DAY)

    fun logFor(sourceId: Long): Flow<List<FetchLogEntity>> = dao.logFor(sourceId)

    suspend fun source(id: Long): SourceEntity? = dao.source(id)

    /** Adds the starting sources on first launch only. */
    suspend fun seedIfNeeded() {
        if (prefs.getBoolean("seeded", false)) return
        if (dao.sourcesNow().isEmpty()) DefaultSources.all.forEach { save(null, it) }
        prefs.edit().putBoolean("seeded", true).apply()
    }

    /** Inserts a new source, or updates [id]. Changing the recipe clears the cached validators. */
    suspend fun save(id: Long?, config: SourceConfig, reason: String = "Replaced by your edit"): Long {
        val recipeJson = ConfigJson.encodeRecipe(config.recipe)
        val headersJson = ConfigJson.json.encodeToString(headerMap, config.headers)
        val existing = id?.let { dao.source(it) }
        if (existing == null) {
            return dao.insertSource(
                SourceEntity(
                    name = config.name,
                    homeUrl = config.homeUrl,
                    recipeJson = recipeJson,
                    trust = config.trust.name,
                    enabled = config.enabled,
                    refreshMinutes = config.refreshMinutes,
                    userAgent = config.userAgent,
                    headersJson = headersJson,
                )
            )
        }
        val recipeChanged = existing.recipe() != config.recipe
        if (recipeChanged) {
            logDao.insertHistory(
                RecipeHistoryEntity(sourceId = existing.id, at = System.currentTimeMillis(), recipeJson = existing.recipeJson, reason = reason)
            )
        }
        dao.updateSource(
            existing.copy(
                name = config.name,
                homeUrl = config.homeUrl,
                recipeJson = recipeJson,
                trust = config.trust.name,
                enabled = config.enabled,
                refreshMinutes = config.refreshMinutes,
                userAgent = config.userAgent,
                headersJson = headersJson,
                etag = if (recipeChanged) null else existing.etag,
                lastModified = if (recipeChanged) null else existing.lastModified,
                failCount = if (recipeChanged) 0 else existing.failCount,
            )
        )
        return existing.id
    }

    suspend fun setTrust(sourceId: Long, trust: TrustLevel) = dao.setTrust(sourceId, trust.name)

    suspend fun setRead(articleId: Long, read: Boolean) = dao.setRead(articleId, read)

    suspend fun delete(sourceId: Long) {
        dao.deleteArticlesOf(sourceId)
        dao.deleteLogOf(sourceId)
        logDao.deleteHistoryOf(sourceId)
        dao.deleteSource(sourceId)
    }

    /**
     * Works out how to follow a site, cheapest method first: a feed, then the
     * page layout, and only if that result fails the checks and a model is
     * set up, a model-written recipe.
     */
    suspend fun discover(url: String): Discovery = withContext(Dispatchers.IO) {
        val plain = runCatching { SourceDiscovery.discover(url, http) }
        val found = plain.getOrNull()
        if (found != null) {
            val recipe = found.source.recipe
            if (recipe.kind == FetchKind.FEED || RecipeValidator.problems(found.preview, recipe.url).isEmpty()) {
                return@withContext found
            }
        }
        val plainError = plain.exceptionOrNull()?.message ?: "The detected article list did not pass the checks"

        // The page may be empty until its scripts run: render it and look again.
        val renderUrl = found?.source?.recipe?.url ?: url.trim().let { if ("://" in it) it else "https://$it" }
        val rendered = runCatching { render(renderUrl, null) }.getOrNull()
        if (rendered != null) {
            val best = HeuristicDetector.detect(rendered, renderUrl).firstOrNull()
            if (best != null && RecipeValidator.problems(best.items, renderUrl).isEmpty()) {
                val base = found?.source ?: SourceConfig(name = Urls.host(renderUrl) ?: renderUrl, homeUrl = renderUrl, recipe = best.recipe)
                return@withContext Discovery(
                    source = base.copy(recipe = best.recipe.copy(render = true)),
                    method = "No feed, and the page is drawn by JavaScript, so it is loaded in a hidden browser before reading " +
                        "(${best.recipe.item}). This is slower than a plain fetch",
                    preview = best.items,
                )
            }
        }
        if (!models.flow.value.enabled) {
            return@withContext found ?: throw DiscoveryException("$plainError No model is set up to try instead.")
        }

        val pageUrl = found?.source?.recipe?.url ?: url.trim().let { if ("://" in it) it else "https://$it" }
        // If the plain page had nothing usable but the rendered one loaded, give the model the rendered one.
        val useRendered = found == null && rendered != null
        val written = askModel(pageUrl, sourceId = null, repair = null, rendered = useRendered)
        val recipe = written.recipe
        if (recipe == null) {
            val note = "A model was also asked and did not produce a working recipe: ${written.failure}"
            return@withContext found?.copy(method = "${found.method}. $note") ?: throw DiscoveryException("$plainError $note")
        }
        val base = found?.source ?: SourceConfig(name = Urls.host(pageUrl) ?: pageUrl, homeUrl = pageUrl, recipe = recipe)
        Discovery(
            source = base.copy(recipe = recipe),
            method = "No feed, and layout detection was not good enough, so ${written.calls.last().model} wrote the recipe " +
                "in ${written.calls.size} request(s). Every request is in the model log.",
            preview = written.items,
        )
    }

    private fun client(): OpenAiCompatClient? {
        val settings = models.flow.value
        if (!settings.enabled) return null
        return OpenAiCompatClient(poster, settings.baseUrl, secrets.get(SecretStore.MODEL_API_KEY).orEmpty())
    }

    /**
     * Fetches [pageUrl] and has the model write a recipe for it. With [repair]
     * the model is shown the old recipe and headlines. Every request is logged.
     */
    suspend fun askModel(
        pageUrl: String,
        sourceId: Long?,
        repair: RepairContext?,
        rendered: Boolean = false,
    ): WriteResult = withContext(Dispatchers.IO) {
        val settings = models.flow.value
        val llm = client()
            ?: return@withContext WriteResult(null, emptyList(), emptyList(), "No model is set up. Choose one on the Models tab.")
        val html = if (rendered) {
            try {
                render(pageUrl, null)
            } catch (e: Exception) {
                return@withContext WriteResult(null, emptyList(), emptyList(), "Could not render the page: ${e.message ?: e.javaClass.simpleName}")
            }
        } else {
            val page = try {
                http.get(pageUrl, mapOf("User-Agent" to DEFAULT_USER_AGENT))
            } catch (e: Exception) {
                return@withContext WriteResult(null, emptyList(), emptyList(), "Could not fetch the page: ${e.message ?: e.javaClass.simpleName}")
            }
            if (page.code !in 200..299) {
                return@withContext WriteResult(null, emptyList(), emptyList(), "The page answered HTTP ${page.code}, so there was nothing to send to a model")
            }
            page.body
        }
        val written = RecipeWriter(llm, settings).write(pageUrl, html, repair)
        // A recipe written from the rendered page only works on the rendered page.
        val result = if (rendered && written.recipe != null) written.copy(recipe = written.recipe?.copy(render = true)) else written
        logCalls(result.calls, sourceId, pageUrl, settings)
        result
    }

    /** The context for re-writing an existing source's recipe; null if it has no selector recipe yet. */
    suspend fun repairContext(sourceId: Long, current: Recipe): RepairContext? {
        if (current.kind != FetchKind.SELECTORS || current.item.isBlank()) return null
        return RepairContext(current, dao.recentTitles(sourceId, 8))
    }

    private suspend fun logCalls(calls: List<ModelCall>, sourceId: Long?, target: String, settings: ModelSettings) {
        val now = System.currentTimeMillis()
        for (call in calls) {
            logDao.insertCall(
                ModelCallEntity(
                    at = now,
                    task = call.task,
                    sourceId = sourceId,
                    target = target,
                    endpoint = Urls.host(settings.baseUrl) ?: settings.baseUrl,
                    model = call.model,
                    systemPrompt = call.system,
                    userPrompt = call.user,
                    response = call.response,
                    outcome = call.outcome,
                    accepted = call.accepted,
                    durationMs = call.durationMs,
                    promptTokens = call.promptTokens,
                    completionTokens = call.completionTokens,
                )
            )
        }
        logDao.pruneCalls()
    }

    /** Sends a one-line prompt to check the address, key and model. Logged like any other call. */
    suspend fun testConnection(): String = withContext(Dispatchers.IO) {
        val settings = models.flow.value
        val llm = client() ?: return@withContext "Enter a provider address and a model first."
        val system = "You are a connection test."
        val user = "Reply with the single word: ok"
        val started = System.nanoTime()
        val (text, outcome) = try {
            val reply = llm.complete(LlmRequest(settings.model.trim(), system, user, settings.temperature, 20, settings.extraBodyJson))
            reply.text to "Replied"
        } catch (e: LlmException) {
            null to "Error: ${e.message}"
        }
        val call = ModelCall("test", settings.model.trim(), system, user, text, outcome, text != null, (System.nanoTime() - started) / 1_000_000)
        logCalls(listOf(call), null, "connection test", settings)
        if (text != null) "It works. ${settings.model.trim()} replied: ${text.trim().take(80)}" else outcome
    }

    suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val settings = models.flow.value
        OpenAiCompatClient(poster, settings.baseUrl, secrets.get(SecretStore.MODEL_API_KEY).orEmpty()).listModels(http)
    }

    fun modelCalls(): Flow<List<ModelCallSummary>> = logDao.calls()

    suspend fun modelCall(id: Long): ModelCallEntity? = logDao.call(id)

    suspend fun clearModelCalls() = logDao.clearCalls()

    fun historyFor(sourceId: Long): Flow<List<RecipeHistoryEntity>> = logDao.historyFor(sourceId)

    /** Puts an earlier recipe back. The one it replaces goes into the history in turn. */
    suspend fun restoreRecipe(sourceId: Long, historyId: Long): Boolean {
        val source = dao.source(sourceId) ?: return false
        val entry = logDao.history(historyId) ?: return false
        val recipe = runCatching { ConfigJson.decodeRecipe(entry.recipeJson) }.getOrNull() ?: return false
        save(sourceId, source.toConfig().copy(recipe = recipe), reason = "Replaced by restoring an earlier version")
        return true
    }

    /**
     * Asks the model for a new recipe for a broken scraped source and applies
     * it if it passes the checks. Returns true when the source was repaired.
     */
    private suspend fun attemptRepair(source: SourceEntity): Boolean {
        val current = source.recipe()
        val context = repairContext(source.id, current) ?: return false
        val result = askModel(current.url, source.id, context, rendered = current.render)
        val now = System.currentTimeMillis()
        val recipe = result.recipe
        if (recipe == null) {
            // No model call means the page itself was unreachable; say so without logging a repair.
            val message = "Automatic repair did not work: ${result.failure}"
            dao.insertLog(FetchLogEntity(sourceId = source.id, at = now, ok = false, message = message, itemCount = 0, newCount = 0))
            return false
        }
        val model = result.calls.last().model
        save(source.id, source.toConfig().copy(recipe = recipe), reason = "Replaced by automatic repair using $model")
        val added = dao.insertArticles(result.items.map { it.toEntity(source.id, now) }).count { it != -1L }
        val status = "Repaired by $model: ${result.items.size} articles, $added new. The old recipe is in this source's history."
        dao.setFetchState(source.id, null, null, now, status, ok = true, itemCount = result.items.size, failCount = 0)
        dao.insertLog(FetchLogEntity(sourceId = source.id, at = now, ok = true, message = status, itemCount = result.items.size, newCount = added))
        return true
    }

    private fun RawItem.toEntity(sourceId: Long, now: Long) = ArticleEntity(
        sourceId = sourceId,
        url = url,
        canonicalUrl = Urls.canonical(url),
        title = title,
        summary = summary,
        imageUrl = imageUrl,
        publishedAt = publishedAt,
        fetchedAt = now,
        tags = tags.joinToString("\n").ifEmpty { null },
    )

    /** Plain HTTP, or the hidden browser for scraped pages marked as needing JavaScript. */
    private fun fetcherFor(config: SourceConfig): HttpFetcher {
        if (config.recipe.kind != FetchKind.SELECTORS || !config.recipe.render) return http
        return HttpFetcher { url, _ ->
            // Called on an IO thread; the renderer itself hops to the main thread.
            HttpResponse(200, runBlocking { render(url, config.userAgent) }, finalUrl = url)
        }
    }

    /** Runs a recipe once without saving anything. */
    suspend fun test(config: SourceConfig): TestResult = withContext(Dispatchers.IO) {
        when (val outcome = SourceFetcher.fetch(config, fetcherFor(config))) {
            is FetchOutcome.Success -> TestResult(outcome.items, if (outcome.items.isEmpty()) "The recipe matched no articles" else null)
            is FetchOutcome.Failed -> TestResult(emptyList(), outcome.message)
            FetchOutcome.NotModified -> TestResult(emptyList(), "The site says nothing changed")
        }
    }

    /**
     * Fetches every enabled, unblocked source that is due. [force] ignores the
     * per-source interval. [onlySourceId] limits the run to one source.
     */
    suspend fun refresh(force: Boolean, onlySourceId: Long? = null): RefreshSummary = refreshLock.withLock {
        val now = System.currentTimeMillis()
        val due = dao.sourcesNow().filter { s ->
            (onlySourceId == null || s.id == onlySourceId) &&
                s.enabled && s.trustLevel() != TrustLevel.BLOCKED && s.recipe().kind != FetchKind.NONE &&
                (force || s.lastFetchAt == null || now - (s.lastFetchAt ?: 0L) >= s.refreshMinutes * 60_000L)
        }
        val limit = Semaphore(4)
        val results = coroutineScope {
            due.map { source -> async { limit.withPermit { refreshOne(source) } } }.awaitAll()
        }
        dao.pruneArticles(now - KEEP_DAYS * DAY)
        dao.pruneLog(now - KEEP_DAYS * DAY)

        // Broken scraped sources get one model repair attempt per interval.
        var repaired = 0
        val settings = models.flow.value
        if (settings.autoRepair && settings.enabled) {
            val checked = due.map { it.id }.toSet()
            val interval = settings.repairIntervalHours.coerceAtLeast(1) * 3_600_000L
            for (source in dao.sourcesNow()) {
                if (source.id !in checked || !source.isBroken() || source.recipe().kind != FetchKind.SELECTORS) continue
                val last = logDao.lastRepairAt(source.id)
                if (last != null && now - last < interval) continue
                if (runCatching { attemptRepair(source) }.getOrDefault(false)) repaired++
            }
        }
        val newIds = ArrayList<Long>(results.flatMap { it.first })
        // Topic searches run on their own schedule, never because of a manual refresh.
        var searches = 0
        if (onlySourceId == null) {
            val (ran, found) = runCatching { runSearches(force = false) }.getOrDefault(0 to emptyList())
            searches = ran
            newIds.addAll(found)
        }
        RefreshSummary(due.size, newIds.size, results.count { !it.second }, repaired, searches, newIds)
    }

    /** Returns the ids of new articles and whether the fetch succeeded. */
    private suspend fun refreshOne(source: SourceEntity): Pair<List<Long>, Boolean> {
        val now = System.currentTimeMillis()
        val outcome = withContext(Dispatchers.IO) {
            val config = source.toConfig()
            SourceFetcher.fetch(config, fetcherFor(config), source.etag, source.lastModified, now)
        }
        when (outcome) {
            is FetchOutcome.Success -> {
                val rows = outcome.items.map { it.toEntity(source.id, now) }
                val addedIds = dao.insertArticles(rows).filter { it != -1L }
                val added = addedIds.size
                val empty = rows.isEmpty()
                val status = if (empty) "No articles found" else "${rows.size} articles, $added new"
                dao.setFetchState(
                    source.id, outcome.etag, outcome.lastModified, now, status,
                    ok = !empty, itemCount = rows.size, failCount = if (empty) source.failCount + 1 else 0,
                )
                dao.insertLog(FetchLogEntity(sourceId = source.id, at = now, ok = !empty, message = status, itemCount = rows.size, newCount = added))
                return addedIds to !empty
            }
            FetchOutcome.NotModified -> {
                val status = "Unchanged since last check"
                dao.setFetchState(
                    source.id, source.etag, source.lastModified, now, status,
                    ok = true, itemCount = source.lastItemCount, failCount = source.failCount,
                )
                dao.insertLog(FetchLogEntity(sourceId = source.id, at = now, ok = true, message = status, itemCount = 0, newCount = 0))
                return emptyList<Long>() to true
            }
            is FetchOutcome.Failed -> {
                dao.setFetchState(
                    source.id, source.etag, source.lastModified, now, outcome.message,
                    ok = false, itemCount = source.lastItemCount, failCount = source.failCount + 1,
                )
                dao.insertLog(FetchLogEntity(sourceId = source.id, at = now, ok = false, message = outcome.message, itemCount = 0, newCount = 0))
                return emptyList<Long>() to false
            }
        }
    }

    private val checkDir = File(context.filesDir, "factchecks").apply { mkdirs() }

    /** A story check saved earlier for this article, if any. */
    fun storedCheck(articleId: Long): FactCheck? = runCatching {
        ConfigJson.json.decodeFromString(FactCheck.serializer(), File(checkDir, "$articleId.json").readText())
    }.getOrNull()

    /** What a story check needs and does not have; null when it can run. */
    fun checkBlocker(): String? = when {
        !models.flow.value.enabled -> "Set up a model in Settings, Models to check stories."
        !search.flow.value.enabled || !secrets.has(SecretStore.SEARCH_API_KEY) -> "Save a search key in Settings, Search to check stories."
        else -> null
    }

    /**
     * Compares an article's main claims with other coverage found by web
     * search. Costs two model requests and one search per claim, all logged.
     * The result is saved and returned; failures are returned as a result
     * with an error, not thrown.
     */
    suspend fun checkStory(articleId: Long): FactCheck = withContext(Dispatchers.IO) {
        val article = dao.articlesByIds(listOf(articleId)).firstOrNull()
            ?: return@withContext FactCheck("", System.currentTimeMillis(), "", emptyList(), error = "That article is no longer stored")
        val settings = models.flow.value
        fun cannot(why: String) = FactCheck(article.url, System.currentTimeMillis(), settings.model, emptyList(), error = why)
        val llm = client() ?: return@withContext cannot("No model is set up")
        val searcher = searchClient() ?: return@withContext cannot("No search key is saved")

        // The article's own text if it can be fetched and read, otherwise the headline and summary.
        val text = runCatching { http.get(article.url, mapOf("User-Agent" to DEFAULT_USER_AGENT)) }.getOrNull()
            ?.takeIf { it.code in 200..299 }
            ?.let { runCatching { Reader.text(it.body, article.url) }.getOrNull() }
            ?: listOfNotNull(article.title, article.summary).joinToString("\n")

        val trustBySite = HashMap<String, TrustLevel>()
        for (s in dao.sourcesNow()) Urls.host(s.homeUrl)?.let { trustBySite[Urls.site(it)] = s.trustLevel() }

        val result = FactChecker(
            llm,
            { query ->
                val now = System.currentTimeMillis()
                if (searchesLeftToday(now) <= 0) {
                    SearchOutcome(emptyList(), "", "Your daily search limit is reached")
                } else {
                    countSearch(now)
                    searcher.search(query, now)
                }
            },
            settings,
            { site -> trustBySite[site] ?: TrustLevel.UNRATED },
        ).check(article.title, text, article.url)

        logCalls(result.calls, article.sourceId, article.url, settings)
        val searchSettings = search.flow.value
        for ((query, outcome) in result.searches) {
            logDao.insertCall(
                ModelCallEntity(
                    at = System.currentTimeMillis(), task = "search", sourceId = article.sourceId,
                    target = "Story check: ${article.title}",
                    endpoint = Urls.host(searchSettings.endpoint) ?: searchSettings.endpoint, model = "web search",
                    systemPrompt = "${searchSettings.method.uppercase()} ${searchSettings.endpoint}\n${searchSettings.request}",
                    userPrompt = query, response = outcome.raw.take(20_000),
                    outcome = outcome.error?.let { "Error: $it" } ?: "${outcome.results.size} results",
                    accepted = outcome.error == null, durationMs = 0, promptTokens = null, completionTokens = null,
                )
            )
        }
        logDao.pruneCalls()
        runCatching { File(checkDir, "$articleId.json").writeText(ConfigJson.json.encodeToString(FactCheck.serializer(), result.check)) }
        result.check
    }

    private fun searchClient(): SearchClient? {
        val settings = search.flow.value
        if (!settings.enabled) return null
        val key = secrets.get(SecretStore.SEARCH_API_KEY) ?: return null
        return SearchClient(http, poster, settings, key)
    }

    /** Searches allowed today under the daily limit; a very large number when there is none. */
    private fun searchesLeftToday(now: Long): Int {
        val limit = search.flow.value.dailyLimit
        if (limit <= 0) return Int.MAX_VALUE
        val day = now / DAY
        val used = if (prefs.getLong("searchDay", -1) == day) prefs.getInt("searchCount", 0) else 0
        return (limit - used).coerceAtLeast(0)
    }

    private fun countSearch(now: Long) {
        val day = now / DAY
        val used = if (prefs.getLong("searchDay", -1) == day) prefs.getInt("searchCount", 0) else 0
        prefs.edit().putLong("searchDay", day).putInt("searchCount", used + 1).apply()
    }

    /**
     * Runs the web search of each topic that has one switched on and is due
     * ([force] runs them all). Results from blocked sites are dropped; a site
     * not seen before is added as an unrated, unfetched source so it can be
     * trusted, blocked or followed. Returns (searches run, new article ids).
     */
    suspend fun runSearches(force: Boolean): Pair<Int, List<Long>> = withContext(Dispatchers.IO) {
        val client = searchClient() ?: return@withContext 0 to emptyList()
        val settings = search.flow.value
        val now = System.currentTimeMillis()
        val newIds = ArrayList<Long>()
        var ran = 0
        for (topic in ranking.flow.value.topics) {
            if (!topic.search || topic.name.isBlank()) continue
            val stamp = "searched:${topic.name}"
            val every = topic.searchEveryHours.coerceAtLeast(1) * 3_600_000L
            if (!force && now - prefs.getLong(stamp, 0L) < every) continue
            if (searchesLeftToday(now) <= 0) break

            val started = System.nanoTime()
            val outcome = client.search(topic.query, now)
            countSearch(now)
            prefs.edit().putLong(stamp, now).apply()
            ran++

            val bySite = HashMap<String, SourceEntity>()
            for (s in dao.sourcesNow()) {
                Urls.host(s.homeUrl)?.let { bySite.putIfAbsent(Urls.site(it), s) }
                Urls.host(s.recipe().url)?.let { bySite.putIfAbsent(Urls.site(it), s) }
            }
            var added = 0
            var blocked = 0
            for (result in outcome.results) {
                val host = Urls.host(result.url) ?: continue
                val site = Urls.site(host)
                val source = bySite[site] ?: run {
                    val home = Urls.origin(result.url) ?: result.url
                    val id = save(null, SourceConfig(name = site, homeUrl = home, recipe = Recipe(FetchKind.NONE, home), trust = TrustLevel.UNRATED))
                    dao.source(id)?.also { bySite[site] = it }
                } ?: continue
                if (source.trustLevel() == TrustLevel.BLOCKED) {
                    blocked++
                    continue
                }
                val row = ArticleEntity(
                    sourceId = source.id,
                    url = result.url,
                    canonicalUrl = Urls.canonical(result.url),
                    title = result.title,
                    summary = result.snippet,
                    publishedAt = result.publishedAt,
                    fetchedAt = now,
                    tags = Topics.SEARCH_TAG_PREFIX + topic.name.trim(),
                )
                val id = dao.insertArticles(listOf(row)).firstOrNull() ?: -1L
                if (id != -1L) {
                    newIds.add(id)
                    added++
                }
            }
            val summary = outcome.error?.let { "Error: $it" }
                ?: ("${outcome.results.size} results, $added new" + if (blocked > 0) ", $blocked from blocked sites dropped" else "")
            logDao.insertCall(
                ModelCallEntity(
                    at = now,
                    task = "search",
                    sourceId = null,
                    target = "Topic: ${topic.name}",
                    endpoint = Urls.host(settings.endpoint) ?: settings.endpoint,
                    model = "web search",
                    systemPrompt = "${settings.method.uppercase()} ${settings.endpoint}\n${settings.request}",
                    userPrompt = topic.query,
                    response = outcome.raw.take(20_000),
                    outcome = summary,
                    accepted = outcome.error == null,
                    durationMs = (System.nanoTime() - started) / 1_000_000,
                    promptTokens = null,
                    completionTokens = null,
                )
            )
            logDao.pruneCalls()
        }
        ran to newIds
    }

    /** Runs one search without storing anything, to check the connector settings. */
    suspend fun testSearch(query: String): SearchOutcome = withContext(Dispatchers.IO) {
        val settings = search.flow.value
        SearchClient(http, poster, settings, secrets.get(SecretStore.SEARCH_API_KEY).orEmpty()).search(query)
    }

    /** The given articles as the ranker sees them, leaving out blocked sources. */
    suspend fun rankItems(ids: List<Long>): List<RankItem> {
        if (ids.isEmpty()) return emptyList()
        val sources = dao.sourcesNow().associateBy { it.id }
        return ids.take(500).chunked(200).flatMap { dao.articlesByIds(it) }.mapNotNull { a ->
            val source = sources[a.sourceId] ?: return@mapNotNull null
            if (source.trustLevel() == TrustLevel.BLOCKED) return@mapNotNull null
            RankItem(
                id = a.id, title = a.title, url = a.url, sourceId = source.id, sourceName = source.name,
                trust = source.trustLevel(), timestamp = minOf(a.publishedAt ?: a.fetchedAt, a.fetchedAt), read = a.read,
                summary = a.summary, tags = a.tags?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
            )
        }
    }

    suspend fun exportConfig(ranking: RankingConfig, browser: BrowserSettings, publish: PublishSettings): String =
        ConfigJson.encode(AppConfig(ranking = ranking, models = models.flow.value, search = search.flow.value, publish = publish, browser = browser, sources = dao.sourcesNow().sortedBy { it.name.lowercase() }.map { it.toConfig() }))

    /**
     * Applies the sources in an imported file: one whose recipe address
     * matches an existing source updates it, any other is added. Sources
     * missing from the file are left alone. Returns (updated, added).
     */
    suspend fun importSources(sources: List<SourceConfig>): Pair<Int, Int> {
        val byUrl = dao.sourcesNow().associateBy { it.recipe().url }
        var updated = 0
        var added = 0
        for (config in sources) {
            val existing = byUrl[config.recipe.url]
            save(existing?.id, config)
            if (existing == null) added++ else updated++
        }
        return updated to added
    }
}

/** A settings object kept as a readable JSON file in the app's storage. */
class JsonStore<T>(
    context: Context,
    fileName: String,
    private val serializer: KSerializer<T>,
    private val default: T,
) {
    private val file = File(context.filesDir, fileName)
    private val state = MutableStateFlow(load())
    val flow: StateFlow<T> = state

    private fun load(): T =
        runCatching { ConfigJson.json.decodeFromString(serializer, file.readText()) }.getOrDefault(default)

    @Synchronized
    fun set(value: T) {
        state.value = value
        runCatching { file.writeText(ConfigJson.json.encodeToString(serializer, value)) }
    }

    fun update(change: (T) -> T) = set(change(state.value))
}
