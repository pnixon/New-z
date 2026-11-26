package com.pnixon.newz.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** A recipe a source used to have, kept so any change can be undone. */
@Entity(tableName = "recipe_history", indices = [Index("sourceId")])
data class RecipeHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: Long,
    val at: Long,
    val recipeJson: String,
    /** Why it was replaced. */
    val reason: String,
)

/** One request to a model, in full: what was sent, what came back, and what the app did with it. */
@Entity(tableName = "model_calls", indices = [Index("sourceId"), Index("at")])
data class ModelCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val task: String,
    val sourceId: Long?,
    /** The page or thing the call was about. */
    val target: String,
    /** Host of the provider the request went to. */
    val endpoint: String,
    val model: String,
    val systemPrompt: String,
    val userPrompt: String,
    val response: String?,
    val outcome: String,
    val accepted: Boolean,
    val durationMs: Long,
    val promptTokens: Int?,
    val completionTokens: Int?,
)

/** A model call without its long texts, for lists. */
data class ModelCallSummary(
    val id: Long,
    val at: Long,
    val task: String,
    val target: String,
    val endpoint: String,
    val model: String,
    val outcome: String,
    val accepted: Boolean,
    val durationMs: Long,
    val promptTokens: Int?,
    val completionTokens: Int?,
)

@Dao
interface LogDao {
    @Insert
    suspend fun insertHistory(entry: RecipeHistoryEntity)

    @Query("SELECT * FROM recipe_history WHERE sourceId = :sourceId ORDER BY at DESC LIMIT 20")
    fun historyFor(sourceId: Long): Flow<List<RecipeHistoryEntity>>

    @Query("SELECT * FROM recipe_history WHERE id = :id")
    suspend fun history(id: Long): RecipeHistoryEntity?

    @Query("DELETE FROM recipe_history WHERE sourceId = :sourceId")
    suspend fun deleteHistoryOf(sourceId: Long)

    @Insert
    suspend fun insertCall(call: ModelCallEntity)

    @Query(
        "SELECT id, at, task, target, endpoint, model, outcome, accepted, durationMs, promptTokens, completionTokens " +
            "FROM model_calls ORDER BY at DESC, id DESC LIMIT 200"
    )
    fun calls(): Flow<List<ModelCallSummary>>

    @Query("SELECT * FROM model_calls WHERE id = :id")
    suspend fun call(id: Long): ModelCallEntity?

    @Query("SELECT MAX(at) FROM model_calls WHERE task = 'repair' AND sourceId = :sourceId")
    suspend fun lastRepairAt(sourceId: Long): Long?

    @Query("DELETE FROM model_calls WHERE id NOT IN (SELECT id FROM model_calls ORDER BY at DESC, id DESC LIMIT 200)")
    suspend fun pruneCalls()

    @Query("DELETE FROM model_calls")
    suspend fun clearCalls()
}

/**
 * Kept apart from the news database so adding these records changed nothing
 * about the existing one.
 */
@Database(entities = [RecipeHistoryEntity::class, ModelCallEntity::class], version = 1, exportSchema = false)
abstract class LogDatabase : RoomDatabase() {
    abstract fun dao(): LogDao
}
