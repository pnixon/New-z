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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import newz.core.AppConfig
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

data class RefreshSummary(val sourcesChecked: Int, val newArticles: Int, val failures: Int)

data class TestResult(val items: List<RawItem>, val error: String?)

class Repository(
    private val context: Context,
    private val dao: NewsDao,
    private val http: HttpFetcher,
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
    suspend fun save(id: Long?, config: SourceConfig): Long {
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
        val recipeChanged = existing.recipeJson != recipeJson
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
        dao.deleteSource(sourceId)
    }

    suspend fun discover(url: String): Discovery = withContext(Dispatchers.IO) {
        SourceDiscovery.discover(url, http)
    }

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
        RefreshSummary(due.size, results.sumOf { it.first }, results.count { !it.second })
    }

    /** Returns the number of new articles and whether the fetch succeeded. */
    private suspend fun refreshOne(source: SourceEntity): Pair<Int, Boolean> {
        val now = System.currentTimeMillis()
        val outcome = withContext(Dispatchers.IO) {
            SourceFetcher.fetch(source.toConfig(), http, source.etag, source.lastModified, now)
        }
        when (outcome) {
            is FetchOutcome.Success -> {
                val rows = outcome.items.map { item ->
                    ArticleEntity(
                        sourceId = source.id,
                        url = item.url,
                        canonicalUrl = Urls.canonical(item.url),
                        title = item.title,
                        summary = item.summary,
                        imageUrl = item.imageUrl,
                        publishedAt = item.publishedAt,
                        fetchedAt = now,
                    )
                }
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

    suspend fun exportConfig(ranking: RankingConfig): String =
        ConfigJson.encode(AppConfig(ranking = ranking, sources = dao.sourcesNow().sortedBy { it.name.lowercase() }.map { it.toConfig() }))

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

/** The ranking settings, kept as a readable JSON file in the app's storage. */
class ConfigStore(context: Context) {
    private val file = File(context.filesDir, "ranking.json")
    private val state = MutableStateFlow(load())
    val flow: StateFlow<RankingConfig> = state

    private fun load(): RankingConfig =
        runCatching { ConfigJson.decodeRanking(file.readText()) }.getOrDefault(RankingConfig())

    @Synchronized
    fun set(config: RankingConfig) {
        state.value = config
        runCatching { file.writeText(ConfigJson.encodeRanking(config)) }
    }

    fun update(change: (RankingConfig) -> RankingConfig) = set(change(state.value))
}
