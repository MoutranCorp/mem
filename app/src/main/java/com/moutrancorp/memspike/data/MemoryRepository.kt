package com.moutrancorp.memspike.data

import com.moutrancorp.memspike.ai.AgentToolCall
import com.moutrancorp.memspike.ai.AgentToolResult
import com.moutrancorp.memspike.ai.AgentRuntime
import com.moutrancorp.memspike.ai.DeterministicToolAgentRuntime
import com.moutrancorp.memspike.ai.EmbeddingInput
import com.moutrancorp.memspike.ai.EmbeddingProvider
import com.moutrancorp.memspike.ai.EmbeddingVector
import com.moutrancorp.memspike.ai.LocalHashEmbeddingProvider
import com.moutrancorp.memspike.ai.VectorHit
import com.moutrancorp.memspike.ai.VectorIndex
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
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

private const val LOCAL_EMBEDDING_MODEL_ID = "local_hash_v1_text_128"
private const val LOCAL_EMBEDDING_PROVIDER = "local"
private const val LOCAL_EMBEDDING_MODEL = "hash-v1-text-128"
private const val LOCAL_EMBEDDING_DIMENSIONS = 128
private const val LOCAL_SEMANTIC_EXACT_SCAN_LIMIT = 10_000
private const val FILTER_ONLY_SCAN_LIMIT = 10_000
private const val SEARCH_RESULT_LIMIT = 40

class MemoryRepository(private val database: MemDatabase) {
    private val embeddingProvider: EmbeddingProvider = LocalHashEmbeddingProvider()
    private val vectorIndex: VectorIndex = RoomExactScanVectorIndex(database.chunkEmbeddingDao(), LOCAL_SEMANTIC_EXACT_SCAN_LIMIT)
    private val agentRuntime: AgentRuntime = DeterministicToolAgentRuntime()

    fun availableMemoryTools(): List<String> {
        return listOf(
            "search_memory",
            "get_source_context",
            "get_transcript",
            "get_visual_observations",
            "explain_result",
            "summarize_source",
            "draft_collection",
            "tag_sources",
        )
    }

    suspend fun runMemoryTool(call: AgentToolCall): AgentToolResult {
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrElse { JSONObject() }
        val result = when (call.name) {
            "search_memory" -> runSearchMemoryTool(args)
            "get_source_context" -> runGetSourceContextTool(args)
            "get_transcript" -> runGetTranscriptTool(args)
            "get_visual_observations" -> runGetVisualObservationsTool(args)
            "explain_result" -> runExplainResultTool(args)
            "summarize_source" -> runSummarizeSourceTool(args)
            "draft_collection" -> runDraftCollectionTool(args)
            "tag_sources" -> runTagSourcesTool(args)
            else -> JSONObject()
                .put("ok", false)
                .put("error", "Unknown memory tool: ${call.name}")
        }
        return AgentToolResult(call = call, resultJson = result.toString())
    }

    suspend fun answerMemoryRequest(request: String): AgentAnswerData {
        val resultJson = agentRuntime.answerWithTools(
            userRequest = request,
            availableTools = availableMemoryTools(),
            toolRunner = ::runMemoryTool,
        )
        val result = runCatching { JSONObject(resultJson) }.getOrElse {
            JSONObject()
                .put("ok", false)
                .put("answer", "Agent runtime returned invalid JSON.")
                .put("citationCount", 0)
                .put("sourceCount", 0)
        }
        return AgentAnswerData(
            ok = result.optBoolean("ok", false),
            runtime = result.optString("runtime", "unknown"),
            answer = result.optString("answer", result.optString("error", "")),
            citationCount = result.optInt("citationCount", 0),
            sourceCount = result.optInt("sourceCount", 0),
            usedTools = result.optJSONArray("usedTools").orEmptyStrings(),
            rawJson = resultJson,
        )
    }

