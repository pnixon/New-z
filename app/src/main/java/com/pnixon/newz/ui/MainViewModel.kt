package com.pnixon.newz.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pnixon.newz.NewzApp
import com.pnixon.newz.data.ArticleEntity
import com.pnixon.newz.data.FetchLogEntity
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
import newz.core.ConfigJson
import newz.core.Discovery
import newz.core.RankItem
import newz.core.RankResult
import newz.core.Ranker
import newz.core.ModelSettings
import newz.core.RankingConfig
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

    /** The time scores are calculated for. Moved forward on refresh and once a minute. */
    private val clock = MutableStateFlow(System.currentTimeMillis())

    val front: StateFlow<FrontState> =
        combine(repository.articles(), repository.sources, container.config.flow, clock) { articles, sources, config, now ->
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
                )
            }
            FrontState(Ranker.rank(items, config, now), articles.associateBy { it.id }, loaded = true)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, FrontState())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    init {
        viewModelScope.launch {
            repository.seedIfNeeded()
            refresh(force = false, announce = false)
        }
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
                    say("${summary.newArticles} new from ${summary.sourcesChecked} sources$failed$repaired")
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

    suspend fun exportConfig(): String = repository.exportConfig(config.value)

    fun importConfig(text: String) {
        viewModelScope.launch {
            try {
                val imported = ConfigJson.decode(text)
                container.config.set(imported.ranking)
                container.models.set(imported.models)
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
    suspend fun askModel(pageUrl: String, sourceId: Long?, current: Recipe): WriteResult =
        runCatching {
            val repair = sourceId?.let { repository.repairContext(it, current) }
            repository.askModel(pageUrl, sourceId, repair)
        }.getOrElse { WriteResult(null, emptyList(), emptyList(), it.message ?: "The request failed") }

    fun historyFor(sourceId: Long): Flow<List<RecipeHistoryEntity>> = repository.historyFor(sourceId)

    suspend fun restoreRecipe(sourceId: Long, historyId: Long): Boolean =
        runCatching { repository.restoreRecipe(sourceId, historyId) }.getOrDefault(false)
}
