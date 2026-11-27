package com.pnixon.newz.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pnixon.newz.NewzApp
import com.pnixon.newz.data.ArticleEntity
import com.pnixon.newz.data.FetchLogEntity
import com.pnixon.newz.data.FilterListState
import com.pnixon.newz.data.ModelCallEntity
import com.pnixon.newz.data.ModelCallSummary
import com.pnixon.newz.data.RecipeHistoryEntity
import com.pnixon.newz.data.SecretStore
import com.pnixon.newz.data.SourceEntity
import com.pnixon.newz.data.TestResult
import com.pnixon.newz.data.trustLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import newz.core.AdBlocker
import newz.core.BrowserSettings
import newz.core.ConfigJson
import newz.core.FilterList
import newz.core.Discovery
import newz.core.RankItem
import newz.core.RankResult
import newz.core.Ranker
import newz.core.ModelSettings
import newz.core.AnsyblIdentity
import newz.core.CheckSummary
import newz.core.FactCheck
import newz.core.LlmRequest
import newz.core.Learner
import newz.core.OpenEvent
import newz.core.Suggestion
import newz.core.TrackRecord
import newz.core.Urls
import newz.core.PublishSettings
import newz.core.RankingConfig
import newz.core.Scored
import newz.core.SharedItem
import newz.core.Topics
import newz.core.SearchOutcome
import newz.core.SearchSettings
import newz.core.Recipe
import newz.core.WriteResult
import newz.core.SourceConfig
import newz.core.TrustLevel