    suspend fun ragIndexHealth(): RagIndexHealth {
        val sourceCount = database.sourceDao().countAll()
        val chunkCount = database.documentChunkDao().countAll()
        val embeddedChunkCount = database.chunkEmbeddingDao().countByType("text")
        val coverage = if (chunkCount == 0) 0f else embeddedChunkCount.toFloat() / chunkCount.toFloat()
        return RagIndexHealth(
            sourceCount = sourceCount,
            needsAuthSourceCount = database.sourceDao().countNeedsAuth(),
            metadataOnlySourceCount = database.documentChunkDao().countMetadataOnlySources(),
            transcriptReadySourceCount = database.documentChunkDao()
                .countSourcesWithAnyType(listOf("transcript", "transcript_segment")),
            visuallyIndexedSourceCount = database.documentChunkDao().countSourcesWithAnyType(listOf("visual")),
            indexedChunkCount = chunkCount,
            chunkSearchRowCount = database.chunkSearchDao().countAll(),
            transcriptChunkCount = database.documentChunkDao().countByType("transcript"),
            articleChunkCount = database.documentChunkDao().countByType("article"),
            documentChunkCount = database.documentChunkDao().countByType("document"),
            noteChunkCount = database.documentChunkDao().countByType("note"),
            visualChunkCount = database.documentChunkDao().countByType("visual"),
            timestampedChunkCount = database.documentChunkDao().countTimestamped(),
            captionTrackCount = database.captionTrackDao().countAll(),
            visualObservationCount = database.visualObservationDao().countAll(),
            embeddedChunkCount = embeddedChunkCount,
            embeddingCoverage = coverage,
            semanticCandidateWindow = LOCAL_SEMANTIC_EXACT_SCAN_LIMIT,
            playbackAssetCount = database.assetDao().countByRole("playback"),
            searchQueryCount = database.searchQueryDao().countAll(),
            agentActionCount = database.agentActionDao().countAll(),
        )
    }

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
        val parsed = parseSearchQuery(rawQuery)
        val ftsQuery = toFtsQuery(rawQuery)
        val ftsResults = if (ftsQuery.isBlank()) {
            if (parsed.hasStructuredFilters()) {
                val candidates = database.chunkEmbeddingDao()
                    .candidates("text", FILTER_ONLY_SCAN_LIMIT)
                candidates
                    .mapIndexed { index, candidate ->
                        candidate.toSearchResultData(score = 1.0f / (index + 1)).copy(
                            retrievalMode = "filter",
                            matchReason = "Structured filter match",
                            rankSignals = "filter; window=$FILTER_ONLY_SCAN_LIMIT; scanned=${candidates.size}",
                        )
                    }
            } else {
                emptyList()
            }
        } else {
            database.chunkSearchDao().searchResults(ftsQuery, 120).mapIndexed { index, result ->
                result.toSearchResultData(
                    retrievalMode = "keyword",
                    rankScore = 1.0f / (index + 1),
                )
            }
        }
        val semanticResults = semanticSearch(rawQuery, 120)
        val filterContext = searchFilterContext(parsed)
        val fused = fuseSearchResults(ftsResults, semanticResults, parsed, filterContext).take(SEARCH_RESULT_LIMIT)
        database.searchQueryDao().insert(
            SearchQueryEntity(
                id = UUID.randomUUID().toString(),
                query = rawQuery,
                parsedFiltersJson = parsed.toJson(filterContext),
                resultCount = fused.size,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return fused
    }

    private suspend fun semanticSearch(rawQuery: String, limit: Int): List<SearchResultData> {
        val queryVector = embeddingProvider.embed(
            listOf(EmbeddingInput(id = "query:${stableId(rawQuery)}", text = rawQuery, type = "text")),
        ).firstOrNull() ?: return emptyList()
        val hits = vectorIndex.search(queryVector, limit)
        if (hits.isEmpty()) return emptyList()
        val byChunkId = database.chunkEmbeddingDao()
            .candidatesByChunkIds("text", hits.map { it.id })
            .associateBy { it.chunkId }
        return hits
            .mapNotNull { hit ->
                val candidate = byChunkId[hit.id] ?: return@mapNotNull null
                candidate.toSearchResultData(hit.score).copy(
                    rankSignals = buildString {
                        append("semantic:${"%.2f".format(hit.score)}")
                        append("; provider=${hit.provider}")
                        append("; model=${hit.model}")
                        hit.diagnostics["index"]?.let { append("; index=$it") }
                        hit.diagnostics["window"]?.let { append("; window=$it") }
                        hit.diagnostics["scanned"]?.let { append("; scanned=$it") }
                    },
                )
            }
            .take(limit)
    }

    private suspend fun searchFilterContext(parsed: ParsedSearchQuery): SearchFilterContext {
        val tagSourceIds = if (parsed.tagFilters.isEmpty()) {
            null
        } else {
            database.tagDao()
                .sourceIdsWithAllTagNames(parsed.tagFilters.toList(), parsed.tagFilters.size)
                .toSet()
        }
        val collectionSourceIds = if (parsed.collectionFilters.isEmpty()) {
            null
        } else {
            database.collectionDao()
                .sourceIdsInAllCollections(parsed.collectionFilters.toList(), parsed.collectionFilters.size)
                .toSet()
        }
        val assetSourceIds = if (parsed.requiredAssetRoles.isEmpty()) {
            null
        } else {
            database.assetDao()
                .sourceIdsWithAllRoles(parsed.requiredAssetRoles.toList(), parsed.requiredAssetRoles.size)
                .toSet()
        }
        return SearchFilterContext(
            tagSourceIds = tagSourceIds,
            collectionSourceIds = collectionSourceIds,
            assetSourceIds = assetSourceIds,
        )
    }

    private fun fuseSearchResults(
        keyword: List<SearchResultData>,
        semantic: List<SearchResultData>,
        parsed: ParsedSearchQuery,
        filterContext: SearchFilterContext,
    ): List<SearchResultData> {
        val byChunk = linkedMapOf<String, SearchResultData>()
        (keyword + semantic)
            .filter { parsed.matches(it, filterContext) }
            .forEachIndexed { index, result ->
            val existing = byChunk[result.chunkId]
            val sourceDedupePenalty = byChunk.values.count { it.sourceId == result.sourceId } * 0.03f
            val recencyBoost = 1.0f / (1 + index)
            val filterBoost = parsed.rankBoost(result, filterContext)
            val score = result.rankScore + recencyBoost + filterBoost - sourceDedupePenalty
            val ranked = result.copy(
                rankScore = score,
                rankSignals = "mode=${result.retrievalMode}; base=${"%.2f".format(result.rankScore)}; recency=${"%.2f".format(recencyBoost)}; filters=${"%.2f".format(filterBoost)}",
            )
            if (existing == null || score > existing.rankScore) {
                byChunk[result.chunkId] = ranked
            } else if (existing.retrievalMode != result.retrievalMode) {
                byChunk[result.chunkId] = existing.copy(
                    retrievalMode = "hybrid",
                    matchReason = existing.matchReason.replace("Keyword", "Hybrid").replace("Semantic", "Hybrid"),
                    rankScore = existing.rankScore + 0.25f,
                    rankSignals = existing.rankSignals + "; hybrid=true",
                )
            }
        }
        return byChunk.values.sortedByDescending { it.rankScore }
    }

    suspend fun chunksForSource(sourceId: String, limit: Int = 80): List<ContentChunkData> {
        return database.documentChunkDao().findBySource(sourceId, limit).map { chunk ->
            chunk.toContentChunkData()
        }
    }

    private suspend fun transcriptChunksForSource(sourceId: String, limit: Int = 120): List<ContentChunkData> {
        return database.documentChunkDao()
            .findBySourceAndTypes(sourceId, listOf("transcript", "transcript_segment"), limit)
            .map { it.toContentChunkData() }
    }

    private suspend fun visualChunksForSource(sourceId: String, limit: Int = 80): List<ContentChunkData> {
        return database.documentChunkDao()
            .findBySourceAndTypes(sourceId, listOf("visual"), limit)
            .map { it.toContentChunkData() }
    }

    suspend fun sourceSnapshot(sourceId: String): SourceSnapshot? {
        val source = database.sourceDao().findById(sourceId) ?: return null
        val assets = database.assetDao().findBySource(sourceId)
        return SourceSnapshot(
            source = source,
            assets = assets,
            contentProfile = contentProfileForSource(source, assets),
        )
    }

    private suspend fun contentProfileForSource(source: SourceEntity, assets: List<AssetEntity>): SourceContentProfile {
        val chunkDao = database.documentChunkDao()
        val chunkCount = chunkDao.countBySource(source.id)
        val transcriptChunks = chunkDao.countBySourceAndTypes(source.id, listOf("transcript", "transcript_segment"))
        val articleChunks = chunkDao.countBySourceAndTypes(source.id, listOf("article"))
        val documentChunks = chunkDao.countBySourceAndTypes(source.id, listOf("document", "rag_text"))
        val noteChunks = chunkDao.countBySourceAndTypes(source.id, listOf("note"))
        val metadataChunks = chunkDao.countBySourceAndTypes(source.id, listOf("metadata"))
        val visualChunks = chunkDao.countBySourceAndTypes(source.id, listOf("visual"))
        val timestampedChunks = chunkDao.countTimestampedBySource(source.id)
        val captionTracks = database.captionTrackDao().countBySource(source.id)
        val visualObservations = database.visualObservationDao().countBySource(source.id)
        return SourceContentProfile(
            chunkCount = chunkCount,
            transcriptChunkCount = transcriptChunks,
            articleChunkCount = articleChunks,
            documentChunkCount = documentChunks,
            noteChunkCount = noteChunks,
            metadataChunkCount = metadataChunks,
            visualChunkCount = visualChunks,
            timestampedChunkCount = timestampedChunks,
            captionTrackCount = captionTracks,
            visualObservationCount = visualObservations,
            assetRoles = assets.map { it.role }.distinct(),
            processingState = source.processingState,
            authState = source.authState,
        )
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

    suspend fun createTagDraft(sourceIds: List<String>, tagNames: List<String>, rationale: String): AgentActionDraft {
        val distinctIds = sourceIds.distinct()
        val normalizedTags = tagNames.mapNotNull { it.trim().lowercase(Locale.US).takeIf(String::isNotBlank) }.distinct()
        val preview = JSONObject()
            .put("sourceIds", JSONArray(distinctIds))
            .put("tags", JSONArray(normalizedTags))
            .put("sourceCount", distinctIds.size)
            .toString()
        val action = AgentActionEntity(
            id = UUID.randomUUID().toString(),
            actionType = "tag_sources",
            state = "draft",
            title = "Tag ${distinctIds.size} source${if (distinctIds.size == 1) "" else "s"}",
            rationale = rationale,
            previewJson = preview,
            undoPayloadJson = null,
            createdAt = System.currentTimeMillis(),
            appliedAt = null,
            undoneAt = null,
        )
        database.agentActionDao().upsert(action)
        return action.toDraft()
    }

    suspend fun applyTagDraft(actionId: String): AgentActionDraft? {
        val action = database.agentActionDao().findById(actionId) ?: return null
        if (action.state != "draft") return action.toDraft()
        val preview = JSONObject(action.previewJson)
        val sourceIds = preview.optJSONArray("sourceIds").orEmptyStrings()
        val tagNames = preview.optJSONArray("tags").orEmptyStrings()
        val createdMemberships = JSONArray()
        sourceIds.forEach { sourceId ->
            tagNames.forEach { tagName ->
                val tag = ensureTag(tagName)
                val existed = database.tagDao().findSourceTag(sourceId, tag.id) != null
                database.tagDao().insertSourceTag(
                    SourceTagEntity(sourceId = sourceId, tagId = tag.id, createdAt = System.currentTimeMillis()),
                )
                if (!existed) createdMemberships.put(JSONObject().put("sourceId", sourceId).put("tagId", tag.id))
            }
            database.sourceDao().findById(sourceId)?.let { indexSource(it, null) }
        }
        val applied = action.copy(
            state = "applied",
            undoPayloadJson = JSONObject().put("createdMemberships", createdMemberships).toString(),
            appliedAt = System.currentTimeMillis(),
        )
        database.agentActionDao().upsert(applied)
        return applied.toDraft()
    }

    suspend fun undoTagDraft(actionId: String): AgentActionDraft? {
        val action = database.agentActionDao().findById(actionId) ?: return null
        if (action.state != "applied" || action.undoPayloadJson.isNullOrBlank()) return action.toDraft()
        val createdMemberships = JSONObject(action.undoPayloadJson).optJSONArray("createdMemberships")
        val reindexSourceIds = mutableSetOf<String>()
        if (createdMemberships != null) {
            for (index in 0 until createdMemberships.length()) {
                val membership = createdMemberships.optJSONObject(index) ?: continue
                val sourceId = membership.optString("sourceId")
                val tagId = membership.optString("tagId")
                if (sourceId.isNotBlank() && tagId.isNotBlank()) {
                    database.tagDao().deleteSourceTag(sourceId, tagId)
                    reindexSourceIds.add(sourceId)
                }
            }
        }
        reindexSourceIds.forEach { sourceId -> database.sourceDao().findById(sourceId)?.let { indexSource(it, null) } }
        val undone = action.copy(state = "undone", undoneAt = System.currentTimeMillis())
        database.agentActionDao().upsert(undone)
        return undone.toDraft()
    }

    private suspend fun runSearchMemoryTool(args: JSONObject): JSONObject {
        val query = args.optString("query").trim()
        val limit = args.optInt("limit", 8).coerceIn(1, 20)
        if (query.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "query is required")
        }
        val results = searchMemory(query).take(limit)
        return JSONObject()
            .put("ok", true)
            .put("tool", "search_memory")
            .put("query", query)
            .put("resultCount", results.size)
            .put("citations", JSONArray(results.map { it.toToolCitationJson() }))
    }

    private suspend fun runGetSourceContextTool(args: JSONObject): JSONObject {
        val sourceId = args.optString("sourceId").trim()
        val limit = args.optInt("limit", 12).coerceIn(1, 40)
        val snapshot = sourceSnapshot(sourceId) ?: return JSONObject()
            .put("ok", false)
            .put("error", "source not found")
        val chunks = chunksForSource(sourceId, limit)
        return JSONObject()
            .put("ok", true)
            .put("tool", "get_source_context")
            .put("source", snapshot.toToolSourceJson())
            .put("chunks", JSONArray(chunks.map { it.toToolChunkJson() }))
    }

    private suspend fun runGetTranscriptTool(args: JSONObject): JSONObject {
        val sourceId = args.optString("sourceId").trim()
        val limit = args.optInt("limit", 40).coerceIn(1, 160)
        val snapshot = sourceSnapshot(sourceId) ?: return JSONObject()
            .put("ok", false)
            .put("error", "source not found")
        val tracks = database.captionTrackDao().findBySource(sourceId)
        val chunks = transcriptChunksForSource(sourceId, limit)
        return JSONObject()
            .put("ok", true)
            .put("tool", "get_transcript")
            .put("source", snapshot.toToolSourceJson())
            .put("hasTranscript", chunks.isNotEmpty())
            .put("captionTracks", JSONArray(tracks.map { it.toToolCaptionTrackJson() }))
            .put("segments", JSONArray(chunks.map { it.toToolChunkJson(textLimit = 1_200) }))
            .put(
                "coverageNote",
                if (chunks.isEmpty()) {
                    "No transcript chunks are indexed for this source yet; answers should treat it as metadata-only unless other chunks are available."
                } else {
                    "Transcript chunks are timestamped where the source provided timing; cite chunkId and startTimeMs when answering."
                },
            )
    }

    private suspend fun runGetVisualObservationsTool(args: JSONObject): JSONObject {
        val sourceId = args.optString("sourceId").trim()
        val limit = args.optInt("limit", 40).coerceIn(1, 120)
        val snapshot = sourceSnapshot(sourceId) ?: return JSONObject()
            .put("ok", false)
            .put("error", "source not found")
        val observations = database.visualObservationDao().findBySource(sourceId).take(limit)
        val chunks = visualChunksForSource(sourceId, limit)
        return JSONObject()
            .put("ok", true)
            .put("tool", "get_visual_observations")
            .put("source", snapshot.toToolSourceJson())
            .put("hasVisualObservations", observations.isNotEmpty() || chunks.isNotEmpty())
            .put("observations", JSONArray(observations.map { it.toToolVisualObservationJson() }))
            .put("chunks", JSONArray(chunks.map { it.toToolChunkJson(textLimit = 1_000) }))
            .put(
                "coverageNote",
                if (observations.isEmpty() && chunks.isEmpty()) {
                    "No visual observations are indexed for this source yet; visual-event answers should say the source has not been visually analyzed."
                } else {
                    "Current local observations may be frame-sample placeholders unless provider/model identify a vision analyzer; cite timestamps and avoid claiming unseen events."
                },
            )
    }

    private suspend fun runExplainResultTool(args: JSONObject): JSONObject {
        val query = args.optString("query").trim()
        val sourceId = args.optString("sourceId").trim()
        val chunkId = args.optString("chunkId").trim()
        if (query.isBlank() || sourceId.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "query and sourceId are required")
        }
        val results = searchMemory(query)
        val result = results.firstOrNull { it.sourceId == sourceId && (chunkId.isBlank() || it.chunkId == chunkId) }
            ?: return JSONObject()
                .put("ok", false)
                .put("error", "result not found for query")
                .put("query", query)
                .put("sourceId", sourceId)
                .put("chunkId", chunkId)
        val chunk = database.documentChunkDao().findById(result.chunkId)?.toContentChunkData()
        return JSONObject()
            .put("ok", true)
            .put("tool", "explain_result")
            .put("query", query)
            .put("citation", result.toToolCitationJson())
            .put("rankSignals", result.rankSignals)
            .put("retrievalMode", result.retrievalMode)
            .put("matchReason", result.matchReason)
            .put("explanation", explainSearchResult(result, query))
            .put("chunk", chunk?.toToolChunkJson(textLimit = 1_200))
    }

