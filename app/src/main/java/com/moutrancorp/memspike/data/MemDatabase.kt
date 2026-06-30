package com.moutrancorp.memspike.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "sources",
    indices = [
        Index(value = ["canonicalUrl"], unique = true),
        Index(value = ["savedAt"]),
        Index(value = ["processingState"]),
    ],
)
data class SourceEntity(
    @PrimaryKey val id: String,
    val canonicalUrl: String,
    val originalUrl: String,
    val sourceType: String,
    val originDomain: String?,
    val title: String,
    val author: String?,
    val summary: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val savedAt: Long,
    val updatedAt: Long,
    val rightsState: String,
    val authState: String,
    val processingState: String,
    val rawMetadataJson: String?,
)

@Entity(
    tableName = "ingestion_jobs",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["state"]),
        Index(value = ["updatedAt"]),
    ],
)
data class IngestionJobEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val jobType: String,
    val state: String,
    val progress: Float,
    val retryCount: Int,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "document_chunks",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["chunkType"]),
    ],
)
data class DocumentChunkEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val text: String,
    val chunkType: String,
    val startOffset: Int?,
    val endOffset: Int?,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val createdAt: Long,
)

@Fts4
@Entity(tableName = "source_search")
data class SourceSearchEntity(
    val sourceId: String,
    val title: String,
    val body: String,
    val tags: String,
)

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY savedAt DESC")
    fun observeSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources ORDER BY savedAt DESC LIMIT :limit")
    fun observeRecentSources(limit: Int): Flow<List<SourceEntity>>

    @Query(
        """
        SELECT sources.* FROM sources
        INNER JOIN source_search ON sources.id = source_search.sourceId
        WHERE source_search MATCH :query
        ORDER BY sources.savedAt DESC
        """,
    )
    fun observeSearchSources(query: String): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE canonicalUrl = :canonicalUrl LIMIT 1")
    suspend fun findByCanonicalUrl(canonicalUrl: String): SourceEntity?

    @Query("SELECT * FROM sources WHERE id = :sourceId LIMIT 1")
    suspend fun findById(sourceId: String): SourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity)
}

@Dao
interface IngestionJobDao {
    @Query("SELECT * FROM ingestion_jobs ORDER BY updatedAt DESC")
    fun observeJobs(): Flow<List<IngestionJobEntity>>

    @Query("SELECT * FROM ingestion_jobs WHERE state IN ('queued', 'extracting', 'needs_auth', 'failed') ORDER BY updatedAt DESC LIMIT :limit")
    fun observeActiveJobs(limit: Int): Flow<List<IngestionJobEntity>>

    @Query("SELECT * FROM ingestion_jobs WHERE id = :jobId LIMIT 1")
    suspend fun findById(jobId: String): IngestionJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: IngestionJobEntity)
}

@Dao
interface DocumentChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chunk: DocumentChunkEntity)
}

@Dao
interface SourceSearchDao {
    @Query("DELETE FROM source_search WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SourceSearchEntity)
}

@Database(
    entities = [
        SourceEntity::class,
        IngestionJobEntity::class,
        DocumentChunkEntity::class,
        SourceSearchEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class MemDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun ingestionJobDao(): IngestionJobDao
    abstract fun documentChunkDao(): DocumentChunkDao
    abstract fun sourceSearchDao(): SourceSearchDao

    companion object {
        @Volatile private var instance: MemDatabase? = null

        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS `source_search`
                    USING FTS4(
                        `sourceId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `tags` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO source_search(sourceId, title, body, tags)
                    SELECT id, title, COALESCE(summary, '') || ' ' || COALESCE(author, '') || ' ' || COALESCE(originDomain, ''), processingState
                    FROM sources
                    """.trimIndent(),
                )
            }
        }

        fun get(context: Context): MemDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemDatabase::class.java,
                    "mem.db",
                )
                    .addMigrations(migration1To2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
