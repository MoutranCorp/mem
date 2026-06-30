package com.moutrancorp.memspike.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        indexSource(source, result.summary)
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
        val text = result.ragText?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(result.title, result.author, result.summary).joinToString("\n\n")
        if (text.isNotBlank()) {
            database.documentChunkDao().upsert(
                DocumentChunkEntity(
                    id = UUID.randomUUID().toString(),
                    sourceId = sourceId,
                    text = text,
                    chunkType = if (result.ragText.isNullOrBlank()) "metadata" else "rag_text",
                    startOffset = 0,
                    endOffset = text.length,
                    startTimeMs = null,
                    endTimeMs = null,
                    createdAt = now,
                ),
            )
        }
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

private fun toFtsQuery(query: String): String {
    return query
        .trim()
        .split(Regex("\\s+"))
        .map { token -> token.filter { it.isLetterOrDigit() || it == '_' || it == '-' } }
        .filter { it.length >= 2 }
        .joinToString(" ") { "$it*" }
}