    private suspend fun runSummarizeSourceTool(args: JSONObject): JSONObject {
        val sourceId = args.optString("sourceId").trim()
        val snapshot = sourceSnapshot(sourceId) ?: return JSONObject()
            .put("ok", false)
            .put("error", "source not found")
        val chunks = chunksForSource(sourceId, 8)
        val summary = deterministicSourceSummary(snapshot, chunks)
        return JSONObject()
            .put("ok", true)
            .put("tool", "summarize_source")
            .put("source", snapshot.toToolSourceJson())
            .put("summary", summary)
            .put("citationChunkIds", JSONArray(chunks.take(4).map { it.id }))
    }

    private suspend fun runDraftCollectionTool(args: JSONObject): JSONObject {
        val query = args.optString("query").trim()
        val title = args.optString("title").trim().takeIf { it.isNotBlank() }
            ?: if (query.isBlank()) "Agent collection" else "Search: ${query.take(42)}"
        val sourceIds = args.optJSONArray("sourceIds").orEmptyStrings()
        val rationale = args.optString("rationale").takeIf { it.isNotBlank() }
            ?: "Drafted from agent-selected memory citations."
        if (sourceIds.isEmpty()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "sourceIds are required")
        }
        val draft = createCollectionDraft(title = title, query = query, sourceIds = sourceIds, rationale = rationale)
        return JSONObject()
            .put("ok", true)
            .put("tool", "draft_collection")
            .put("actionId", draft.id)
            .put("state", draft.state)
            .put("title", draft.title)
            .put("sourceCount", draft.sourceCount)
            .put("requiresApproval", true)
    }

    private suspend fun runTagSourcesTool(args: JSONObject): JSONObject {
        val sourceIds = args.optJSONArray("sourceIds").orEmptyStrings()
        val tags = args.optJSONArray("tags").orEmptyStrings().ifEmpty {
            args.optString("tag").takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
        }
        val rationale = args.optString("rationale").takeIf { it.isNotBlank() }
            ?: "Drafted from agent-selected memory sources."
        if (sourceIds.isEmpty() || tags.isEmpty()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "sourceIds and tags are required")
        }
        val draft = createTagDraft(sourceIds = sourceIds, tagNames = tags, rationale = rationale)
        return JSONObject()
            .put("ok", true)
            .put("tool", "tag_sources")
            .put("actionId", draft.id)
            .put("state", draft.state)
            .put("title", draft.title)
            .put("sourceCount", draft.sourceCount)
            .put("requiresApproval", true)
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
        val visualText = indexPlayableVideoVisualContext(
            source = source,
            playbackAssetId = stableId("asset:${source.id}:playback"),
            thumbnailPath = thumbnailPath,
            durationMs = durationMs,
            now = now,
        )
        indexSource(source, listOfNotNull(source.summary, visualText).joinToString("\n\n"))
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
        val updated = source.copy(processingState = "done", authState = "authorized")
        val visualText = indexPlayableVideoVisualContext(
            source = updated,
            playbackAssetId = stableId("asset:${source.id}:playback"),
            thumbnailPath = thumbnailPath,
            durationMs = durationMs,
            now = now,
        )
        indexSource(updated, listOfNotNull(source.summary, visualText).joinToString("\n\n"))
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
                vectorIndex.upsert(
                    embeddingProvider.embed(
                        listOf(
                            EmbeddingInput(
                                id = entity.id,
                                text = chunk.text,
                                type = "text",
                                metadata = mapOf(
                                    "sourceId" to source.id,
                                    "modelId" to LOCAL_EMBEDDING_MODEL_ID,
                                    "embeddingType" to "text",
                                    "contentHash" to (entity.contentHash ?: chunk.text.contentHash()),
                                    "createdAt" to now.toString(),
                                ),
                            ),
                        ),
                    ),
                )
                if (indexedText.length < 12_000) {
                    indexedText.append(chunk.text.take(1_200)).append('\n')
                }
            }
        return indexedText.toString()
    }

    private suspend fun indexPlayableVideoVisualContext(
        source: SourceEntity,
        playbackAssetId: String,
        thumbnailPath: String?,
        durationMs: Long?,
        now: Long,
    ): String {
        database.visualObservationDao().deleteForSource(source.id)
        database.documentChunkDao().findBySourceAndType(source.id, "visual").forEach { chunk ->
            database.chunkSearchDao().deleteForChunk(chunk.id)
            database.chunkEmbeddingDao().deleteForChunk(chunk.id)
        }
        database.documentChunkDao().deleteForSourceAndTypes(source.id, listOf("visual"))
        ensureLocalEmbeddingModel(now)
        val sampleTimes = visualSampleTimes(durationMs)
        val observations = sampleTimes.mapIndexed { index, timeMs ->
            VisualObservationEntity(
                id = stableId("visual:${source.id}:frame_sample:$timeMs"),
                sourceId = source.id,
                assetId = playbackAssetId,
                observationType = "frame_sample",
                text = visualObservationText(source, timeMs, durationMs, thumbnailPath, index),
                confidence = 0.42f,
                provider = "local_frame_sampler",
                model = "metadata-frame-v1",
                startTimeMs = timeMs,
                endTimeMs = timeMs,
                createdAt = now,
            )
        }
        observations.forEach { database.visualObservationDao().upsert(it) }
        val chunks = observations.map { observation ->
            ExtractedContentChunk(
                text = observation.text,
                chunkType = "visual",
                language = null,
                startOffset = null,
                endOffset = null,
                startTimeMs = observation.startTimeMs,
                endTimeMs = observation.endTimeMs,
                page = null,
                sectionTitle = "Visual observation",
                provider = observation.provider,
            )
        }
        return storeChunks(source, chunks, now)
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
                val tag = ensureTag(name)
                database.tagDao().insertSourceTag(SourceTagEntity(sourceId = sourceId, tagId = tag.id, createdAt = now))
            }
    }

    private suspend fun ensureTag(name: String): TagEntity {
        val normalized = name.trim().lowercase(Locale.US)
        val tag = database.tagDao().findByName(normalized) ?: TagEntity(
            id = stableId("tag:$normalized"),
            name = normalized,
            colorKey = null,
            createdAt = System.currentTimeMillis(),
        )
        database.tagDao().upsert(tag)
        return tag
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

data class RagIndexHealth(
    val sourceCount: Int,
    val needsAuthSourceCount: Int,
    val metadataOnlySourceCount: Int,
    val transcriptReadySourceCount: Int,
    val visuallyIndexedSourceCount: Int,
    val indexedChunkCount: Int,
    val chunkSearchRowCount: Int,
    val transcriptChunkCount: Int,
    val articleChunkCount: Int,
    val documentChunkCount: Int,
    val noteChunkCount: Int,
    val visualChunkCount: Int,
    val timestampedChunkCount: Int,
    val captionTrackCount: Int,
    val visualObservationCount: Int,
    val embeddedChunkCount: Int,
    val embeddingCoverage: Float,
    val semanticCandidateWindow: Int,
    val playbackAssetCount: Int,
    val searchQueryCount: Int,
    val agentActionCount: Int,
) {
    val embeddingCoveragePercent: Int
        get() = (embeddingCoverage * 100f).toInt().coerceIn(0, 100)

    val warnings: List<String>
        get() = buildList {
            if (sourceCount > 0 && indexedChunkCount == 0) add("No indexed chunks yet")
            if (metadataOnlySourceCount > 0) add("$metadataOnlySourceCount metadata-only source${if (metadataOnlySourceCount == 1) "" else "s"}")
            if (indexedChunkCount > 0 && embeddingCoverage < 0.95f) add("Embedding coverage below 95%")
            if (embeddedChunkCount > semanticCandidateWindow) add("Semantic fallback scans newest $semanticCandidateWindow embeddings")
            if (playbackAssetCount > 0 && visualObservationCount == 0) add("Playable videos have no visual observations")
            if (needsAuthSourceCount > 0) add("$needsAuthSourceCount auth-gated source${if (needsAuthSourceCount == 1) "" else "s"}")
        }
}

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
    val durationSeconds: Long?,
    val processingState: String,
    val authState: String,
    val contentDepth: String,
    val snippet: String,
    val chunkType: String,
    val language: String?,
    val matchReason: String,
    val startTimeMs: Long?,
    val endTimeMs: Long?,
    val savedAt: Long,
    val retrievalMode: String,
    val rankScore: Float,
    val rankSignals: String,
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

data class AgentAnswerData(
    val ok: Boolean,
    val runtime: String,
    val answer: String,
    val citationCount: Int,
    val sourceCount: Int,
    val usedTools: List<String>,
    val rawJson: String,
)

data class SourceSnapshot(
    val source: SourceEntity,
    val assets: List<AssetEntity>,
    val contentProfile: SourceContentProfile,
)

data class SourceContentProfile(
    val chunkCount: Int,
    val transcriptChunkCount: Int,
    val articleChunkCount: Int,
    val documentChunkCount: Int,
    val noteChunkCount: Int,
    val metadataChunkCount: Int,
    val visualChunkCount: Int,
    val timestampedChunkCount: Int,
    val captionTrackCount: Int,
    val visualObservationCount: Int,
    val assetRoles: List<String>,
    val processingState: String,
    val authState: String,
) {
    val contentDepth: String
        get() = when {
            authState == "needs_auth" || processingState == "needs_auth" -> "auth_required"
            transcriptChunkCount > 0 && visualChunkCount > 0 -> "transcript_visual"
            transcriptChunkCount > 0 -> "transcript"
            visualChunkCount > 0 -> "visual"
            articleChunkCount > 0 -> "article"
            documentChunkCount > 0 -> "document"
            noteChunkCount > 0 -> "note"
            chunkCount > 0 && metadataChunkCount == chunkCount -> "metadata_only"
            chunkCount > 0 -> "indexed"
            else -> "unindexed"
        }

    val score: Int
        get() = buildList {
            if (chunkCount > 0) add(1)
            if (metadataChunkCount < chunkCount) add(1)
            if (transcriptChunkCount > 0 || articleChunkCount > 0 || documentChunkCount > 0 || noteChunkCount > 0) add(1)
            if (timestampedChunkCount > 0) add(1)
            if (captionTrackCount > 0) add(1)
            if (visualChunkCount > 0 || visualObservationCount > 0) add(1)
            if ("playback" in assetRoles) add(1)
        }.sum().coerceIn(0, 7)

    val notes: List<String>
        get() = buildList {
            when (contentDepth) {
                "auth_required" -> add("Auth is required before full extraction can run.")
                "metadata_only" -> add("Only metadata chunks are indexed; answers should say transcript/body content is unavailable.")
                "unindexed" -> add("No indexed chunks are available yet.")
                "transcript" -> add("Transcript text is indexed and can support cited answers.")
                "transcript_visual" -> add("Transcript and visual context are both indexed.")
                "visual" -> add("Visual context is indexed; transcript/body text may still be missing.")
            }
            if (captionTrackCount == 0 && transcriptChunkCount == 0 && processingState == "done") {
                add("No caption track is stored for this source.")
            }
            if ("playback" in assetRoles && visualObservationCount == 0) {
                add("Playable media exists but visual observations are not model-analyzed yet.")
            }
        }
}

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
        durationSeconds = durationSeconds,
        processingState = processingState,
        authState = authState,
        contentDepth = contentDepthForChunk(processingState, authState, chunkType),
        snippet = body.take(360),
        chunkType = chunkType,
        language = language,
        matchReason = reason,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        savedAt = savedAt,
        retrievalMode = retrievalMode,
        rankScore = rankScore,
        rankSignals = retrievalMode,
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
        durationSeconds = durationSeconds,
        processingState = processingState,
        authState = authState,
        contentDepth = contentDepthForChunk(processingState, authState, chunkType),
        snippet = body.take(360),
        chunkType = chunkType,
        language = language,
        matchReason = reason,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        savedAt = savedAt,
        retrievalMode = "semantic",
        rankScore = score,
        rankSignals = "semantic:${"%.2f".format(score)}",
    )
}

