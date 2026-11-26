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
import newz.core.AppConfig
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

data class RefreshSummary(val sourcesChecked: Int, val newArticles: Int, val failures: Int, val repaired: Int = 0)

data class TestResult(val items: List<RawItem>, val error: String?)

class Repository(
    private val context: Context,
    private val dao: NewsDao,
    private val logDao: LogDao,
    private val http: HttpFetcher,
    private val poster: HttpPoster,
    private val models: JsonStore<ModelSettings>,
    private val secrets: SecretStore,
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
        if (!models.flow.value.enabled) {
            return@withContext found ?: throw DiscoveryException("$plainError No model is set up to try instead.")
        }

        val pageUrl = found?.source?.recipe?.url ?: url.trim().let { if ("://" in it) it else "https://$it" }
        val written = askModel(pageUrl, sourceId = null, repair = null)
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
    suspend fun askModel(pageUrl: String, sourceId: Long?, repair: RepairContext?): WriteResult = withContext(Dispatchers.IO) {
        val settings = models.flow.value
        val llm = client()
            ?: return@withContext WriteResult(null, emptyList(), emptyList(), "No model is set up. Choose one on the Models tab.")
        val page = try {
            http.get(pageUrl, mapOf("User-Agent" to DEFAULT_USER_AGENT))
        } catch (e: Exception) {
            return@withContext WriteResult(null, emptyList(), emptyList(), "Could not fetch the page: ${e.message ?: e.javaClass.simpleName}")
        }
        if (page.code !in 200..299) {
            return@withContext WriteResult(null, emptyList(), emptyList(), "The page answered HTTP ${page.code}, so there was nothing to send to a model")
        }
        val result = RecipeWriter(llm, settings).write(pageUrl, page.body, repair)
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
        val result = askModel(current.url, source.id, context)
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

    /** Runs a recipe once without saving anything. */
    suspend fun test(config: SourceConfig): TestResult = withContext(Dispatchers.IO) {
        when (val outcome = SourceFetcher.fetch(config, http)) {
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
                s.enabled && s.trustLevel() != TrustLevel.BLOCKED &&
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
        RefreshSummary(due.size, results.sumOf { it.first }, results.count { !it.second }, repaired)
    }

    /** Returns the number of new articles and whether the fetch succeeded. */
    private suspend fun refreshOne(source: SourceEntity): Pair<Int, Boolean> {
        val now = System.currentTimeMillis()
        val outcome = withContext(Dispatchers.IO) {
            SourceFetcher.fetch(source.toConfig(), http, source.etag, source.lastModified, now)
        }
        when (outcome) {
            is FetchOutcome.Success -> {
                val rows = outcome.items.map { it.toEntity(source.id, now) }
                val added = dao.insertArticles(rows).count { it != -1L }
                val empty = rows.isEmpty()
                val status = if (empty) "No articles found" else "${rows.size} articles, $added new"
                dao.setFetchState(
                    source.id, outcome.etag, outcome.lastModified, now, status,
                    ok = !empty, itemCount = rows.size, failCount = if (empty) source.failCount + 1 else 0,
                )
                dao.insertLog(FetchLogEntity(sourceId = source.id, at = now, ok = !empty, message = status, itemCount = rows.size, newCount = added))
                return added to !empty
            }
            FetchOutcome.NotModified -> {
                val status = "Unchanged since last check"
                dao.setFetchState(
                    source.id, source.etag, source.lastModified, now, status,
                    ok = true, itemCount = source.lastItemCount, failCount = source.failCount,
                )
                dao.insertLog(FetchLogEntity(sourceId = source.id, at = now, ok = true, message = status, itemCount = 0, newCount = 0))
                return 0 to true
            }
            is FetchOutcome.Failed -> {
                dao.setFetchState(
                    source.id, source.etag, source.lastModified, now, outcome.message,
                    ok = false, itemCount = source.lastItemCount, failCount = source.failCount + 1,
                )
                dao.insertLog(FetchLogEntity(sourceId = source.id, at = now, ok = false, message = outcome.message, itemCount = 0, newCount = 0))
                return 0 to false
            }
        }
    }

    suspend fun exportConfig(ranking: RankingConfig, browser: BrowserSettings): String =
        ConfigJson.encode(AppConfig(ranking = ranking, models = models.flow.value, browser = browser, sources = dao.sourcesNow().sortedBy { it.name.lowercase() }.map { it.toConfig() }))

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
