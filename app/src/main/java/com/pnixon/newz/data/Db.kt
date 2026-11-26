package com.pnixon.newz.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val homeUrl: String,
    /** The recipe as JSON, exactly as it appears in the exported config. */
    val recipeJson: String,
    val trust: String,
    val enabled: Boolean = true,
    val refreshMinutes: Int = 60,
    val userAgent: String? = null,
    val headersJson: String = "{}",
    val etag: String? = null,
    val lastModified: String? = null,
    val lastFetchAt: Long? = null,
    val lastStatus: String? = null,
    val lastOk: Boolean = true,
    val lastItemCount: Int? = null,
    /** Fetches in a row that failed or found nothing. */
    val failCount: Int = 0,
)

@Entity(
    tableName = "articles",
    indices = [Index(value = ["canonicalUrl"], unique = true), Index("sourceId")],
)
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val url: String,
    val canonicalUrl: String,
    val title: String,
    val summary: String? = null,
    val imageUrl: String? = null,
    val publishedAt: Long? = null,
    val fetchedAt: Long,
    val read: Boolean = false,
    val bookmarked: Boolean = false,
    /** The source's own categories, one per line. Null when it gave none. */
    val tags: String? = null,
)

@Entity(tableName = "fetch_log", indices = [Index("sourceId")])
data class FetchLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val at: Long,
    val ok: Boolean,
    val message: String,
    val itemCount: Int,
    val newCount: Int,
)

@Dao
interface NewsDao {
    @Query("SELECT * FROM sources ORDER BY name COLLATE NOCASE")
    fun sources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources")
    suspend fun sourcesNow(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun source(id: Long): SourceEntity?

    @Insert
    suspend fun insertSource(source: SourceEntity): Long

    @Update
    suspend fun updateSource(source: SourceEntity)

    @Query("UPDATE sources SET trust = :trust WHERE id = :id")
    suspend fun setTrust(id: Long, trust: String)

    @Query(
        "UPDATE sources SET etag = :etag, lastModified = :lastModified, lastFetchAt = :at, " +
            "lastStatus = :status, lastOk = :ok, lastItemCount = :itemCount, failCount = :failCount WHERE id = :id"
    )
    suspend fun setFetchState(
        id: Long,
        etag: String?,
        lastModified: String?,
        at: Long,
        status: String,
        ok: Boolean,
        itemCount: Int?,
        failCount: Int,
    )

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun deleteSource(id: Long)

    @Query("DELETE FROM articles WHERE sourceId = :sourceId")
    suspend fun deleteArticlesOf(sourceId: Long)

    @Query("DELETE FROM fetch_log WHERE sourceId = :sourceId")
    suspend fun deleteLogOf(sourceId: Long)

    @Query(
        "SELECT * FROM articles WHERE fetchedAt > :since OR bookmarked = 1 " +
            "ORDER BY fetchedAt DESC LIMIT 5000"
    )
    fun articlesSince(since: Long): Flow<List<ArticleEntity>>

    @Query("SELECT title FROM articles WHERE sourceId = :sourceId ORDER BY fetchedAt DESC, id LIMIT :limit")
    suspend fun recentTitles(sourceId: Long, limit: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArticles(articles: List<ArticleEntity>): List<Long>

    @Query("UPDATE articles SET read = :read WHERE id = :id")
    suspend fun setRead(id: Long, read: Boolean)

    @Query("DELETE FROM articles WHERE fetchedAt < :before AND bookmarked = 0")
    suspend fun pruneArticles(before: Long)

    @Insert
    suspend fun insertLog(entry: FetchLogEntity)

    @Query("SELECT * FROM fetch_log WHERE sourceId = :sourceId ORDER BY at DESC LIMIT 20")
    fun logFor(sourceId: Long): Flow<List<FetchLogEntity>>

    @Query("DELETE FROM fetch_log WHERE at < :before")
    suspend fun pruneLog(before: Long)
}

/** Version 2 added the article tags column. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE articles ADD COLUMN tags TEXT")
    }
}

@Database(
    entities = [SourceEntity::class, ArticleEntity::class, FetchLogEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class NewsDatabase : RoomDatabase() {
    abstract fun dao(): NewsDao
}