private fun SearchResultData.toToolCitationJson(): JSONObject {
    return JSONObject()
        .put("sourceId", sourceId)
        .put("chunkId", chunkId)
        .put("title", title)
        .put("sourceType", sourceType)
        .put("originDomain", originDomain)
        .put("author", author)
        .put("processingState", processingState)
        .put("authState", authState)
        .put("contentDepth", contentDepth)
        .put("snippet", snippet)
        .put("chunkType", chunkType)
        .put("language", language)
        .put("matchReason", matchReason)
        .put("startTimeMs", startTimeMs)
        .put("endTimeMs", endTimeMs)
        .put("retrievalMode", retrievalMode)
        .put("rankScore", rankScore.toDouble())
        .put("rankSignals", rankSignals)
}

private fun SourceSnapshot.toToolSourceJson(): JSONObject {
    return JSONObject()
        .put("sourceId", source.id)
        .put("title", source.title)
        .put("sourceType", source.sourceType)
        .put("originDomain", source.originDomain)
        .put("author", source.author)
        .put("summary", source.summary)
        .put("durationSeconds", source.durationSeconds)
        .put("processingState", source.processingState)
        .put("authState", source.authState)
        .put("savedAt", source.savedAt)
        .put("assetRoles", JSONArray(assets.map { it.role }))
        .put("contentProfile", contentProfile.toToolJson())
}

