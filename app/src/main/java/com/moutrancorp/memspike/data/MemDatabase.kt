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
    tableName = "caption_tracks",
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
        Index(value = ["language"]),
        Index(value = ["source"]),
    ],
)
data class CaptionTrackEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val language: String?,
    val source: String,
    val format: String?,
    val segmentCount: Int,
    val chunkCount: Int,
    val createdAt: Long,
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
    val language: String?,
    val startOffset: Int?,
    val endOffset: Int?,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val page: Int?,
    val sectionTitle: String?,
    val provider: String?,
    val contentHash: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "assets",
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
        Index(value = ["assetType"]),
        Index(value = ["role"]),
    ],
)
data class AssetEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val assetType: String,
    val role: String,
    val remoteUrl: String?,
    val localPath: String?,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
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

@Fts4
@Entity(tableName = "chunk_search")
data class ChunkSearchEntity(
    val chunkId: String,
    val sourceId: String,
    val title: String,
    val body: String,
    val tags: String,
)

@Entity(
    tableName = "embedding_models",
    indices = [Index(value = ["provider", "model", "embeddingType"], unique = true)],
)
data class EmbeddingModelEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val model: String,
    val embeddingType: String,
    val dimensions: Int?,
    val quantization: String?,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "chunk_embeddings",
    foreignKeys = [
        ForeignKey(
            entity = DocumentChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["chunkId"]),
        Index(value = ["sourceId"]),
        Index(value = ["modelId"]),
        Index(value = ["contentHash"]),
    ],
)
data class ChunkEmbeddingEntity(
    @PrimaryKey val id: String,
    val chunkId: String,
    val sourceId: String,
    val modelId: String,
    val embeddingType: String,
    val dimensions: Int,
    val vector: ByteArray,
    val contentHash: String,
    val createdAt: Long,
)

@Entity(
    tableName = "visual_observations",
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
        Index(value = ["observationType"]),
        Index(value = ["provider"]),
    ],
)
data class VisualObservationEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val assetId: String?,
    val observationType: String,
    val text: String,
    val confidence: Float?,
    val provider: String,
    val model: String?,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val createdAt: Long,
)

@Entity(
    tableName = "search_queries",
    indices = [Index(value = ["createdAt"])],
)
data class SearchQueryEntity(
    @PrimaryKey val id: String,
    val query: String,
    val parsedFiltersJson: String,
    val resultCount: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "agent_actions",
    indices = [
        Index(value = ["actionType"]),
        Index(value = ["state"]),
        Index(value = ["createdAt"]),
    ],
)
data class AgentActionEntity(
    @PrimaryKey val id: String,
    val actionType: String,
    val state: String,
    val title: String,
    val rationale: String,
    val previewJson: String,
    val undoPayloadJson: String?,
    val createdAt: Long,
    val appliedAt: Long?,
    val undoneAt: Long?,
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)],
)
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorKey: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "source_tags",
    primaryKeys = ["sourceId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sourceId"]), Index(value = ["tagId"])],
)
data class SourceTagEntity(
    val sourceId: String,
    val tagId: String,
    val createdAt: Long,
)

@Entity(
    tableName = "collections",
    indices = [Index(value = ["title"])],
)
data class CollectionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val type: String,
    val filterJson: String?,
    val createdBy: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "collection_sources",
    primaryKeys = ["collectionId", "sourceId"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["collectionId"]), Index(value = ["sourceId"])],
)
data class CollectionSourceEntity(
    val collectionId: String,
    val sourceId: String,
    val addedAt: Long,
)

@Entity(
    tableName = "auth_sessions",
    indices = [
        Index(value = ["domain"], unique = true),
        Index(value = ["provider"]),
        Index(value = ["status"]),
    ],
)
data class AuthSessionEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val domain: String,
    val status: String,
    val cookieFilePath: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastValidatedAt: Long?,
    val lastError: String?,
)

data class CollectionSummary(
    val id: String,
    val title: String,
    val type: String,
    val itemCount: Int,
    val updatedAt: Long,
)

