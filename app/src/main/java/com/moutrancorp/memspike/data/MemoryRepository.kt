package com.moutrancorp.memspike.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

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
            .map(::toFtsQuery)
            .distinctUntilChanged()
            .flatMapLatest { ftsQuery ->
                if (ftsQuery.isBlank()) {
                    flowOf(emptyList())
                } else {
                    database.chunkSearchDao().observeSearchResults(ftsQuery, 40)
                }
            }
            .map { results -> results.map { it.toSearchResultData() } }
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
                if (indexedText.length < 12_000) {
                    indexedText.append(chunk.text.take(1_200)).append('\n')
                }
            }
        return indexedText.toString()
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

private fun ChunkSearchResult.toSearchResultData(): SearchResultData {
    val timeLabel = startTimeMs?.let { " at ${it.timestampLabel()}" }
    val reason = when (chunkType) {
        "transcript" -> "Transcript match${timeLabel.orEmpty()}"
        "transcript_segment" -> "Transcript segment${timeLabel.orEmpty()}"
        "article", "document", "note" -> "${chunkType.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }} text match"
        "visual" -> "Visual observation${timeLabel.orEmpty()}"
        else -> "Indexed memory match"
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
    )
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
    return (parsed.freeTerms + parsed.filterTerms)
        .map { token -> token.filter { it.isLetterOrDigit() || it == '_' || it == '-' } }
        .filter { it.length >= 2 }
        .distinct()
        .joinToString(" ") { "$it*" }
}

private data class ParsedSearchQuery(val freeTerms: List<String>, val filterTerms: List<String>)

private fun parseSearchQuery(query: String): ParsedSearchQuery {
    val freeTerms = mutableListOf<String>()
    val filterTerms = mutableListOf<String>()
    query
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
                    else -> freeTerms.add(value)
                }
            } else {
                freeTerms.add(token)
            }
        }
    return ParsedSearchQuery(freeTerms, filterTerms)
}