private fun SourceContentProfile.toToolJson(): JSONObject {
    return JSONObject()
        .put("contentDepth", contentDepth)
        .put("score", score)
        .put("chunkCount", chunkCount)
        .put("transcriptChunkCount", transcriptChunkCount)
        .put("articleChunkCount", articleChunkCount)
        .put("documentChunkCount", documentChunkCount)
        .put("noteChunkCount", noteChunkCount)
        .put("metadataChunkCount", metadataChunkCount)
        .put("visualChunkCount", visualChunkCount)
        .put("timestampedChunkCount", timestampedChunkCount)
        .put("captionTrackCount", captionTrackCount)
        .put("visualObservationCount", visualObservationCount)
        .put("assetRoles", JSONArray(assetRoles))
        .put("notes", JSONArray(notes))
}

private fun ContentChunkData.toToolChunkJson(textLimit: Int = 900): JSONObject {
    return JSONObject()
        .put("chunkId", id)
        .put("sourceId", sourceId)
        .put("chunkType", chunkType)
        .put("language", language)
        .put("startTimeMs", startTimeMs)
        .put("endTimeMs", endTimeMs)
        .put("page", page)
        .put("sectionTitle", sectionTitle)
        .put("provider", provider)
        .put("text", text.take(textLimit))
}

private fun CaptionTrackEntity.toToolCaptionTrackJson(): JSONObject {
    return JSONObject()
        .put("captionTrackId", id)
        .put("sourceId", sourceId)
        .put("language", language)
        .put("source", source)
        .put("format", format)
        .put("segmentCount", segmentCount)
        .put("chunkCount", chunkCount)
        .put("createdAt", createdAt)
}