data class ChunkSearchResult(
    val sourceId: String,
    val chunkId: String,
    val title: String,
    val sourceType: String,
    val originDomain: String?,
    val author: String?,
    val durationSeconds: Long?,
    val body: String,
    val chunkType: String,
    val language: String?,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val savedAt: Long,
)

data class ChunkEmbeddingCandidate(
    val sourceId: String,
    val chunkId: String,
    val title: String,
    val sourceType: String,
    val originDomain: String?,
    val author: String?,
    val durationSeconds: Long?,
    val body: String,
    val chunkType: String,
    val language: String?,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val savedAt: Long,
    val dimensions: Int,
    val vector: ByteArray,
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

    @Query("SELECT COUNT(*) FROM sources")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM sources WHERE processingState = 'needs_auth' OR authState = 'needs_auth'")
    suspend fun countNeedsAuth(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(source: SourceEntity)

    @Query("DELETE FROM sources WHERE id = :sourceId")
    suspend fun deleteById(sourceId: String)
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

    @Query("DELETE FROM document_chunks WHERE sourceId = :sourceId AND chunkType IN (:chunkTypes)")
    suspend fun deleteForSourceAndTypes(sourceId: String, chunkTypes: List<String>)

    @Query("SELECT * FROM document_chunks WHERE sourceId = :sourceId ORDER BY COALESCE(startTimeMs, startOffset, 0) LIMIT :limit")
    suspend fun findBySource(sourceId: String, limit: Int = 200): List<DocumentChunkEntity>

    @Query("SELECT * FROM document_chunks WHERE sourceId = :sourceId AND chunkType = :chunkType")
    suspend fun findBySourceAndType(sourceId: String, chunkType: String): List<DocumentChunkEntity>

    @Query("SELECT COUNT(*) FROM document_chunks")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM document_chunks WHERE chunkType = :chunkType")
    suspend fun countByType(chunkType: String): Int

    @Query("SELECT COUNT(*) FROM document_chunks WHERE startTimeMs IS NOT NULL")
    suspend fun countTimestamped(): Int
}

@Dao
interface AssetDao {
    @Query("SELECT * FROM assets ORDER BY createdAt DESC")
    fun observeAssets(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE sourceId = :sourceId AND role = :role LIMIT 1")
    suspend fun findBySourceAndRole(sourceId: String, role: String): AssetEntity?

    @Query("SELECT * FROM assets WHERE sourceId = :sourceId")
    suspend fun findBySource(sourceId: String): List<AssetEntity>

    @Query("SELECT COUNT(*) FROM assets WHERE role = :role")
    suspend fun countByRole(role: String): Int

    @Query(
        """
        SELECT sourceId FROM assets
        WHERE role IN (:roles)
        GROUP BY sourceId
        HAVING COUNT(DISTINCT role) = :roleCount
        """,
    )
    suspend fun sourceIdsWithAllRoles(roles: List<String>, roleCount: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(asset: AssetEntity)
}

@Dao
interface SourceSearchDao {
    @Query("DELETE FROM source_search WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SourceSearchEntity)
}

@Dao
interface ChunkSearchDao {
    @Query("DELETE FROM chunk_search WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: String)

    @Query("DELETE FROM chunk_search WHERE chunkId = :chunkId")
    suspend fun deleteForChunk(chunkId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ChunkSearchEntity)

    @Query("SELECT COUNT(*) FROM chunk_search")
    suspend fun countAll(): Int

    @Query(
        """
        SELECT sources.id AS sourceId, chunk_search.chunkId AS chunkId, sources.title AS title,
               sources.sourceType AS sourceType, sources.originDomain AS originDomain,
               sources.author AS author, sources.durationSeconds AS durationSeconds,
               chunk_search.body AS body,
               document_chunks.chunkType AS chunkType, document_chunks.language AS language,
               document_chunks.startTimeMs AS startTimeMs,
               document_chunks.endTimeMs AS endTimeMs, sources.savedAt AS savedAt
        FROM chunk_search
        INNER JOIN sources ON sources.id = chunk_search.sourceId
        LEFT JOIN document_chunks ON document_chunks.id = chunk_search.chunkId
        WHERE chunk_search MATCH :query
        ORDER BY sources.savedAt DESC
        LIMIT :limit
        """,
    )
    fun observeSearchResults(query: String, limit: Int): Flow<List<ChunkSearchResult>>

    @Query(
        """
        SELECT sources.id AS sourceId, chunk_search.chunkId AS chunkId, sources.title AS title,
               sources.sourceType AS sourceType, sources.originDomain AS originDomain,
               sources.author AS author, sources.durationSeconds AS durationSeconds,
               chunk_search.body AS body,
               document_chunks.chunkType AS chunkType, document_chunks.language AS language,
               document_chunks.startTimeMs AS startTimeMs,
               document_chunks.endTimeMs AS endTimeMs, sources.savedAt AS savedAt
        FROM chunk_search
        INNER JOIN sources ON sources.id = chunk_search.sourceId
        LEFT JOIN document_chunks ON document_chunks.id = chunk_search.chunkId
        WHERE chunk_search MATCH :query
        ORDER BY sources.savedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun searchResults(query: String, limit: Int): List<ChunkSearchResult>
}

@Dao
interface CaptionTrackDao {
    @Query("DELETE FROM caption_tracks WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: String)

    @Query("SELECT * FROM caption_tracks WHERE sourceId = :sourceId ORDER BY createdAt DESC")
    suspend fun findBySource(sourceId: String): List<CaptionTrackEntity>

    @Query("SELECT COUNT(*) FROM caption_tracks")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(track: CaptionTrackEntity)
}

@Dao
interface EmbeddingModelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: EmbeddingModelEntity)
}

@Dao
interface ChunkEmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(embedding: ChunkEmbeddingEntity)

    @Query("DELETE FROM chunk_embeddings WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: String)

    @Query("DELETE FROM chunk_embeddings WHERE chunkId = :chunkId")
    suspend fun deleteForChunk(chunkId: String)

    @Query("SELECT COUNT(*) FROM chunk_embeddings WHERE embeddingType = :embeddingType")
    suspend fun countByType(embeddingType: String): Int

    @Query(
        """
        SELECT sources.id AS sourceId, document_chunks.id AS chunkId, sources.title AS title,
               sources.sourceType AS sourceType, sources.originDomain AS originDomain,
               sources.author AS author, sources.durationSeconds AS durationSeconds,
               document_chunks.text AS body,
               document_chunks.chunkType AS chunkType, document_chunks.language AS language,
               document_chunks.startTimeMs AS startTimeMs,
               document_chunks.endTimeMs AS endTimeMs, sources.savedAt AS savedAt,
               chunk_embeddings.dimensions AS dimensions, chunk_embeddings.vector AS vector
        FROM chunk_embeddings
        INNER JOIN document_chunks ON document_chunks.id = chunk_embeddings.chunkId
        INNER JOIN sources ON sources.id = chunk_embeddings.sourceId
        WHERE chunk_embeddings.embeddingType = :embeddingType
        ORDER BY sources.savedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun candidates(embeddingType: String, limit: Int): List<ChunkEmbeddingCandidate>
}

@Dao
interface VisualObservationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(observation: VisualObservationEntity)

    @Query("DELETE FROM visual_observations WHERE sourceId = :sourceId")
    suspend fun deleteForSource(sourceId: String)

    @Query("SELECT * FROM visual_observations WHERE sourceId = :sourceId ORDER BY COALESCE(startTimeMs, 0)")
    suspend fun findBySource(sourceId: String): List<VisualObservationEntity>

    @Query("SELECT COUNT(*) FROM visual_observations")
    suspend fun countAll(): Int
}

@Dao
interface SearchQueryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(query: SearchQueryEntity)

    @Query("SELECT COUNT(*) FROM search_queries")
    suspend fun countAll(): Int
}

@Dao
interface AgentActionDao {
    @Query("SELECT * FROM agent_actions WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): AgentActionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(action: AgentActionEntity)

    @Query("SELECT COUNT(*) FROM agent_actions")
    suspend fun countAll(): Int
}

@Dao
interface TagDao {
    @Query("SELECT tags.* FROM tags INNER JOIN source_tags ON tags.id = source_tags.tagId WHERE source_tags.sourceId = :sourceId ORDER BY tags.name")
    suspend fun tagsForSource(sourceId: String): List<TagEntity>

    @Query(
        """
        SELECT source_tags.sourceId FROM source_tags
        INNER JOIN tags ON tags.id = source_tags.tagId
        WHERE tags.name IN (:names)
        GROUP BY source_tags.sourceId
        HAVING COUNT(DISTINCT tags.name) = :nameCount
        """,
    )
    suspend fun sourceIdsWithAllTagNames(names: List<String>, nameCount: Int): List<String>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: TagEntity)

    @Query("SELECT * FROM source_tags WHERE sourceId = :sourceId AND tagId = :tagId LIMIT 1")
    suspend fun findSourceTag(sourceId: String, tagId: String): SourceTagEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceTag(sourceTag: SourceTagEntity)

    @Query("DELETE FROM source_tags WHERE sourceId = :sourceId AND tagId = :tagId")
    suspend fun deleteSourceTag(sourceId: String, tagId: String)
}

@Dao
interface CollectionDao {
    @Query(
        """
        SELECT collections.id, collections.title, collections.type, COUNT(collection_sources.sourceId) AS itemCount, collections.updatedAt
        FROM collections
        LEFT JOIN collection_sources ON collections.id = collection_sources.collectionId
        GROUP BY collections.id
        ORDER BY collections.updatedAt DESC
        """,
    )
    fun observeCollectionSummaries(): Flow<List<CollectionSummary>>

    @Query("SELECT * FROM collections WHERE title = :title LIMIT 1")
    suspend fun findByTitle(title: String): CollectionEntity?

    @Query(
        """
        SELECT collection_sources.sourceId FROM collection_sources
        INNER JOIN collections ON collections.id = collection_sources.collectionId
        WHERE lower(collections.title) IN (:titles)
        GROUP BY collection_sources.sourceId
        HAVING COUNT(DISTINCT lower(collections.title)) = :titleCount
        """,
    )
    suspend fun sourceIdsInAllCollections(titles: List<String>, titleCount: Int): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(collection: CollectionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollectionSource(collectionSource: CollectionSourceEntity)

    @Query("DELETE FROM collection_sources WHERE collectionId = :collectionId AND sourceId = :sourceId")
    suspend fun deleteCollectionSource(collectionId: String, sourceId: String)
}

@Dao
interface AuthSessionDao {
    @Query("SELECT * FROM auth_sessions WHERE domain = :domain LIMIT 1")
    suspend fun findByDomain(domain: String): AuthSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: AuthSessionEntity)

    @Query("DELETE FROM auth_sessions WHERE domain = :domain")
    suspend fun deleteByDomain(domain: String)
}

@Database(
    entities = [
        SourceEntity::class,
        CaptionTrackEntity::class,
        IngestionJobEntity::class,
        DocumentChunkEntity::class,
        AssetEntity::class,
        SourceSearchEntity::class,
        ChunkSearchEntity::class,
        EmbeddingModelEntity::class,
        ChunkEmbeddingEntity::class,
        VisualObservationEntity::class,
        SearchQueryEntity::class,
        AgentActionEntity::class,
        TagEntity::class,
        SourceTagEntity::class,
        CollectionEntity::class,
        CollectionSourceEntity::class,
        AuthSessionEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class MemDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun captionTrackDao(): CaptionTrackDao
    abstract fun ingestionJobDao(): IngestionJobDao
    abstract fun documentChunkDao(): DocumentChunkDao
    abstract fun assetDao(): AssetDao
    abstract fun sourceSearchDao(): SourceSearchDao
    abstract fun chunkSearchDao(): ChunkSearchDao
    abstract fun embeddingModelDao(): EmbeddingModelDao
    abstract fun chunkEmbeddingDao(): ChunkEmbeddingDao
    abstract fun visualObservationDao(): VisualObservationDao
    abstract fun searchQueryDao(): SearchQueryDao
    abstract fun agentActionDao(): AgentActionDao
    abstract fun tagDao(): TagDao
    abstract fun collectionDao(): CollectionDao
    abstract fun authSessionDao(): AuthSessionDao

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

        private val migration2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createOrganizationTables(db)
            }
        }

        private val migration3To4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createAssetTables(db)
            }
        }

        private val migration4To5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createAuthSessionTables(db)
            }
        }

        private val migration5To6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createSearchAndRagTables(db)
            }
        }

        private val migration6To7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createEmbeddingTables(db)
            }
        }

        private val migration7To8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createAgentActionTables(db)
            }
        }

        private fun createAgentActionTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_actions` (
                    `id` TEXT NOT NULL,
                    `actionType` TEXT NOT NULL,
                    `state` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `rationale` TEXT NOT NULL,
                    `previewJson` TEXT NOT NULL,
                    `undoPayloadJson` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    `appliedAt` INTEGER,
                    `undoneAt` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_actions_actionType` ON `agent_actions` (`actionType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_actions_state` ON `agent_actions` (`state`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_agent_actions_createdAt` ON `agent_actions` (`createdAt`)")
        }

        private fun createEmbeddingTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `chunk_embeddings` (
                    `id` TEXT NOT NULL,
                    `chunkId` TEXT NOT NULL,
                    `sourceId` TEXT NOT NULL,
                    `modelId` TEXT NOT NULL,
                    `embeddingType` TEXT NOT NULL,
                    `dimensions` INTEGER NOT NULL,
                    `vector` BLOB NOT NULL,
                    `contentHash` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`chunkId`) REFERENCES `document_chunks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chunk_embeddings_chunkId` ON `chunk_embeddings` (`chunkId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chunk_embeddings_sourceId` ON `chunk_embeddings` (`sourceId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chunk_embeddings_modelId` ON `chunk_embeddings` (`modelId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chunk_embeddings_contentHash` ON `chunk_embeddings` (`contentHash`)")
        }


        private fun createSearchAndRagTables(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `document_chunks` ADD COLUMN `language` TEXT")
            db.execSQL("ALTER TABLE `document_chunks` ADD COLUMN `page` INTEGER")
            db.execSQL("ALTER TABLE `document_chunks` ADD COLUMN `sectionTitle` TEXT")
            db.execSQL("ALTER TABLE `document_chunks` ADD COLUMN `provider` TEXT")
            db.execSQL("ALTER TABLE `document_chunks` ADD COLUMN `contentHash` TEXT")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `caption_tracks` (
                    `id` TEXT NOT NULL,
                    `sourceId` TEXT NOT NULL,
                    `language` TEXT,
                    `source` TEXT NOT NULL,
                    `format` TEXT,
                    `segmentCount` INTEGER NOT NULL,
                    `chunkCount` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_caption_tracks_sourceId` ON `caption_tracks` (`sourceId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_caption_tracks_language` ON `caption_tracks` (`language`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_caption_tracks_source` ON `caption_tracks` (`source`)")
            db.execSQL(
                """
                CREATE VIRTUAL TABLE IF NOT EXISTS `chunk_search`
                USING FTS4(
                    `chunkId` TEXT NOT NULL,
                    `sourceId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `body` TEXT NOT NULL,
                    `tags` TEXT NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO chunk_search(chunkId, sourceId, title, body, tags)
                SELECT document_chunks.id, document_chunks.sourceId, sources.title, document_chunks.text,
                       document_chunks.chunkType || ' ' || sources.sourceType || ' ' || sources.processingState
                FROM document_chunks
                INNER JOIN sources ON sources.id = document_chunks.sourceId
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `embedding_models` (
                    `id` TEXT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `model` TEXT NOT NULL,
                    `embeddingType` TEXT NOT NULL,
                    `dimensions` INTEGER,
                    `quantization` TEXT,
                    `status` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_embedding_models_provider_model_embeddingType` ON `embedding_models` (`provider`, `model`, `embeddingType`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `visual_observations` (
                    `id` TEXT NOT NULL,
                    `sourceId` TEXT NOT NULL,
                    `assetId` TEXT,
                    `observationType` TEXT NOT NULL,
                    `text` TEXT NOT NULL,
                    `confidence` REAL,
                    `provider` TEXT NOT NULL,
                    `model` TEXT,
                    `startTimeMs` INTEGER,
                    `endTimeMs` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_visual_observations_sourceId` ON `visual_observations` (`sourceId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_visual_observations_observationType` ON `visual_observations` (`observationType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_visual_observations_provider` ON `visual_observations` (`provider`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `search_queries` (
                    `id` TEXT NOT NULL,
                    `query` TEXT NOT NULL,
                    `parsedFiltersJson` TEXT NOT NULL,
                    `resultCount` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_search_queries_createdAt` ON `search_queries` (`createdAt`)")
        }

        private fun createAuthSessionTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `auth_sessions` (
                    `id` TEXT NOT NULL,
                    `provider` TEXT NOT NULL,
                    `domain` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `cookieFilePath` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `lastValidatedAt` INTEGER,
                    `lastError` TEXT,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_auth_sessions_domain` ON `auth_sessions` (`domain`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_auth_sessions_provider` ON `auth_sessions` (`provider`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_auth_sessions_status` ON `auth_sessions` (`status`)")
        }

        private fun createAssetTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `assets` (
                    `id` TEXT NOT NULL,
                    `sourceId` TEXT NOT NULL,
                    `assetType` TEXT NOT NULL,
                    `role` TEXT NOT NULL,
                    `remoteUrl` TEXT,
                    `localPath` TEXT,
                    `mimeType` TEXT,
                    `width` INTEGER,
                    `height` INTEGER,
                    `durationMs` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_assets_sourceId` ON `assets` (`sourceId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_assets_assetType` ON `assets` (`assetType`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_assets_role` ON `assets` (`role`)")
            db.execSQL(
                """
                INSERT INTO assets(id, sourceId, assetType, role, remoteUrl, localPath, mimeType, width, height, durationMs, createdAt)
                SELECT id || ':thumbnail', id, 'image', 'thumbnail', thumbnailUrl, NULL, NULL, NULL, NULL, NULL, updatedAt
                FROM sources
                WHERE thumbnailUrl IS NOT NULL AND thumbnailUrl != ''
                """.trimIndent(),
            )
        }

        private fun createOrganizationTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `tags` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `colorKey` TEXT,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `source_tags` (
                    `sourceId` TEXT NOT NULL,
                    `tagId` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    PRIMARY KEY(`sourceId`, `tagId`),
                    FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`tagId`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_source_tags_sourceId` ON `source_tags` (`sourceId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_source_tags_tagId` ON `source_tags` (`tagId`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `collections` (
                    `id` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `filterJson` TEXT,
                    `createdBy` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_collections_title` ON `collections` (`title`)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `collection_sources` (
                    `collectionId` TEXT NOT NULL,
                    `sourceId` TEXT NOT NULL,
                    `addedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`collectionId`, `sourceId`),
                    FOREIGN KEY(`collectionId`) REFERENCES `collections`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`sourceId`) REFERENCES `sources`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_sources_collectionId` ON `collection_sources` (`collectionId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_collection_sources_sourceId` ON `collection_sources` (`sourceId`)")
        }

        fun get(context: Context): MemDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemDatabase::class.java,
                    "mem.db",
                )
                    .addMigrations(migration1To2, migration2To3, migration3To4, migration4To5, migration5To6, migration6To7, migration7To8)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
