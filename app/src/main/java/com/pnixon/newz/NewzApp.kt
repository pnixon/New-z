package com.pnixon.newz

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pnixon.newz.data.FilterListStore
import com.pnixon.newz.data.JsonStore
import com.pnixon.newz.data.LogDatabase
import com.pnixon.newz.data.OnDeviceModel
import com.pnixon.newz.data.MIGRATION_1_2
import com.pnixon.newz.data.Publisher
import com.pnixon.newz.data.SecretStore
import com.pnixon.newz.data.WebRenderer
import com.pnixon.newz.data.NewsDatabase
import com.pnixon.newz.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import newz.core.BrowserSettings
import newz.core.ModelSettings
import newz.core.OkHttpFetcher
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import newz.core.OpenEvent
import newz.core.PublishSettings
import newz.core.RankingConfig
import newz.core.SharedItem
import newz.core.SearchSettings
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    private val db = Room.databaseBuilder(context, NewsDatabase::class.java, "newz.db")
        .addMigrations(MIGRATION_1_2)
        .build()
    private val logDb = Room.databaseBuilder(context, LogDatabase::class.java, "newz-log.db").build()
    private val http = OkHttpFetcher()
    val config = JsonStore(context, "ranking.json", RankingConfig.serializer(), RankingConfig())
    val models = JsonStore(context, "models.json", ModelSettings.serializer(), ModelSettings())
    val secrets = SecretStore(context)
    val browser = JsonStore(context, "browser.json", BrowserSettings.serializer(), BrowserSettings())
    val filters = FilterListStore(context, http, browser)
    val search = JsonStore(context, "search.json", SearchSettings.serializer(), SearchSettings())
    val publish = JsonStore(context, "publish.json", PublishSettings.serializer(), PublishSettings())
    val shared = JsonStore(context, "shared.json", ListSerializer(SharedItem.serializer()), emptyList())
    val publisher = Publisher(context, http, publish, shared, secrets)
    private val renderer = WebRenderer(context, filters, browser)
    val onDevice = OnDeviceModel()
    val repository = Repository(
        context, db.dao(), logDb.dao(), http, http, models, secrets, config, search,
        render = { url, userAgent -> renderer.render(url, userAgent) },
        local = onDevice,
    )
    val reading = JsonStore(context, "reading.json", ListSerializer(OpenEvent.serializer()), emptyList())
    val dismissed = JsonStore(context, "dismissed.json", ListSerializer(String.serializer()), emptyList())
}


class NewzApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Read the downloaded filter lists off the main thread.
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            container.filters.reload()
            // Only ask the phone about its built-in model if the user has switched it on.
            if (container.models.flow.value.onDevice) container.onDevice.refresh()
        }
        RefreshWorker.schedule(this)
    }
}

/** Checks sources in the background. Each source still honours its own interval. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as NewzApp).container.repository
        return try {
            repository.seedIfNeeded()
            val container = (applicationContext as NewzApp).container
            val summary = repository.refresh(force = false)
            runCatching {
                Notifier.notifyNew(applicationContext, repository.rankItems(summary.newIds), container.config.flow.value.topics)
            }
            runCatching { (applicationContext as NewzApp).container.filters.update(force = false) }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