private fun VisualObservationEntity.toToolVisualObservationJson(): JSONObject {
    return JSONObject()
        .put("observationId", id)
        .put("sourceId", sourceId)
        .put("assetId", assetId)
        .put("observationType", observationType)
        .put("text", text.take(1_000))
        .put("confidence", confidence)
        .put("provider", provider)
        .put("model", model)
        .put("startTimeMs", startTimeMs)
        .put("endTimeMs", endTimeMs)
        .put("createdAt", createdAt)
}

private fun DocumentChunkEntity.toContentChunkData(): ContentChunkData {
    return ContentChunkData(
        id = id,
        sourceId = sourceId,
        text = text,
        chunkType = chunkType,
        language = language,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        page = page,
        sectionTitle = sectionTitle,
        provider = provider,
    )
}

private fun explainSearchResult(result: SearchResultData, query: String): String {
    val timestamp = result.startTimeMs?.let { " at ${it.timestampLabel()}" }.orEmpty()
    val sourceLabel = listOfNotNull(result.sourceType, result.originDomain).joinToString(" from ")
    val retrieval = when (result.retrievalMode) {
        "hybrid" -> "both keyword and semantic retrieval"
        "keyword" -> "keyword retrieval"
        "semantic" -> "semantic retrieval"
        "filter" -> "structured filter retrieval"
        else -> result.retrievalMode
    }
    return buildString {
        append("Mem matched ")
        append(result.title)
        if (sourceLabel.isNotBlank()) append(" ($sourceLabel)")
        append(" for \"$query\" using $retrieval.")
        append(" The cited ${result.chunkType} chunk$timestamp was selected because: ${result.matchReason}.")
        append(" Rank signals: ${result.rankSignals}.")
        if (result.snippet.isNotBlank()) {
            append(" Evidence snippet: ")
            append(result.snippet.take(260))
        }
    }
}

private fun deterministicSourceSummary(snapshot: SourceSnapshot, chunks: List<ContentChunkData>): String {
    val source = snapshot.source
    val bestChunks = chunks
        .sortedWith(
            compareBy<ContentChunkData> {
                when (it.chunkType) {
                    "transcript" -> 0
                    "article", "document", "note" -> 1
                    "visual" -> 2
                    else -> 3
                }
            }.thenBy { it.startTimeMs ?: Long.MAX_VALUE },
        )
        .take(3)
    val context = bestChunks.joinToString(" ") { chunk ->
        val prefix = chunk.startTimeMs?.let { "[${it.timestampLabel()}] " }.orEmpty()
        prefix + chunk.text
    }.take(900)
    return buildString {
        append(source.title)
        source.originDomain?.let { append(" from $it") }
        source.author?.let { append(" by $it") }
        append(". ")
        source.summary?.takeIf { it.isNotBlank() }?.let {
            append(it.take(260))
            append(" ")
        }
        if (context.isNotBlank()) {
            append("Indexed context: ")
            append(context)
        } else {
            append("No indexed transcript, article, document, note, or visual chunks are available yet.")
        }
    }
}

