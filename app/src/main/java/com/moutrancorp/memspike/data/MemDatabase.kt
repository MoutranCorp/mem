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
interface AssetDao {
    @Query("SELECT * FROM assets ORDER BY createdAt DESC")
    fun observeAssets(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM assets WHERE sourceId = :sourceId AND role = :role LIMIT 1")
    suspend fun findBySourceAndRole(sourceId: String, role: String): AssetEntity?

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
interface TagDao {
    @Query("SELECT tags.* FROM tags INNER JOIN source_tags ON tags.id = source_tags.tagId WHERE source_tags.sourceId = :sourceId ORDER BY tags.name")
    suspend fun tagsForSource(sourceId: String): List<TagEntity>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): TagEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: TagEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceTag(sourceTag: SourceTagEntity)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(collection: CollectionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollectionSource(collectionSource: CollectionSourceEntity)
}

@Dao
interface AuthSessionDao {
    @Query("SELECT * FROM auth_sessions WHERE domain = :domain LIMIT 1")
    suspend fun findByDomain(domain: String): AuthSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: AuthSessionEntity)
}

@Database(
    entities = [
        SourceEntity::class,
        IngestionJobEntity::class,
        DocumentChunkEntity::class,
        AssetEntity::class,
        SourceSearchEntity::class,
        TagEntity::class,
        SourceTagEntity::class,
        CollectionEntity::class,
        CollectionSourceEntity::class,
        AuthSessionEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class MemDatabase : RoomDatabase() {
    abstract fun sourceDao(): SourceDao
    abstract fun ingestionJobDao(): IngestionJobDao
    abstract fun documentChunkDao(): DocumentChunkDao
    abstract fun assetDao(): AssetDao
    abstract fun sourceSearchDao(): SourceSearchDao
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
                    .addMigrations(migration1To2, migration2To3, migration3To4, migration4To5)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
