package com.pnixon.newz.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import newz.core.AdBlocker
import newz.core.BrowserSettings
import newz.core.DEFAULT_USER_AGENT
import newz.core.FilterList
import newz.core.FilterSource
import newz.core.HttpFetcher
import java.io.File
import java.security.MessageDigest

/** What is known about one filter list on this phone. */
data class FilterListState(val downloadedAt: Long?, val error: String?)

/**
 * Downloads the filter lists, keeps them as files, and builds the blocker
 * from them plus the built-in list and the user's own rules.
 */
class FilterListStore(
    context: Context,
    private val http: HttpFetcher,
    private val settings: JsonStore<BrowserSettings>,
) {
    private val dir = File(context.filesDir, "filters").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("filters", Context.MODE_PRIVATE)
    private val lock = Mutex()

    private val _blocker = MutableStateFlow(AdBlocker.build(listOf(AdBlocker.BUILT_IN)))
    val blocker: StateFlow<AdBlocker> = _blocker

    /** Bumped whenever a list's download state changes, so screens re-read it. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    private fun key(list: FilterList): String =
        MessageDigest.getInstance("SHA-1").digest(list.url.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

    private fun file(list: FilterList) = File(dir, key(list) + ".txt")

    fun state(list: FilterList): FilterListState {
        val k = key(list)
        return FilterListState(
            downloadedAt = prefs.getLong("at:$k", 0L).takeIf { it > 0 && file(list).exists() },
            error = prefs.getString("error:$k", null),
        )
    }

    /** Rebuilds the blocker from what is on disk. Custom rules come first, so they are the ones reported. */
    suspend fun reload() = withContext(Dispatchers.Default) {
        lock.withLock {
            val current = settings.flow.value
            val sources = ArrayList<FilterSource>()
            if (current.customRules.isNotBlank()) sources.add(FilterSource("Your rules", current.customRules))
            sources.add(AdBlocker.BUILT_IN)
            for (list in current.filterLists) {
                if (!list.enabled) continue
                val text = runCatching { file(list).readText() }.getOrNull() ?: continue
                sources.add(FilterSource(list.name, text))
            }
            _blocker.value = AdBlocker.build(sources)
        }
    }

    /**
     * Downloads enabled lists that are older than the update interval, or all
     * of them when [force] is set, then rebuilds. Returns how many were fetched
     * and how many failed.
     */
    suspend fun update(force: Boolean): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val current = settings.flow.value
        val now = System.currentTimeMillis()
        val maxAge = current.updateDays.coerceAtLeast(1) * 86_400_000L
        var fetched = 0
        var failed = 0
        for (list in current.filterLists) {
            if (!list.enabled) continue
            val at = state(list).downloadedAt
            if (!force && at != null && now - at < maxAge) continue
            val k = key(list)
            val problem = try {
                val response = http.get(list.url, mapOf("User-Agent" to DEFAULT_USER_AGENT))
                when {
                    response.code !in 200..299 -> "HTTP ${response.code}"
                    response.body.length < 100 -> "The list was empty"
                    response.body.take(300).contains("<html", ignoreCase = true) -> "The address returned a web page, not a list"
                    else -> {
                        file(list).writeText(response.body)
                        null
                    }
                }
            } catch (e: Exception) {
                "Download failed: ${e.message ?: e.javaClass.simpleName}"
            }
            if (problem == null) {
                prefs.edit().putLong("at:$k", now).remove("error:$k").apply()
                fetched++
            } else {
                prefs.edit().putString("error:$k", problem).apply()
                failed++
            }
        }
        if (fetched > 0 || force) reload()
        _revision.value++
        fetched to failed
    }
}