private class RoomExactScanVectorIndex(
    private val dao: ChunkEmbeddingDao,
    private val scanLimit: Int,
) : VectorIndex {
    override suspend fun upsert(vectors: List<EmbeddingVector>) {
        vectors.forEach { vector ->
            val sourceId = vector.metadata["sourceId"] ?: return@forEach
            val modelId = vector.metadata["modelId"] ?: "${vector.provider}_${vector.model}"
            val embeddingType = vector.metadata["embeddingType"] ?: "text"
            val contentHash = vector.metadata["contentHash"] ?: return@forEach
            val createdAt = vector.metadata["createdAt"]?.toLongOrNull() ?: System.currentTimeMillis()
            dao.upsert(
                ChunkEmbeddingEntity(
                    id = stableId("embedding:${vector.inputId}:$modelId"),
                    chunkId = vector.inputId,
                    sourceId = sourceId,
                    modelId = modelId,
                    embeddingType = embeddingType,
                    dimensions = vector.dimensions,
                    vector = vector.values.toByteArrayVector(),
                    contentHash = contentHash,
                    createdAt = createdAt,
                ),
            )
        }
    }

    override suspend fun search(vector: EmbeddingVector, limit: Int, filter: Map<String, String>): List<VectorHit> {
        val embeddingType = filter["embeddingType"] ?: "text"
        val candidates = dao.candidates(embeddingType, scanLimit)
        return candidates
            .mapNotNull { candidate ->
                val score = cosineSimilarity(vector.values, candidate.vector.toFloatVector(candidate.dimensions))
                if (score < 0.08f) return@mapNotNull null
                VectorHit(
                    id = candidate.chunkId,
                    score = score,
                    provider = vector.provider,
                    model = vector.model,
                    diagnostics = mapOf(
                        "index" to "room_exact_scan",
                        "window" to scanLimit.toString(),
                        "scanned" to candidates.size.toString(),
                    ),
                )
            }
            .sortedByDescending { it.score }
            .take(limit)
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

private fun visualSampleTimes(durationMs: Long?): List<Long> {
    val duration = durationMs?.coerceAtLeast(0L) ?: 0L
    if (duration <= 0L) return listOf(1_000L)
    val safeEnd = (duration - 1_000L).coerceAtLeast(1_000L)
    return listOf(
        1_000L,
        duration / 3L,
        (duration * 2L) / 3L,
        safeEnd,
    )
        .map { it.coerceIn(0L, safeEnd) }
        .distinct()
        .take(4)
}

private fun visualObservationText(
    source: SourceEntity,
    timeMs: Long,
    durationMs: Long?,
    thumbnailPath: String?,
    index: Int,
): String {
    val timestamp = timeMs.timestampLabel()
    val duration = durationMs?.timestampLabel()
    val thumbnailState = if (thumbnailPath.isNullOrBlank()) "No local thumbnail file is available." else "A local thumbnail/frame asset is available."
    return buildString {
        append("Visual frame sample ${index + 1} for ${source.title} at $timestamp.")
        duration?.let { append(" Video duration is $it.") }
        append(" $thumbnailState")
        append(" This is a local visual observation placeholder from playable media; it supports has:visual filtering and future model-backed action, object, OCR, and scene analysis without claiming detected objects or events yet.")
    }
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
    return (parsed.phrases + parsed.freeTerms + parsed.softTerms)
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

private data class ParsedSearchQuery(
    val phrases: List<String>,
    val freeTerms: List<String>,
    val softTerms: List<String>,
    val negativeTerms: List<String>,
    val types: Set<String>,
    val domains: Set<String>,
    val statuses: Set<String>,
    val tagFilters: Set<String>,
    val collectionFilters: Set<String>,
    val authorFilters: Set<String>,
    val languageFilters: Set<String>,
    val requiredAssetRoles: Set<String>,
    val requiredChunkTypes: Set<String>,
    val requiredCapabilities: Set<String>,
    val durationRanges: List<LongRange>,
    val savedRanges: List<LongRange>,
    val dateRanges: List<LongRange>,
) {
    fun matches(result: SearchResultData, filterContext: SearchFilterContext): Boolean {
        val haystack = result.searchHaystack()
        if (negativeTerms.any { haystack.contains(it.lowercase(Locale.US)) }) return false
        if (types.isNotEmpty() && result.sourceType.lowercase(Locale.US) !in types) return false
        if (domains.isNotEmpty()) {
            val domain = result.originDomain?.lowercase(Locale.US)?.removePrefix("www.")
            if (domain == null || domains.none { domain == it || domain.endsWith(".$it") }) return false
        }
        if (statuses.isNotEmpty() && statuses.none { haystack.contains(it) }) return false
        filterContext.tagSourceIds?.let { if (result.sourceId !in it) return false }
        filterContext.collectionSourceIds?.let { if (result.sourceId !in it) return false }
        filterContext.assetSourceIds?.let { if (result.sourceId !in it) return false }
        if (authorFilters.isNotEmpty()) {
            val author = result.author?.lowercase(Locale.US) ?: return false
            if (authorFilters.none { author.contains(it) }) return false
        }
        if (languageFilters.isNotEmpty()) {
            val language = result.language?.lowercase(Locale.US) ?: return false
            if (language !in languageFilters) return false
        }
        if (requiredChunkTypes.isNotEmpty() && result.chunkType.lowercase(Locale.US) !in requiredChunkTypes) return false
        if ("timestamp" in requiredCapabilities && result.startTimeMs == null) return false
        if (durationRanges.isNotEmpty()) {
            val duration = result.durationSeconds ?: return false
            if (durationRanges.none { duration in it }) return false
        }
        if (savedRanges.isNotEmpty() && savedRanges.none { result.savedAt in it }) return false
        if (dateRanges.isNotEmpty() && dateRanges.none { result.savedAt in it }) return false
        return true
    }

    fun hasStructuredFilters(): Boolean {
        return types.isNotEmpty() ||
            domains.isNotEmpty() ||
            statuses.isNotEmpty() ||
            tagFilters.isNotEmpty() ||
            collectionFilters.isNotEmpty() ||
            authorFilters.isNotEmpty() ||
            languageFilters.isNotEmpty() ||
            requiredAssetRoles.isNotEmpty() ||
            requiredChunkTypes.isNotEmpty() ||
            requiredCapabilities.isNotEmpty() ||
            durationRanges.isNotEmpty() ||
            savedRanges.isNotEmpty() ||
            dateRanges.isNotEmpty() ||
            negativeTerms.isNotEmpty()
    }

    fun rankBoost(result: SearchResultData, filterContext: SearchFilterContext): Float {
        val haystack = result.searchHaystack()
        var boost = 0f
        phrases.forEach { phrase -> if (haystack.contains(phrase.lowercase(Locale.US))) boost += 0.45f }
        freeTerms.forEach { term -> if (haystack.contains(term.lowercase(Locale.US))) boost += 0.12f }
        if (requiredChunkTypes.contains(result.chunkType.lowercase(Locale.US))) boost += 0.35f
        if (domains.any { result.originDomain?.lowercase(Locale.US)?.contains(it) == true }) boost += 0.25f
        if (filterContext.tagSourceIds?.contains(result.sourceId) == true) boost += 0.22f
        if (filterContext.collectionSourceIds?.contains(result.sourceId) == true) boost += 0.22f
        if (filterContext.assetSourceIds?.contains(result.sourceId) == true) boost += 0.2f
        if (authorFilters.any { result.author?.lowercase(Locale.US)?.contains(it) == true }) boost += 0.18f
        if (languageFilters.contains(result.language?.lowercase(Locale.US))) boost += 0.18f
        if (durationRanges.isNotEmpty() && result.durationSeconds != null) boost += 0.18f
        if (savedRanges.isNotEmpty() || dateRanges.isNotEmpty()) boost += 0.12f
        if (result.startTimeMs != null) boost += 0.08f
        if (result.retrievalMode == "hybrid") boost += 0.25f
        return boost
    }

    fun toJson(filterContext: SearchFilterContext): String {
        return JSONObject()
            .put("phrases", JSONArray(phrases))
            .put("freeTerms", JSONArray(freeTerms))
            .put("softTerms", JSONArray(softTerms))
            .put("negativeTerms", JSONArray(negativeTerms))
            .put("types", JSONArray(types.toList()))
            .put("domains", JSONArray(domains.toList()))
            .put("statuses", JSONArray(statuses.toList()))
            .put("tagFilters", JSONArray(tagFilters.toList()))
            .put("collectionFilters", JSONArray(collectionFilters.toList()))
            .put("authorFilters", JSONArray(authorFilters.toList()))
            .put("languageFilters", JSONArray(languageFilters.toList()))
            .put("requiredAssetRoles", JSONArray(requiredAssetRoles.toList()))
            .put("tagFilterMatches", filterContext.tagSourceIds?.size ?: 0)
            .put("collectionFilterMatches", filterContext.collectionSourceIds?.size ?: 0)
            .put("assetFilterMatches", filterContext.assetSourceIds?.size ?: 0)
            .put("requiredChunkTypes", JSONArray(requiredChunkTypes.toList()))
            .put("requiredCapabilities", JSONArray(requiredCapabilities.toList()))
            .put("durationRanges", JSONArray(durationRanges.map { "${it.first}..${it.last}" }))
            .put("savedRanges", JSONArray(savedRanges.map { "${it.first}..${it.last}" }))
            .put("dateRanges", JSONArray(dateRanges.map { "${it.first}..${it.last}" }))
            .toString()
    }
}

private data class SearchFilterContext(
    val tagSourceIds: Set<String>?,
    val collectionSourceIds: Set<String>?,
    val assetSourceIds: Set<String>?,
)

private fun parseSearchQuery(query: String): ParsedSearchQuery {
    val freeTerms = mutableListOf<String>()
    val softTerms = mutableListOf<String>()
    val negativeTerms = mutableListOf<String>()
    val types = mutableSetOf<String>()
    val domains = mutableSetOf<String>()
    val statuses = mutableSetOf<String>()
    val tagFilters = mutableSetOf<String>()
    val collectionFilters = mutableSetOf<String>()
    val authorFilters = mutableSetOf<String>()
    val languageFilters = mutableSetOf<String>()
    val requiredAssetRoles = mutableSetOf<String>()
    val requiredChunkTypes = mutableSetOf<String>()
    val requiredCapabilities = mutableSetOf<String>()
    val durationRanges = mutableListOf<LongRange>()
    val savedRanges = mutableListOf<LongRange>()
    val dateRanges = mutableListOf<LongRange>()
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
            if (token.startsWith("-")) {
                token.drop(1).takeIf { it.length >= 2 }?.let { negativeTerms.add(it.lowercase(Locale.US)) }
                return@forEach
            }
            val parts = token.split(":", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].lowercase(Locale.US)
                val value = parts[1].lowercase(Locale.US).trim()
                when (key) {
                    "type" -> types.addAll(value.split("/", ",").map { normalizeTypeFilter(it) })
                    "site", "domain" -> domains.add(value.removePrefix("www."))
                    "status" -> statuses.add(value.replace("-", "_"))
                    "has" -> when (value) {
                        "transcript" -> requiredChunkTypes.add("transcript")
                        "visual" -> requiredChunkTypes.add("visual")
                        "local_video" -> requiredAssetRoles.add("playback")
                        "thumbnail" -> requiredAssetRoles.add("thumbnail")
                        "timestamp" -> requiredCapabilities.add("timestamp")
                        "auth" -> statuses.add("needs_auth")
                        else -> softTerms.add(value)
                    }
                    "tag" -> tagFilters.add(value)
                    "collection" -> collectionFilters.add(value)
                    "author", "channel" -> authorFilters.add(value)
                    "language" -> languageFilters.add(value)
                    "action" -> softTerms.add(value)
                    "duration" -> parseDurationRange(value)?.let(durationRanges::add)
                    "saved" -> parseDateRange(value)?.let(savedRanges::add)
                    "date" -> parseDateRange(value)?.let(dateRanges::add)
                    else -> freeTerms.add(value)
                }
            } else {
                freeTerms.add(token)
            }
        }
    return ParsedSearchQuery(
        phrases = phrases,
        freeTerms = freeTerms.map { it.lowercase(Locale.US) },
        softTerms = softTerms.map { it.lowercase(Locale.US) },
        negativeTerms = negativeTerms,
        types = types.filter { it.isNotBlank() }.toSet(),
        domains = domains.filter { it.isNotBlank() }.toSet(),
        statuses = statuses.filter { it.isNotBlank() }.toSet(),
        tagFilters = tagFilters.filter { it.isNotBlank() }.toSet(),
        collectionFilters = collectionFilters.filter { it.isNotBlank() }.toSet(),
        authorFilters = authorFilters.filter { it.isNotBlank() }.toSet(),
        languageFilters = languageFilters.filter { it.isNotBlank() }.toSet(),
        requiredAssetRoles = requiredAssetRoles.filter { it.isNotBlank() }.toSet(),
        requiredChunkTypes = requiredChunkTypes,
        requiredCapabilities = requiredCapabilities,
        durationRanges = durationRanges,
        savedRanges = savedRanges,
        dateRanges = dateRanges,
    )
}

private fun parseDurationRange(value: String): LongRange? {
    val normalized = value.trim().lowercase(Locale.US)
    if (normalized.isBlank()) return null
    return when {
        normalized.startsWith("<=") -> 0L..(parseDurationSeconds(normalized.drop(2)) ?: return null)
        normalized.startsWith("<") -> 0L..((parseDurationSeconds(normalized.drop(1)) ?: return null) - 1L).coerceAtLeast(0L)
        normalized.startsWith(">=") -> (parseDurationSeconds(normalized.drop(2)) ?: return null)..Long.MAX_VALUE
        normalized.startsWith(">") -> ((parseDurationSeconds(normalized.drop(1)) ?: return null) + 1L)..Long.MAX_VALUE
        ".." in normalized -> {
            val parts = normalized.split("..", limit = 2)
            val start = parts.getOrNull(0)?.takeIf { it.isNotBlank() }?.let(::parseDurationSeconds) ?: 0L
            val end = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::parseDurationSeconds) ?: Long.MAX_VALUE
            if (start > end) end..start else start..end
        }
        else -> {
            val seconds = parseDurationSeconds(normalized) ?: return null
            seconds..seconds
        }
    }
}