data class FrontState(
    val result: RankResult = RankResult(emptyList(), emptyList()),
    val articles: Map<Long, ArticleEntity> = emptyMap(),
    val loaded: Boolean = false,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as NewzApp).container
    private val repository = container.repository

    val config: StateFlow<RankingConfig> = container.config.flow

    val sources: StateFlow<List<SourceEntity>> =
        repository.sources.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val models: StateFlow<ModelSettings> = container.models.flow

    private val _hasApiKey = MutableStateFlow(container.secrets.has(SecretStore.MODEL_API_KEY))
    val hasApiKey: StateFlow<Boolean> = _hasApiKey

    val modelCalls: StateFlow<List<ModelCallSummary>> =
        repository.modelCalls().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val publish: StateFlow<PublishSettings> = container.publish.flow
    val shared: StateFlow<List<SharedItem>> = container.shared.flow
    val publishStatus: StateFlow<String> = container.publisher.status
    val publicKey: StateFlow<String?> = container.publisher.publicKey

    private val _hasPublishSecret = MutableStateFlow(container.secrets.has(SecretStore.PUBLISH_SECRET_KEY))
    val hasPublishSecret: StateFlow<Boolean> = _hasPublishSecret

    private val _publishing = MutableStateFlow(false)
    val publishing: StateFlow<Boolean> = _publishing

    val onDeviceStatus: StateFlow<String> = container.onDevice.status
    val onDeviceAvailable: StateFlow<Boolean> = container.onDevice.available
    val onDeviceDownloadable: StateFlow<Boolean> = container.onDevice.downloadable

    val opens: StateFlow<List<OpenEvent>> = container.reading.flow

    /** Story checks loaded or run in this session, by article id. */
    private val _checks = MutableStateFlow<Map<Long, FactCheck>>(emptyMap())
    val checks: StateFlow<Map<Long, FactCheck>> = _checks

    private val _checking = MutableStateFlow<Set<Long>>(emptySet())
    val checking: StateFlow<Set<Long>> = _checking

    val search: StateFlow<SearchSettings> = container.search.flow

    private val _hasSearchKey = MutableStateFlow(container.secrets.has(SecretStore.SEARCH_API_KEY))
    val hasSearchKey: StateFlow<Boolean> = _hasSearchKey

    val browser: StateFlow<BrowserSettings> = container.browser.flow
    val blocker: StateFlow<AdBlocker> = container.filters.blocker
    val filterRevision: StateFlow<Int> = container.filters.revision

    private val _updatingFilters = MutableStateFlow(false)
    val updatingFilters: StateFlow<Boolean> = _updatingFilters

    /** Outcomes of the story checks run so far, by article id. They feed the ranking. */
    private val summaries = MutableStateFlow<Map<Long, CheckSummary>>(emptyMap())

    /** The time scores are calculated for. Moved forward on refresh and once a minute. */
    private val clock = MutableStateFlow(System.currentTimeMillis())

    val front: StateFlow<FrontState> =
        combine(repository.articles(), repository.sources, container.config.flow, clock, summaries) { articles, sources, config, now, checked ->
            val byId = sources.associateBy { it.id }
            val items = articles.mapNotNull { a ->
                val source = byId[a.sourceId] ?: return@mapNotNull null
                RankItem(
                    id = a.id,
                    title = a.title,
                    url = a.url,
                    sourceId = source.id,
                    sourceName = source.name,
                    trust = source.trustLevel(),
                    // A source cannot publish after the app saw the article.
                    timestamp = minOf(a.publishedAt ?: a.fetchedAt, a.fetchedAt),
                    read = a.read,
                    summary = a.summary,
                    tags = a.tags?.split("\n")?.filter { it.isNotBlank() } ?: emptyList(),
                    storyCheck = checked[a.id],
                )
            }
            FrontState(Ranker.rank(items, config, now), articles.associateBy { it.id }, loaded = true)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, FrontState())

    /** Topic changes suggested from what you opened. Empty when learning is off. */
    val suggestions: StateFlow<List<Suggestion>> =
        combine(container.reading.flow, container.dismissed.flow, container.config.flow, front) { opened, dismissed, config, state ->
            if (!config.learnFromReading) emptyList()
            else Learner.suggest(opened, state.articles.values.map { it.title }, config.topics, dismissed.toSet(), System.currentTimeMillis())
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    init {
        viewModelScope.launch {
            repository.seedIfNeeded()
            refresh(force = false, announce = false)
        }
        viewModelScope.launch { summaries.value = runCatching { repository.checkSummaries() }.getOrDefault(emptyMap()) }
        // First launch downloads the filter lists; later launches only when they are due.
        updateFilters(force = false, announce = false)
    }

    fun tick() {
        clock.value = System.currentTimeMillis()
    }

    fun say(message: String) {
        _messages.tryEmit(message)
    }

    fun refresh(force: Boolean = true, announce: Boolean = true, onlySourceId: Long? = null) {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            try {
                val summary = repository.refresh(force, onlySourceId)
                tick()
                if (announce) {
                    val failed = if (summary.failures > 0) ", ${summary.failures} failed" else ""
                    val repaired = if (summary.repaired > 0) ", ${summary.repaired} repaired by the model" else ""
                    val searched = if (summary.searches > 0) ", ${summary.searches} topic searches run" else ""
                    say("${summary.newArticles} new from ${summary.sourcesChecked} sources$failed$repaired$searched")
                }
            } catch (e: Exception) {
                say("Refresh failed: ${e.message}")
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun setRead(articleId: Long, read: Boolean) {
        viewModelScope.launch { repository.setRead(articleId, read) }
    }

    fun setTrust(sourceId: Long, trust: TrustLevel) {
        viewModelScope.launch { repository.setTrust(sourceId, trust) }
    }

    fun updateConfig(change: (RankingConfig) -> RankingConfig) = container.config.update(change)

    fun logFor(sourceId: Long): Flow<List<FetchLogEntity>> = repository.logFor(sourceId)

    suspend fun source(id: Long): SourceEntity? = repository.source(id)

    suspend fun discover(url: String): Result<Discovery> = runCatching { repository.discover(url) }

    suspend fun test(config: SourceConfig): TestResult =
        runCatching { repository.test(config) }.getOrElse { TestResult(emptyList(), it.message ?: "Test failed") }

    fun saveSource(id: Long?, config: SourceConfig, done: () -> Unit) {
        viewModelScope.launch {
            val savedId = repository.save(id, config)
            done()
            refresh(force = true, announce = true, onlySourceId = savedId)
        }
    }

    fun deleteSource(id: Long, done: () -> Unit) {
        viewModelScope.launch {
            repository.delete(id)
            done()
        }
    }

    suspend fun exportConfig(): String = repository.exportConfig(config.value, browser.value, publish.value)

    fun importConfig(text: String) {
        viewModelScope.launch {
            try {
                val imported = ConfigJson.decode(text)
                container.config.set(imported.ranking)
                container.models.set(imported.models)
                container.search.set(imported.search)
                container.publish.set(imported.publish)
                container.browser.set(imported.browser)
                container.filters.reload()
                val (updated, added) = repository.importSources(imported.sources)
                say("Imported settings, $updated sources updated, $added added")
                refresh(force = false, announce = false)
            } catch (e: Exception) {
                say("That file could not be read: ${e.message?.take(120)}")
            }
        }
    }

    fun saveModels(settings: ModelSettings) = container.models.set(settings)

    /** Stores the key, or removes it when [value] is blank. */
    fun setApiKey(value: String?) {
        val ok = runCatching { container.secrets.set(SecretStore.MODEL_API_KEY, value) }.isSuccess
        _hasApiKey.value = container.secrets.has(SecretStore.MODEL_API_KEY)
        say(
            when {
                !ok -> "The key could not be stored on this phone"
                value.isNullOrBlank() -> "Key removed"
                else -> "Key saved on this phone"
            }
        )
    }

    suspend fun listModels(): Result<List<String>> = runCatching { repository.listModels() }

    suspend fun testConnection(): String =
        runCatching { repository.testConnection() }.getOrElse { it.message ?: "The test failed" }

    suspend fun modelCall(id: Long): ModelCallEntity? = repository.modelCall(id)

    fun clearModelCalls() {
        viewModelScope.launch { repository.clearModelCalls() }
    }

    /**
     * Has the model write a recipe for [pageUrl]. For an existing source the
     * model is also shown [current] and that source's recent headlines.
     */
    suspend fun askModel(pageUrl: String, sourceId: Long?, current: Recipe, rendered: Boolean = false): WriteResult =
        runCatching {
            val repair = sourceId?.let { repository.repairContext(it, current) }
            repository.askModel(pageUrl, sourceId, repair, rendered)
        }.getOrElse { WriteResult(null, emptyList(), emptyList(), it.message ?: "The request failed") }

    fun historyFor(sourceId: Long): Flow<List<RecipeHistoryEntity>> = repository.historyFor(sourceId)

    suspend fun restoreRecipe(sourceId: Long, historyId: Long): Boolean =
        runCatching { repository.restoreRecipe(sourceId, historyId) }.getOrDefault(false)

    /** Saves browser settings and rebuilds the blocker from them. */
    fun saveBrowser(settings: BrowserSettings) {
        container.browser.set(settings)
        viewModelScope.launch { container.filters.reload() }
    }

    fun filterState(list: FilterList): FilterListState = container.filters.state(list)

    fun updateFilters(force: Boolean, announce: Boolean = true) {
        if (_updatingFilters.value) return
        _updatingFilters.value = true
        viewModelScope.launch {
            val result = runCatching { container.filters.update(force) }
            _updatingFilters.value = false
            if (announce) {
                say(
                    result.fold(
                        onSuccess = { (fetched, failed) -> "$fetched lists downloaded" + if (failed > 0) ", $failed failed" else "" },
                        onFailure = { "Could not update the lists: ${it.message}" },
                    )
                )
            }
        }
    }

    fun saveSearch(settings: SearchSettings) = container.search.set(settings)

    fun setSearchKey(value: String?) {
        val ok = runCatching { container.secrets.set(SecretStore.SEARCH_API_KEY, value) }.isSuccess
        _hasSearchKey.value = container.secrets.has(SecretStore.SEARCH_API_KEY)
        say(
            when {
                !ok -> "The key could not be stored on this phone"
                value.isNullOrBlank() -> "Key removed"
                else -> "Key saved on this phone"
            }
        )
    }

    suspend fun testSearch(query: String): SearchOutcome =
        runCatching { repository.testSearch(query) }.getOrElse { SearchOutcome(emptyList(), "", it.message ?: "The test failed") }

    /** Runs every topic's web search now, whatever its schedule. Each one is a paid request. */
    fun runSearchesNow() {
        viewModelScope.launch {
            val result = runCatching { repository.runSearches(force = true) }
            tick()
            say(
                result.fold(
                    onSuccess = { (ran, found) -> if (ran == 0) "No searches ran. Check the key and that a topic has search switched on." else "$ran searches run, ${found.size} new articles" },
                    onFailure = { "Search failed: ${it.message}" },
                )
            )
        }
    }

    /** Loads a saved story check for the article into [checks], if there is one. */
    fun loadCheck(articleId: Long) {
        if (articleId in _checks.value) return
        viewModelScope.launch {
            val stored = kotlinx.coroutines.withContext(Dispatchers.IO) { repository.storedCheck(articleId) }
            if (stored != null) _checks.value = _checks.value + (articleId to stored)
        }
    }

    fun checkBlocker(): String? = repository.checkBlocker()

    /** Runs a story check. It costs model requests and searches, so it only runs when asked. */
    fun checkStory(articleId: Long) {
        if (articleId in _checking.value) return
        _checking.value = _checking.value + articleId
        viewModelScope.launch {
            val result = runCatching { repository.checkStory(articleId) }.getOrElse {
                FactCheck("", System.currentTimeMillis(), "", emptyList(), error = it.message ?: "The check failed")
            }
            _checks.value = _checks.value + (articleId to result)
            CheckSummary.of(result)?.let { summaries.value = summaries.value + (articleId to it) }
            _checking.value = _checking.value - articleId
        }
    }

    fun isShared(url: String): Boolean = shared.value.any { it.url == url }

    /** Adds the article to your published feed, or removes it, then republishes if set to. */
    fun toggleShare(scored: Scored, article: ArticleEntity?) {
        val item = scored.item
        val current = container.shared.flow.value
        val next = if (current.any { it.url == item.url }) {
            current.filter { it.url != item.url }
        } else {
            current + SharedItem(
                id = java.util.UUID.randomUUID().toString(),
                url = item.url,
                title = item.title,
                summary = article?.summary,
                sourceName = item.sourceName,
                imageUrl = article?.imageUrl,
                tags = (scored.topics.map { it.topic } + item.tags.filter { !it.startsWith(Topics.SEARCH_TAG_PREFIX) }).distinct().take(8),
                sharedAt = System.currentTimeMillis(),
            )
        }
        container.shared.set(next)
        afterShareChange(if (next.size > current.size) "Added to your feed" else "Removed from your feed")
    }

    fun unshare(url: String) {
        container.shared.set(container.shared.flow.value.filter { it.url != url })
        afterShareChange("Removed from your feed")
    }

    private fun afterShareChange(message: String) {
        val settings = publish.value
        if (settings.autoPublish && settings.ready && _hasPublishSecret.value) {
            publishNow("$message.")
        } else {
            say(if (settings.ready) message else "$message. It is not online until publishing is set up in Settings, Publish.")
        }
    }

    fun publishNow(prefix: String = "") {
        if (_publishing.value) return
        _publishing.value = true
        viewModelScope.launch {
            val ok = runCatching { container.publisher.publish() }.getOrDefault(false)
            _publishing.value = false
            say(listOf(prefix, if (ok) "Feed published" else container.publisher.status.value).filter { it.isNotBlank() }.joinToString(" "))
        }
    }

    fun savePublish(settings: PublishSettings) = container.publish.set(settings)

    fun setPublishSecret(value: String?) {
        val ok = runCatching { container.secrets.set(SecretStore.PUBLISH_SECRET_KEY, value) }.isSuccess
        _hasPublishSecret.value = container.secrets.has(SecretStore.PUBLISH_SECRET_KEY)
        say(
            when {
                !ok -> "The secret could not be stored on this phone"
                value.isNullOrBlank() -> "Secret removed"
                else -> "Secret saved on this phone"
            }
        )
    }

    /** Makes sure a signing identity exists, creating one on first use. */
    fun ensureIdentity() {
        if (publicKey.value == null) runCatching { container.publisher.identity() }
    }

    fun identityFingerprint(): String? = runCatching { container.publisher.identity().fingerprint }.getOrNull()

    fun privateKeyForBackup(): String? = runCatching { container.publisher.identity().privateKeyString }.getOrNull()

    fun newIdentity() {
        runCatching { container.publisher.newIdentity() }
        say("New identity created. Subscribers will see a different author key.")
    }

    fun importIdentity(privateKey: String) {
        say(
            if (runCatching { container.publisher.importIdentity(privateKey) }.isSuccess) "Identity imported"
            else "That is not an Ed25519 private key in ed25519:BASE64 form"
        )
    }

    /** Remembers that an article was opened, if learning is on. Only its headline, topics and source are kept. */
    fun recordOpen(articleId: Long) {
        if (!config.value.learnFromReading) return
        val state = front.value
        val scored = state.result.visible.firstOrNull { it.item.id == articleId }
            ?: state.result.hidden.firstOrNull { it.item.id == articleId }
            ?: return
        val event = OpenEvent(System.currentTimeMillis(), scored.item.title, scored.topics.map { it.topic }, scored.item.sourceName)
        container.reading.update { (it + event).takeLast(1000) }
    }

    fun acceptSuggestion(suggestion: Suggestion) {
        container.config.update { it.copy(topics = Learner.apply(suggestion, it.topics)) }
        container.dismissed.update { (it + suggestion.key).distinct() }
        say("Done. You can change it under Topics.")
    }

    fun dismissSuggestion(suggestion: Suggestion) {
        container.dismissed.update { (it + suggestion.key).distinct().takeLast(500) }
    }

    fun forgetReading() {
        container.reading.set(emptyList())
        container.dismissed.set(emptyList())
        say("Reading history cleared")
    }

    /** How the site's articles fared in the story checks run so far; null if none were checked. */
    suspend fun trackRecord(homeUrl: String): TrackRecord? {
        val site = Urls.host(homeUrl)?.let { Urls.site(it) } ?: return null
        return runCatching { repository.trackRecords()[site] }.getOrNull()
    }

    fun checkOnDevice() {
        viewModelScope.launch { container.onDevice.refresh() }
    }

    fun downloadOnDevice() {
        viewModelScope.launch { container.onDevice.download() }
    }

    /** Sends the built-in model a one-line prompt and returns what it said or why it failed. */
    suspend fun testOnDevice(): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        container.onDevice.refresh()
        runCatching {
            container.onDevice.llm.complete(LlmRequest("", "You are a connection test.", "Reply with the single word: ok", 0.0, 20)).text.trim().take(120)
        }.fold(onSuccess = { "It works. The on-device model replied: $it" }, onFailure = { it.message ?: "The test failed" })
    }
}
