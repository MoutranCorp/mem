package com.moutrancorp.memspike.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.URI
import java.security.MessageDigest
import kotlin.math.sqrt
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

private const val LOCAL_EMBEDDING_MODEL_ID = "local_hash_v1_text_128"
private const val LOCAL_EMBEDDING_PROVIDER = "local"
private const val LOCAL_EMBEDDING_MODEL = "hash-v1-text-128"
private const val LOCAL_EMBEDDING_DIMENSIONS = 128

class MemoryRepository(private val database: MemDatabase) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeMemoryState(query: Flow<String>): Flow<MemoryState> {
        val sourceFlow = query
            .map(::toFtsQuery)
            .distinctUntilChanged()
            .flatMapLatest { ftsQuery ->
                if (ftsQuery.isBlank()) {
                    database.sourceDao().observeSources()
                } else {
                    database.sourceDao().observeSearchSources(ftsQuery)
                }
            }
        return combine(
            sourceFlow,
            database.ingestionJobDao().observeJobs(),
            database.collectionDao().observeCollectionSummaries(),
            database.assetDao().observeAssets(),
        ) { sources, jobs, collections, assets ->
            MemoryState(sources = sources, jobs = jobs, collections = collections, assets = assets)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSearchResults(query: Flow<String>): Flow<List<SearchResultData>> {
        return query
            .map { it.trim() }
            .distinctUntilChanged()
            .flatMapLatest { rawQuery ->
                if (rawQuery.isBlank()) {
                    flowOf(emptyList())
                } else {
                    flow {
                        emit(searchMemory(rawQuery))
                    }
                }
            }
    }

    private suspend fun searchMemory(rawQuery: String): List<SearchResultData> {
        val ftsQuery = toFtsQuery(rawQuery)
        val ftsResults = if (ftsQuery.isBlank()) {
            emptyList()
        } else {
            database.chunkSearchDao().searchResults(ftsQuery, 60).mapIndexed { index, result ->
                result.toSearchResultData(
                    retrievalMode = "keyword",
                    rankScore = 1.0f / (index + 1),
                )
            }
        }
        val semanticResults = semanticSearch(rawQuery, 60)
        val fused = fuseSearchResults(ftsResults, semanticResults).take(40)
        database.searchQueryDao().insert(
            SearchQueryEntity(
                id = UUID.randomUUID().toString(),
                query = rawQuery,
                parsedFiltersJson = parsedSearchJson(rawQuery),
                resultCount = fused.size,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return fused
    }

    private suspend fun semanticSearch(rawQuery: String, limit: Int): List<SearchResultData> {
        val queryVector = localEmbedding(rawQuery)
        return database.chunkEmbeddingDao()
            .candidates("text", 5_000)
            .mapNotNull { candidate ->
                val score = cosineSimilarity(queryVector, candidate.vector.toFloatVector(candidate.dimensions))
                if (score < 0.08f) return@mapNotNull null
                candidate.toSearchResultData(score)
            }
            .sortedByDescending { it.rankScore }
            .take(limit)
    }

    private fun fuseSearchResults(keyword: List<SearchResultData>, semantic: List<SearchResultData>): List<SearchResultData> {
        val byChunk = linkedMapOf<String, SearchResultData>()
        (keyword + semantic).forEachIndexed { index, result ->
            val existing = byChunk[result.chunkId]
            val sourceDedupePenalty = byChunk.values.count { it.sourceId == result.sourceId } * 0.03f
            val recencyBoost = 1.0f / (1 + index)
            val score = result.rankScore + recencyBoost - sourceDedupePenalty
            if (existing == null || score > existing.rankScore) {
                byChunk[result.chunkId] = result.copy(rankScore = score)
            } else if (existing.retrievalMode != result.retrievalMode) {
                byChunk[result.chunkId] = existing.copy(
                    retrievalMode = "hybrid",
                    matchReason = existing.matchReason.replace("Keyword", "Hybrid").replace("Semantic", "Hybrid"),
                    rankScore = existing.rankScore + 0.25f,
                )
            }
        }
        return byChunk.values.sortedByDescending { it.rankScore }
    }

    suspend fun chunksForSource(sourceId: String, limit: Int = 80): List<ContentChunkData> {
        return database.documentChunkDao().findBySource(sourceId, limit).map { chunk ->
            ContentChunkData(
                id = chunk.id,
                sourceId = chunk.sourceId,
                text = chunk.text,
                chunkType = chunk.chunkType,
                language = chunk.language,
                startTimeMs = chunk.startTimeMs,
                endTimeMs = chunk.endTimeMs,
                page = chunk.page,
                sectionTitle = chunk.sectionTitle,
                provider = chunk.provider,
            )
        }
    }

    suspend fun sourceSnapshot(sourceId: String): SourceSnapshot? {
        val source = database.sourceDao().findById(sourceId) ?: return null
        return SourceSnapshot(source = source, assets = database.assetDao().findBySource(sourceId))
    }

    suspend fun createQueuedSource(input: String): QueuedSource {
        val now = System.currentTimeMillis()
        val canonicalUrl = canonicalize(input)
        val existing = database.sourceDao().findByCanonicalUrl(canonicalUrl)
        val sourceId = existing?.id ?: stableSourceId(canonicalUrl)
        val title = existing?.title ?: input.take(90)
        val source = SourceEntity(
            id = sourceId,
            canonicalUrl = canonicalUrl,
            originalUrl = input,
            sourceType = "link",
            originDomain = originDomain(input),
            title = title,
            author = existing?.author,
            summary = existing?.summary,
            thumbnailUrl = existing?.thumbnailUrl,
            durationSeconds = existing?.durationSeconds,
            savedAt = existing?.savedAt ?: now,
            updatedAt = now,
            rightsState = existing?.rightsState ?: "unconfirmed",
            authState = existing?.authState ?: "unknown",
            processingState = "queued",
            rawMetadataJson = existing?.rawMetadataJson,
        )
        val job = IngestionJobEntity(
            id = UUID.randomUUID().toString(),
            sourceId = sourceId,
            jobType = "metadata_extraction",
            state = "queued",
            progress = 0.05f,
            retryCount = 0,
            lastError = null,
            createdAt = now,
            updatedAt = now,
        )
        database.sourceDao().upsert(source)
        database.ingestionJobDao().upsert(job)
        ensureTags(source.id, listOfNotNull("link", source.originDomain, "queued"))
        indexSource(source, null)
        return QueuedSource(sourceId = sourceId, jobId = job.id)
    }

    suspend fun markExtracting(sourceId: String, jobId: String) {
        val now = System.currentTimeMillis()
        val source = database.sourceDao().findById(sourceId)
        if (source != null) {
            database.sourceDao().upsert(
                source.copy(
                    processingState = "extracting",
                    updatedAt = now,
                ),
            )
        }
        source?.let { indexSource(it.copy(processingState = "extracting"), null) }
        database.ingestionJobDao().upsert(
            IngestionJobEntity(
                id = jobId,
                sourceId = sourceId,
                jobType = "metadata_extraction",
                state = "extracting",
                progress = 0.35f,
                retryCount = 0,
                lastError = null,
                createdAt = source?.savedAt ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun completeExtraction(sourceId: String, jobId: String, result: ExtractedSourceData) {
        val now = System.currentTimeMillis()
        val existing = database.sourceDao().findByCanonicalUrl(result.canonicalUrl)
        val source = SourceEntity(
            id = sourceId,
            canonicalUrl = result.canonicalUrl,
            originalUrl = result.originalUrl,
            sourceType = result.sourceType,
            originDomain = result.originDomain,
            title = result.title,
            author = result.author,
            summary = result.summary,
            thumbnailUrl = result.thumbnailUrl,
            durationSeconds = result.durationSeconds,
            savedAt = existing?.savedAt ?: now,
            updatedAt = now,
            rightsState = "unconfirmed",
            authState = if (result.authRequired) "needs_auth" else "public_or_available",
            processingState = if (result.ok) "done" else if (result.authRequired) "needs_auth" else "failed",
            rawMetadataJson = result.rawMetadataJson,
        )
        database.sourceDao().upsert(source)
        ensureTags(
            source.id,
            listOfNotNull(
                source.sourceType,
                source.originDomain,
                source.processingState,
                if (source.authState == "needs_auth") "needs auth" else null,
            ),
        )
        result.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { thumbnailUrl ->
            database.assetDao().upsert(
                AssetEntity(
                    id = stableId("asset:${source.id}:thumbnail"),
                    sourceId = source.id,
                    assetType = "image",
                    role = "thumbnail",
                    remoteUrl = thumbnailUrl,
                    localPath = null,
                    mimeType = null,
                    width = null,
                    height = null,
                    durationMs = null,
                    createdAt = now,
                ),
            )
        }
        database.captionTrackDao().deleteForSource(source.id)
        database.documentChunkDao().deleteForSourceAndTypes(
            source.id,
            listOf("metadata", "rag_text", "transcript", "transcript_segment", "article", "document", "note"),
        )
        database.chunkSearchDao().deleteForSource(source.id)
        database.chunkEmbeddingDao().deleteForSource(source.id)
        result.captionTrack?.let { track ->
            database.captionTrackDao().upsert(
                CaptionTrackEntity(
                    id = stableId("caption:${source.id}:${track.source}:${track.language}:${track.format}"),
                    sourceId = source.id,
                    language = track.language,
                    source = track.source,
                    format = track.format,
                    segmentCount = track.segmentCount,
                    chunkCount = track.chunkCount,
                    createdAt = now,
                ),
            )
        }
        val extractedChunks = result.chunks.ifEmpty {
            val text = result.ragText?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(result.title, result.author, result.summary).joinToString("\n\n")
            if (text.isBlank()) {
                emptyList()
            } else {
                listOf(
                    ExtractedContentChunk(
                        text = text,
                        chunkType = if (result.ragText.isNullOrBlank()) "metadata" else "rag_text",
                        language = null,
                        startOffset = 0,
                        endOffset = text.length,
                        startTimeMs = null,
                        endTimeMs = null,
                        page = null,
                        sectionTitle = null,
                        provider = "extractor",
                    ),
                )
            }
        }
        val indexedText = storeChunks(source, extractedChunks, now)
        ensureLocalEmbeddingModel(now)
        indexSource(source, listOfNotNull(result.summary, indexedText).joinToString("\n\n"))
        database.ingestionJobDao().upsert(
            IngestionJobEntity(
                id = jobId,
                sourceId = sourceId,
                jobType = "metadata_extraction",
                state = source.processingState,
                progress = 1f,
                retryCount = 0,
                lastError = result.error,
                createdAt = source.savedAt,
                updatedAt = now,
            ),
        )
    }

    suspend fun cancelJob(jobId: String) {
        val job = database.ingestionJobDao().findById(jobId) ?: return
        val source = database.sourceDao().findById(job.sourceId)
        val now = System.currentTimeMillis()
        if (source != null) {
            val updatedSource = source.copy(
                processingState = "canceled",
                updatedAt = now,
            )
            database.sourceDao().upsert(updatedSource)
            indexSource(updatedSource, null)
        }
        database.ingestionJobDao().upsert(
            job.copy(
                state = "canceled",
                progress = 1f,
                updatedAt = now,
            ),
        )
    }

    suspend fun sourceInputForJob(jobId: String): String? {
        val job = database.ingestionJobDao().findById(jobId) ?: return null
        val source = database.sourceDao().findById(job.sourceId) ?: return null
        return source.originalUrl
    }

    suspend fun addSourceToCollection(sourceId: String, title: String = "Saved playlist") {
        val source = database.sourceDao().findById(sourceId) ?: return
        val now = System.currentTimeMillis()
        val existing = database.collectionDao().findByTitle(title)
        val collection = existing ?: CollectionEntity(
            id = stableId("collection:$title"),
            title = title,
            type = "playlist",
            filterJson = null,
            createdBy = "user",
            createdAt = now,
            updatedAt = now,
        )
        database.collectionDao().upsert(collection.copy(updatedAt = now))
        database.collectionDao().insertCollectionSource(
            CollectionSourceEntity(
                collectionId = collection.id,
                sourceId = source.id,
                addedAt = now,
            ),
        )
    }

    suspend fun createCollectionDraft(title: String, query: String, sourceIds: List<String>, rationale: String): AgentActionDraft {
        val now = System.currentTimeMillis()
        val distinctIds = sourceIds.distinct()
        val preview = JSONObject()
            .put("title", title)
            .put("query", query)
            .put("sourceIds", JSONArray(distinctIds))
            .put("sourceCount", distinctIds.size)
            .toString()
        val action = AgentActionEntity(
            id = UUID.randomUUID().toString(),
            actionType = "create_collection",
            state = "draft",
            title = title,
            rationale = rationale,
            previewJson = preview,
            undoPayloadJson = null,
            createdAt = now,
            appliedAt = null,
            undoneAt = null,
        )
        database.agentActionDao().upsert(action)
        return action.toDraft()
    }

    suspend fun applyCollectionDraft(actionId: String): AgentActionDraft? {
        val action = database.agentActionDao().findById(actionId) ?: return null
        if (action.state != "draft") return action.toDraft()
        val preview = JSONObject(action.previewJson)
        val title = preview.optString("title").takeIf { it.isNotBlank() } ?: action.title
        val sourceIds = preview.optJSONArray("sourceIds").orEmptyStrings()
        sourceIds.forEach { sourceId -> addSourceToCollection(sourceId, title) }
        val now = System.currentTimeMillis()
        val undo = JSONObject()
            .put("collectionTitle", title)
            .put("sourceIds", JSONArray(sourceIds))
            .toString()
        val applied = action.copy(state = "applied", undoPayloadJson = undo, appliedAt = now)
        database.agentActionDao().upsert(applied)
        return applied.toDraft()
    }

    suspend fun undoCollectionDraft(actionId: String): AgentActionDraft? {
        val action = database.agentActionDao().findById(actionId) ?: return null
        if (action.state != "applied" || action.undoPayloadJson.isNullOrBlank()) return action.toDraft()
        val undo = JSONObject(action.undoPayloadJson)
        val title = undo.optString("collectionTitle")
        val sourceIds = undo.optJSONArray("sourceIds").orEmptyStrings()
        val collection = database.collectionDao().findByTitle(title)
        if (collection != null) {
            sourceIds.forEach { sourceId -> database.collectionDao().deleteCollectionSource(collection.id, sourceId) }
        }
        val undone = action.copy(state = "undone", undoneAt = System.currentTimeMillis())
        database.agentActionDao().upsert(undone)
        return undone.toDraft()
    }

    suspend fun tagSource(sourceId: String, tagName: String = "review") {
        ensureTags(sourceId, listOf(tagName))
        val source = database.sourceDao().findById(sourceId) ?: return
        indexSource(source, null)
    }

    suspend fun deleteSource(sourceId: String): Boolean {
        val source = database.sourceDao().findById(sourceId) ?: return false
        val localFiles = database.assetDao().findBySource(sourceId)
            .mapNotNull { it.localPath }
            .filter { it.isNotBlank() }
            .map(::File)
        database.sourceDao().deleteById(source.id)
        database.sourceSearchDao().deleteForSource(source.id)
        database.chunkSearchDao().deleteForSource(source.id)
        localFiles.forEach { file -> runCatching { file.delete() } }
        return true
    }

    suspend fun authSessionForInput(input: String): AuthSessionEntity? {
        val domain = authDomain(input) ?: return null
        return database.authSessionDao().findByDomain(domain)?.takeIf { it.status == "connected" }
    }

    suspend fun saveAuthSession(provider: String, domain: String, cookieFilePath: String): AuthSessionEntity {
        val now = System.currentTimeMillis()
        val existing = database.authSessionDao().findByDomain(domain)
        val session = AuthSessionEntity(
            id = existing?.id ?: stableId("auth:$domain"),
            provider = provider,
            domain = domain,
            status = "connected",
            cookieFilePath = cookieFilePath,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            lastValidatedAt = null,
            lastError = null,
        )
        database.authSessionDao().upsert(session)
        return session
    }

    suspend fun clearAuthSessionForInput(input: String): String? {
        val domain = authDomain(input) ?: return null
        clearAuthSessionForDomain(domain)
        return domain
    }

    suspend fun clearAuthSessionForDomain(domain: String): String {
        val existing = database.authSessionDao().findByDomain(domain)
        database.authSessionDao().deleteByDomain(domain)
        existing?.cookieFilePath
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { File(it).delete() } }
        return domain
    }

    suspend fun saveLocalVideo(
        stableInput: String,
        fileName: String,
        filePath: String,
        thumbnailPath: String?,
        mimeType: String?,
        durationMs: Long?,
    ): String {
        val now = System.currentTimeMillis()
        val canonicalUrl = canonicalize(stableInput)
        val existing = database.sourceDao().findByCanonicalUrl(canonicalUrl)
        val sourceId = existing?.id ?: stableSourceId(canonicalUrl)
        val title = fileName.takeIf { it.isNotBlank() } ?: "Imported video"
        val durationSeconds = durationMs?.let { (it / 1000L).coerceAtLeast(0L) }
        val raw = """{
  "ok": true,
  "sourceType": "video",
  "extractor": "local_video_import",
  "title": ${title.jsonString()},
  "mimeType": ${mimeType.jsonStringOrNull()},
  "localPath": ${filePath.jsonString()},
  "durationMs": ${durationMs ?: 0}
}"""
        val source = SourceEntity(
            id = sourceId,
            canonicalUrl = canonicalUrl,
            originalUrl = stableInput,
            sourceType = "video",
            originDomain = null,
            title = title,
            author = "Imported video",
            summary = "Local video imported into Mem and ready for in-app playback.",
            thumbnailUrl = null,
            durationSeconds = durationSeconds,
            savedAt = existing?.savedAt ?: now,
            updatedAt = now,
            rightsState = "user_owned",
            authState = "local",
            processingState = "done",
            rawMetadataJson = raw,
        )
        database.sourceDao().upsert(source)
        database.assetDao().upsert(
            AssetEntity(
                id = stableId("asset:${source.id}:playback"),
                sourceId = source.id,
                assetType = "video",
                role = "playback",
                remoteUrl = null,
                localPath = filePath,
                mimeType = mimeType,
                width = null,
                height = null,
                durationMs = durationMs,
                createdAt = now,
            ),
        )
        thumbnailPath?.takeIf { it.isNotBlank() }?.let { path ->
            database.assetDao().upsert(
                AssetEntity(
                    id = stableId("asset:${source.id}:thumbnail"),
                    sourceId = source.id,
                    assetType = "image",
                    role = "thumbnail",
                    remoteUrl = null,
                    localPath = path,
                    mimeType = "image/jpeg",
                    width = null,
                    height = null,
                    durationMs = null,
                    createdAt = now,
                ),
            )
        }
        ensureTags(source.id, listOf("video", "local", "playable", "done"))
        indexSource(source, source.summary)
        return source.id
    }

    suspend fun attachLocalVideoPlayback(
        sourceId: String,
        filePath: String,
        thumbnailPath: String?,
        mimeType: String?,
        durationMs: Long?,
    ): Boolean {
        val source = database.sourceDao().findById(sourceId) ?: return false
        val now = System.currentTimeMillis()
        database.sourceDao().upsert(
            source.copy(
                durationSeconds = durationMs?.let { (it / 1000L).coerceAtLeast(0L) } ?: source.durationSeconds,
                rightsState = "user_owned",
                authState = "authorized",
                processingState = "done",
                updatedAt = now,
            ),
        )
        database.assetDao().upsert(
            AssetEntity(
                id = stableId("asset:${source.id}:playback"),
                sourceId = source.id,
                assetType = "video",
                role = "playback",
                remoteUrl = null,
                localPath = filePath,
                mimeType = mimeType,
                width = null,
                height = null,
                durationMs = durationMs,
                createdAt = now,
            ),
        )
        thumbnailPath?.takeIf { it.isNotBlank() }?.let { path ->
            database.assetDao().upsert(
                AssetEntity(
                    id = stableId("asset:${source.id}:thumbnail"),
                    sourceId = source.id,
                    assetType = "image",
                    role = "thumbnail",
                    remoteUrl = null,
                    localPath = path,
                    mimeType = "image/jpeg",
                    width = null,
                    height = null,
                    durationMs = null,
                    createdAt = now,
                ),
            )
        }
        ensureTags(source.id, listOf("video", "local", "playable", "authorized"))
        indexSource(source.copy(processingState = "done", authState = "authorized"), source.summary)
        return true
    }

    private suspend fun indexSource(source: SourceEntity, extractedText: String?) {
        val durableTags = database.tagDao().tagsForSource(source.id).map { it.name }
        val tags = (listOf(source.processingState, source.sourceType, source.authState, source.originDomain) + durableTags)
            .filterNotNull()
            .joinToString(" ")
        val body = listOfNotNull(
            source.summary,
            source.author,
            source.originDomain,
            source.originalUrl,
            extractedText,
        ).joinToString("\n")
        database.sourceSearchDao().deleteForSource(source.id)
        database.sourceSearchDao().insert(
            SourceSearchEntity(
                sourceId = source.id,
                title = source.title,
                body = body,
                tags = tags,
            ),
        )
    }

    private suspend fun storeChunks(source: SourceEntity, chunks: List<ExtractedContentChunk>, now: Long): String {
        val durableTags = database.tagDao().tagsForSource(source.id).map { it.name }
        val tags = (
            listOf(source.processingState, source.sourceType, source.authState, source.originDomain) +
                durableTags +
                chunks.map { it.chunkType }
            )
            .filterNotNull()
            .joinToString(" ")
        val indexedText = StringBuilder()
        chunks
            .filter { it.text.isNotBlank() }
            .forEachIndexed { index, chunk ->
                val chunkId = stableId("chunk:${source.id}:${chunk.chunkType}:${chunk.startTimeMs}:${chunk.startOffset}:${chunk.text.contentHash()}:$index")
                val entity = DocumentChunkEntity(
                    id = chunkId,
                    sourceId = source.id,
                    text = chunk.text,
                    chunkType = chunk.chunkType,
                    language = chunk.language,
                    startOffset = chunk.startOffset,
                    endOffset = chunk.endOffset,
                    startTimeMs = chunk.startTimeMs,
                    endTimeMs = chunk.endTimeMs,
                    page = chunk.page,
                    sectionTitle = chunk.sectionTitle,
                    provider = chunk.provider,
                    contentHash = chunk.text.contentHash(),
                    createdAt = now,
                )
                database.documentChunkDao().upsert(entity)
                database.chunkSearchDao().insert(
                    ChunkSearchEntity(
                        chunkId = entity.id,
                        sourceId = source.id,
                        title = source.title,
                        body = listOfNotNull(chunk.sectionTitle, chunk.text).joinToString("\n"),
                        tags = tags,
                    ),
                )
                database.chunkEmbeddingDao().upsert(
                    ChunkEmbeddingEntity(
                        id = stableId("embedding:${entity.id}:$LOCAL_EMBEDDING_MODEL_ID"),
                        chunkId = entity.id,
                        sourceId = source.id,
                        modelId = LOCAL_EMBEDDING_MODEL_ID,
                        embeddingType = "text",
                        dimensions = LOCAL_EMBEDDING_DIMENSIONS,
                        vector = localEmbedding(chunk.text).toByteArrayVector(),
                        contentHash = entity.contentHash ?: chunk.text.contentHash(),
                        createdAt = now,
                    ),
                )
                if (indexedText.length < 12_000) {
                    indexedText.append(chunk.text.take(1_200)).append('\n')
                }
            }
        return indexedText.toString()
    }

    private suspend fun ensureLocalEmbeddingModel(now: Long) {
        database.embeddingModelDao().upsert(
            EmbeddingModelEntity(
                id = LOCAL_EMBEDDING_MODEL_ID,
                provider = LOCAL_EMBEDDING_PROVIDER,
                model = LOCAL_EMBEDDING_MODEL,
                embeddingType = "text",
                dimensions = LOCAL_EMBEDDING_DIMENSIONS,
                quantization = "float32",
                status = "local_fallback",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private suspend fun ensureTags(sourceId: String, names: List<String>) {
        val now = System.currentTimeMillis()
        names
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { name ->
                val tag = database.tagDao().findByName(name) ?: TagEntity(
                    id = stableId("tag:$name"),
                    name = name,
                    colorKey = null,
                    createdAt = now,
                )
                database.tagDao().upsert(tag)
                database.tagDao().insertSourceTag(SourceTagEntity(sourceId = sourceId, tagId = tag.id, createdAt = now))
            }
    }
}

private fun String.jsonString(): String = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

private fun String?.jsonStringOrNull(): String = this?.jsonString() ?: "null"

data class MemoryState(
    val sources: List<SourceEntity>,
    val jobs: List<IngestionJobEntity>,
    val collections: List<CollectionSummary>,
    val assets: List<AssetEntity>,
)

data class QueuedSource(
    val sourceId: String,
    val jobId: String,
)

data class ExtractedSourceData(
    val ok: Boolean,
    val canonicalUrl: String,
    val originalUrl: String,
    val sourceType: String,
    val originDomain: String?,
    val title: String,
    val author: String?,
    val summary: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val authRequired: Boolean,
    val error: String?,
    val ragText: String?,
    val rawMetadataJson: String,
    val captionTrack: ExtractedCaptionTrack? = null,
    val chunks: List<ExtractedContentChunk> = emptyList(),
)

data class ExtractedCaptionTrack(
    val language: String?,
    val source: String,
    val format: String?,
    val segmentCount: Int,
    val chunkCount: Int,
)

data class ExtractedContentChunk(
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
)

data class SearchResultData(
    val sourceId: String,
    val chunkId: String,
    val title: String,
    val sourceType: String,
    val originDomain: String?,
    val author: String?,
    val snippet: String,
    val chunkType: String,
    val matchReason: String,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val savedAt: Long,
    val retrievalMode: String,
    val rankScore: Float,
)

data class ContentChunkData(
    val id: String,
    val sourceId: String,
    val text: String,
    val chunkType: String,
    val language: String?,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val page: Int?,
    val sectionTitle: String?,
    val provider: String?,
)

data class AgentActionDraft(
    val id: String,
    val title: String,
    val actionType: String,
    val state: String,
    val rationale: String,
    val sourceCount: Int,
)

data class SourceSnapshot(
    val source: SourceEntity,
    val assets: List<AssetEntity>,
)

fun canonicalize(input: String): String {
    val trimmed = input.trim()
    return runCatching {
        val uri = URI(trimmed)
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching trimmed
        val host = uri.host?.lowercase(Locale.US) ?: return@runCatching trimmed
        val path = uri.rawPath ?: ""
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        "$scheme://$host$path$query".trimEnd('/')
    }.getOrDefault(trimmed)
}

fun originDomain(input: String): String? {
    return runCatching { URI(input.trim()).host?.removePrefix("www.") }.getOrNull()
}

fun authDomain(input: String): String? {
    val host = originDomain(input)?.lowercase(Locale.US) ?: return null
    return when {
        host == "instagram.com" || host.endsWith(".instagram.com") -> "instagram.com"
        host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be" -> "youtube.com"
        else -> host
    }
}

private fun stableSourceId(canonicalUrl: String): String {
    return stableId("source:$canonicalUrl").take(32)
}

private fun stableId(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(32)
}

private fun String.contentHash(): String = stableId("content:$this")

private fun ChunkSearchResult.toSearchResultData(retrievalMode: String, rankScore: Float): SearchResultData {
    val timeLabel = startTimeMs?.let { " at ${it.timestampLabel()}" }
    val prefix = if (retrievalMode == "semantic") "Semantic" else "Keyword"
    val reason = when (chunkType) {
        "transcript" -> "$prefix transcript match${timeLabel.orEmpty()}"
        "transcript_segment" -> "$prefix transcript segment${timeLabel.orEmpty()}"
        "article", "document", "note" -> "$prefix ${chunkType.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} text match"
        "visual" -> "$prefix visual observation${timeLabel.orEmpty()}"
        else -> "$prefix indexed memory match"
    }
    return SearchResultData(
        sourceId = sourceId,
        chunkId = chunkId,
        title = title,
        sourceType = sourceType,
        originDomain = originDomain,
        author = author,
        snippet = body.take(360),
        chunkType = chunkType,
        matchReason = reason,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        savedAt = savedAt,
        retrievalMode = retrievalMode,
        rankScore = rankScore,
    )
}

private fun ChunkEmbeddingCandidate.toSearchResultData(score: Float): SearchResultData {
    val timeLabel = startTimeMs?.let { " at ${it.timestampLabel()}" }
    val reason = when (chunkType) {
        "transcript" -> "Semantic transcript match${timeLabel.orEmpty()}"
        "article", "document", "note" -> "Semantic ${chunkType.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} match"
        "visual" -> "Semantic visual match${timeLabel.orEmpty()}"
        else -> "Semantic indexed memory match"
    }
    return SearchResultData(
        sourceId = sourceId,
        chunkId = chunkId,
        title = title,
        sourceType = sourceType,
        originDomain = originDomain,
        author = author,
        snippet = body.take(360),
        chunkType = chunkType,
        matchReason = reason,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        savedAt = savedAt,
        retrievalMode = "semantic",
        rankScore = score,
    )
}

private fun localEmbedding(text: String): FloatArray {
    val vector = FloatArray(LOCAL_EMBEDDING_DIMENSIONS)
    tokenizeForEmbedding(text).forEach { token ->
        val hash = stableId("embed:$token")
        val bucket = hash.take(8).toLong(16).mod(LOCAL_EMBEDDING_DIMENSIONS)
        val sign = if (hash.drop(8).take(2).toInt(16) % 2 == 0) 1f else -1f
        val weight = when {
            token.length > 10 -> 1.35f
            token.length > 6 -> 1.15f
            else -> 1f
        }
        vector[bucket] += sign * weight
    }
    normalizeInPlace(vector)
    return vector
}

private fun tokenizeForEmbedding(text: String): List<String> {
    val stop = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "your", "you", "are", "was", "were",
        "have", "has", "had", "not", "but", "about", "what", "when", "where", "how", "why", "can", "will",
    )
    return text
        .lowercase(Locale.US)
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 && it !in stop }
        .flatMap { token ->
            buildList {
                add(token)
                simpleStem(token)?.let(::add)
            }
        }
}

private fun simpleStem(token: String): String? {
    return when {
        token.endsWith("ing") && token.length > 6 -> token.dropLast(3)
        token.endsWith("ed") && token.length > 5 -> token.dropLast(2)
        token.endsWith("s") && token.length > 4 -> token.dropLast(1)
        else -> null
    }?.takeIf { it.length >= 3 && it != token }
}

private fun normalizeInPlace(vector: FloatArray) {
    var sum = 0f
    vector.forEach { sum += it * it }
    val norm = sqrt(sum)
    if (norm <= 0f) return
    for (index in vector.indices) {
        vector[index] = vector[index] / norm
    }
}

private fun cosineSimilarity(left: FloatArray, right: FloatArray): Float {
    val size = minOf(left.size, right.size)
    var score = 0f
    for (index in 0 until size) {
        score += left[index] * right[index]
    }
    return score
}

private fun FloatArray.toByteArrayVector(): ByteArray {
    val buffer = ByteBuffer.allocate(size * 4).order(ByteOrder.LITTLE_ENDIAN)
    forEach(buffer::putFloat)
    return buffer.array()
}

private fun ByteArray.toFloatVector(dimensions: Int): FloatArray {
    val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
    val values = FloatArray(dimensions)
    for (index in 0 until dimensions) {
        if (buffer.remaining() >= 4) values[index] = buffer.float
    }
    return values
}

private fun Long.timestampLabel(): String {
    val totalSeconds = (this / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun toFtsQuery(query: String): String {
    val parsed = parseSearchQuery(query)
    return (parsed.phrases + parsed.freeTerms + parsed.filterTerms)
        .map { token -> token.filter { it.isLetterOrDigit() || it == '_' || it == '-' } }
        .filter { it.length >= 2 }
        .distinct()
        .joinToString(" ") { "$it*" }
}

private fun AgentActionEntity.toDraft(): AgentActionDraft {
    val preview = runCatching { JSONObject(previewJson) }.getOrNull()
    return AgentActionDraft(
        id = id,
        title = title,
        actionType = actionType,
        state = state,
        rationale = rationale,
        sourceCount = preview?.optInt("sourceCount", 0) ?: 0,
    )
}

private fun JSONArray?.orEmptyStrings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun parsedSearchJson(query: String): String {
    val parsed = parseSearchQuery(query)
    return JSONObject()
        .put("phrases", JSONArray(parsed.phrases))
        .put("freeTerms", JSONArray(parsed.freeTerms))
        .put("filterTerms", JSONArray(parsed.filterTerms))
        .toString()
}

private data class ParsedSearchQuery(
    val phrases: List<String>,
    val freeTerms: List<String>,
    val filterTerms: List<String>,
)

private fun parseSearchQuery(query: String): ParsedSearchQuery {
    val freeTerms = mutableListOf<String>()
    val filterTerms = mutableListOf<String>()
    val phrases = Regex("\"([^\"]+)\"")
        .findAll(query)
        .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotBlank) }
        .toList()
    val withoutPhrases = query.replace(Regex("\"([^\"]+)\""), " ")
    withoutPhrases
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .forEach { raw ->
            val token = raw.trim().trim('"')
            if (token.startsWith("-")) return@forEach
            val parts = token.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].lowercase(Locale.US)
                val value = parts[1].lowercase(Locale.US).trim()
                when (key) {
                    "type" -> filterTerms.add(value)
                    "site", "domain" -> filterTerms.add(value.removePrefix("www."))
                    "status" -> filterTerms.add(value)
                    "has" -> when (value) {
                        "transcript" -> filterTerms.add("transcript")
                        "visual" -> filterTerms.add("visual")
                        "local_video" -> filterTerms.add("playable")
                        "auth" -> filterTerms.add("needs")
                        else -> filterTerms.add(value)
                    }
                    "tag", "collection", "author", "channel", "language" -> filterTerms.add(value)
                    "duration", "saved", "date" -> Unit
                    else -> freeTerms.add(value)
                }
            } else {
                freeTerms.add(token)
            }
        }
    return ParsedSearchQuery(phrases, freeTerms, filterTerms)
}