private fun parseDurationSeconds(value: String): Long? {
    val match = Regex("""^(\d+)(ms|s|m|h)?$""").matchEntire(value.trim().lowercase(Locale.US)) ?: return null
    val amount = match.groupValues[1].toLongOrNull() ?: return null
    return when (match.groupValues[2]) {
        "ms" -> amount / 1000L
        "s", "" -> amount
        "m" -> amount * 60L
        "h" -> amount * 3600L
        else -> null
    }
}

private fun parseDateRange(value: String): LongRange? {
    val normalized = value.trim().lowercase(Locale.US)
    if (normalized.isBlank()) return null
    val now = System.currentTimeMillis()
    return when {
        normalized == "today" -> calendarRange(now, Calendar.DAY_OF_MONTH)
        normalized == "yesterday" -> {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, -1)
            }
            dayRange(calendar)
        }
        normalized.startsWith("last") && normalized.endsWith("d") -> {
            val days = normalized.removePrefix("last").removeSuffix("d").toIntOrNull() ?: return null
            (now - days.coerceAtLeast(0) * 24L * 60L * 60L * 1000L)..now
        }
        ".." in normalized -> {
            val parts = normalized.split("..", limit = 2)
            val start = parts.getOrNull(0)?.takeIf { it.isNotBlank() }?.let(::parseDateBoundaryStart) ?: 0L
            val end = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::parseDateBoundaryEnd) ?: Long.MAX_VALUE
            if (start > end) end..start else start..end
        }
        else -> {
            val start = parseDateBoundaryStart(normalized) ?: return null
            val end = parseDateBoundaryEnd(normalized) ?: return null
            start..end
        }
    }
}

private fun parseDateBoundaryStart(value: String): Long? {
    val parts = value.split("-")
    val year = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val month = parts.getOrNull(1)?.toIntOrNull()
    val day = parts.getOrNull(2)?.toIntOrNull()
    return Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, (month ?: 1) - 1)
        set(Calendar.DAY_OF_MONTH, day ?: 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun parseDateBoundaryEnd(value: String): Long? {
    val parts = value.split("-")
    val year = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val month = parts.getOrNull(1)?.toIntOrNull()
    val day = parts.getOrNull(2)?.toIntOrNull()
    val calendar = Calendar.getInstance().apply {
        clear()
        set(Calendar.YEAR, year)
        set(Calendar.MONTH, (month ?: 12) - 1)
        set(Calendar.DAY_OF_MONTH, day ?: getActualMaximum(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }
    if (month != null && day == null) {
        calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
    }
    return calendar.timeInMillis
}

private fun calendarRange(timeMs: Long, field: Int): LongRange {
    val calendar = Calendar.getInstance().apply { timeInMillis = timeMs }
    return when (field) {
        Calendar.DAY_OF_MONTH -> dayRange(calendar)
        else -> dayRange(calendar)
    }
}

private fun dayRange(calendar: Calendar): LongRange {
    val start = calendar.clone() as Calendar
    start.set(Calendar.HOUR_OF_DAY, 0)
    start.set(Calendar.MINUTE, 0)
    start.set(Calendar.SECOND, 0)
    start.set(Calendar.MILLISECOND, 0)
    val end = start.clone() as Calendar
    end.set(Calendar.HOUR_OF_DAY, 23)
    end.set(Calendar.MINUTE, 59)
    end.set(Calendar.SECOND, 59)
    end.set(Calendar.MILLISECOND, 999)
    return start.timeInMillis..end.timeInMillis
}

private fun normalizeTypeFilter(value: String): String {
    return when (value.trim().lowercase(Locale.US)) {
        "pdf" -> "pdf"
        "doc", "docs", "document" -> "document"
        "videos" -> "video"
        "articles" -> "article"
        "notes" -> "note"
        "images" -> "image"
        "audio" -> "audio"
        else -> value.trim().lowercase(Locale.US)
    }
}

private fun SearchResultData.searchHaystack(): String {
    return listOfNotNull(
        title,
        sourceType,
        originDomain,
        author,
        processingState,
        authState,
        contentDepth,
        snippet,
        chunkType,
        matchReason,
        retrievalMode,
    ).joinToString(" ").lowercase(Locale.US)
}

private fun contentDepthForChunk(processingState: String, authState: String, chunkType: String): String {
    return when {
        authState == "needs_auth" || processingState == "needs_auth" -> "auth_required"
        chunkType == "metadata" -> "metadata_only"
        chunkType == "transcript" || chunkType == "transcript_segment" -> "transcript"
        chunkType == "visual" -> "visual"
        chunkType == "article" -> "article"
        chunkType == "document" || chunkType == "rag_text" -> "document"
        chunkType == "note" -> "note"
        else -> "indexed"
    }
}
