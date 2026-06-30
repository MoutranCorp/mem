package com.moutrancorp.memspike.ai

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

enum class AiCapabilityStatus {
    Active,
    Fallback,
    Planned,
}

data class AiPipelineStatus(
    val capability: String,
    val activeProvider: String,
    val activeModel: String,
    val status: AiCapabilityStatus,
    val privacy: String,
    val notes: String,
)

object DefaultAiProviderRegistry {
    fun statuses(): List<AiPipelineStatus> {
        return listOf(
            AiPipelineStatus(
                capability = "Text embeddings",
                activeProvider = "local",
                activeModel = "hash-v1-text-128",
                status = AiCapabilityStatus.Fallback,
                privacy = "On device",
                notes = "LocalHashEmbeddingProvider keeps the embedding pipeline swappable; production semantic quality needs a real embedding model.",
            ),
            AiPipelineStatus(
                capability = "Vector retrieval",
                activeProvider = "room",
                activeModel = "room-exact-scan",
                status = AiCapabilityStatus.Fallback,
                privacy = "On device",
                notes = "RoomExactScanVectorIndex implements the VectorIndex boundary now; large libraries need an approximate packaged index.",
            ),
            AiPipelineStatus(
                capability = "Agent runtime",
                activeProvider = "local",
                activeModel = "deterministic-tool-runtime",
                status = AiCapabilityStatus.Fallback,
                privacy = "On device",
                notes = "DeterministicToolAgentRuntime executes the same memory tools a model-backed runtime will use, returning cited local answer payloads.",
            ),
            AiPipelineStatus(
                capability = "Visual understanding",
                activeProvider = "local",
                activeModel = "frame-sample-placeholders",
                status = AiCapabilityStatus.Planned,
                privacy = "On device",
                notes = "Playable videos are indexed with timestamped frame samples; object/action/OCR labels need a vision model provider.",
            ),
        )
    }
}

data class EmbeddingInput(
    val id: String,
    val text: String,
    val type: String,
    val metadata: Map<String, String> = emptyMap(),
)

data class EmbeddingVector(
    val inputId: String,
    val provider: String,
    val model: String,
    val dimensions: Int,
    val values: FloatArray,
    val metadata: Map<String, String> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmbeddingVector
        return inputId == other.inputId &&
            provider == other.provider &&
            model == other.model &&
            dimensions == other.dimensions &&
            values.contentEquals(other.values) &&
            metadata == other.metadata
    }

    override fun hashCode(): Int {
        var result = inputId.hashCode()
        result = 31 * result + provider.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + dimensions
        result = 31 * result + values.contentHashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }
}

interface EmbeddingProvider {
    val providerId: String
    val model: String
    val dimensions: Int?

    suspend fun embed(inputs: List<EmbeddingInput>): List<EmbeddingVector>
}

class LocalHashEmbeddingProvider : EmbeddingProvider {
    override val providerId: String = "local"
    override val model: String = "hash-v1-text-128"
    override val dimensions: Int = 128

    override suspend fun embed(inputs: List<EmbeddingInput>): List<EmbeddingVector> {
        return inputs.map { input ->
            EmbeddingVector(
                inputId = input.id,
                provider = providerId,
                model = model,
                dimensions = dimensions,
                values = embedText(input.text, dimensions),
                metadata = input.metadata,
            )
        }
    }
}

data class VectorHit(
    val id: String,
    val score: Float,
    val provider: String,
    val model: String,
    val diagnostics: Map<String, String> = emptyMap(),
)

interface VectorIndex {
    suspend fun upsert(vectors: List<EmbeddingVector>)
    suspend fun search(vector: EmbeddingVector, limit: Int, filter: Map<String, String> = emptyMap()): List<VectorHit>
}

data class AgentToolCall(
    val name: String,
    val argumentsJson: String,
)

data class AgentToolResult(
    val call: AgentToolCall,
    val resultJson: String,
)

interface AgentRuntime {
    suspend fun answerWithTools(
        userRequest: String,
        availableTools: List<String>,
        toolRunner: suspend (AgentToolCall) -> AgentToolResult,
    ): String
}

class DeterministicToolAgentRuntime : AgentRuntime {
    override suspend fun answerWithTools(
        userRequest: String,
        availableTools: List<String>,
        toolRunner: suspend (AgentToolCall) -> AgentToolResult,
    ): String {
        val query = userRequest.trim()
        if (query.isBlank()) {
            return JSONObject()
                .put("ok", false)
                .put("runtime", "deterministic-tool-runtime")
                .put("error", "userRequest is required")
                .toString()
        }
        if ("search_memory" !in availableTools) {
            return JSONObject()
                .put("ok", false)
                .put("runtime", "deterministic-tool-runtime")
                .put("error", "search_memory tool is unavailable")
                .toString()
        }

        val search = toolRunner(
            AgentToolCall(
                name = "search_memory",
                argumentsJson = JSONObject()
                    .put("query", query)
                    .put("limit", 6)
                    .toString(),
            ),
        ).json()
        val citations = search.optJSONArray("citations") ?: JSONArray()
        val topCitations = citations.takeObjects(4)
        val enrichedSources = JSONArray()
        val usedSourceIds = mutableSetOf<String>()
        topCitations.forEach { citation ->
            val sourceId = citation.optString("sourceId")
            if (sourceId.isBlank() || !usedSourceIds.add(sourceId)) return@forEach
            val sourceContext = runToolIfAvailable(
                availableTools = availableTools,
                toolName = "get_source_context",
                args = JSONObject().put("sourceId", sourceId).put("limit", 4),
                toolRunner = toolRunner,
            )
            val transcript = if (citation.optString("chunkType") in setOf("transcript", "transcript_segment")) {
                runToolIfAvailable(
                    availableTools = availableTools,
                    toolName = "get_transcript",
                    args = JSONObject().put("sourceId", sourceId).put("limit", 4),
                    toolRunner = toolRunner,
                )
            } else {
                null
            }
            val visual = if (citation.optString("chunkType") == "visual") {
                runToolIfAvailable(
                    availableTools = availableTools,
                    toolName = "get_visual_observations",
                    args = JSONObject().put("sourceId", sourceId).put("limit", 4),
                    toolRunner = toolRunner,
                )
            } else {
                null
            }
            enrichedSources.put(
                JSONObject()
                    .put("citation", citation)
                    .put("sourceContext", sourceContext)
                    .put("transcript", transcript)
                    .put("visual", visual),
            )
        }

        val answer = buildDeterministicAnswer(query, topCitations, enrichedSources)
        return JSONObject()
            .put("ok", true)
            .put("runtime", "deterministic-tool-runtime")
            .put("query", query)
            .put("answer", answer)
            .put("citationCount", citations.length())
            .put("sourceCount", usedSourceIds.size)
            .put("citations", JSONArray(topCitations))
            .put("enrichedSources", enrichedSources)
            .put("usedTools", JSONArray(listOf("search_memory") + listOf("get_source_context", "get_transcript", "get_visual_observations").filter { it in availableTools }))
            .put("requiresModelUpgrade", true)
            .put("upgradeNote", "This runtime is deterministic and grounded. Planning, nuanced synthesis, and multi-step reasoning should use a model-backed AgentRuntime over the same tools.")
            .toString()
    }

    private suspend fun runToolIfAvailable(
        availableTools: List<String>,
        toolName: String,
        args: JSONObject,
        toolRunner: suspend (AgentToolCall) -> AgentToolResult,
    ): JSONObject? {
        if (toolName !in availableTools) return null
        return toolRunner(AgentToolCall(name = toolName, argumentsJson = args.toString())).json()
    }

    private fun buildDeterministicAnswer(query: String, citations: List<JSONObject>, enrichedSources: JSONArray): String {
        if (citations.isEmpty()) {
            return "I could not find indexed memory chunks for \"$query\". Try capturing sources with transcripts, article text, notes, PDFs, or use filters like type:video, has:transcript, site:youtube.com, status:needs_auth."
        }
        val depthCounts = citations
            .map { it.optString("contentDepth", "indexed") }
            .groupingBy { it }
            .eachCount()
        val sourceCount = citations.map { it.optString("sourceId") }.filter { it.isNotBlank() }.distinct().size
        val strongest = citations.first()
        val evidence = citations.take(3).joinToString(" ") { citation ->
            val title = citation.optString("title")
            val reason = citation.optString("matchReason")
            val snippet = citation.optString("snippet").take(180)
            "[$title] $reason: $snippet"
        }
        val caveat = contentCaveat(depthCounts)
        return buildString {
            append("I found $sourceCount grounded source${if (sourceCount == 1) "" else "s"} for \"$query\". ")
            append("The strongest citation is ${strongest.optString("title")} (${strongest.optString("contentDepth", "indexed")}). ")
            append(caveat)
            append(" Evidence: ")
            append(evidence)
            if (enrichedSources.length() > 0) {
                append(" I used memory tools to enrich the top citations before answering.")
            }
        }
    }

    private fun contentCaveat(depthCounts: Map<String, Int>): String {
        return when {
            depthCounts.keys.any { it == "transcript" || it == "transcript_visual" } -> "At least one result is transcript-backed, so spoken content can be cited. "
            depthCounts.keys.any { it == "visual" } -> "At least one result is visual-context-backed, but current visual observations may still be placeholders. "
            depthCounts.keys.any { it == "metadata_only" || it == "auth_required" || it == "unindexed" } -> "Some results are shallow or auth-gated, so I should not imply transcript/body evidence where it is missing. "
            else -> ""
        }
    }
}

private fun AgentToolResult.json(): JSONObject {
    return runCatching { JSONObject(resultJson) }.getOrElse {
        JSONObject()
            .put("ok", false)
            .put("tool", call.name)
            .put("error", "Tool returned invalid JSON")
    }
}

private fun JSONArray.takeObjects(limit: Int): List<JSONObject> {
    return buildList {
        for (index in 0 until minOf(length(), limit)) {
            optJSONObject(index)?.let(::add)
        }
    }
}

fun localHashEmbedding(text: String, dimensions: Int = 128): FloatArray {
    return embedText(text, dimensions)
}

private fun embedText(text: String, dimensions: Int): FloatArray {
    val vector = FloatArray(dimensions)
    tokenizeForEmbedding(text).forEach { token ->
        val hash = stableId("embed:$token")
        val bucket = hash.take(8).toLong(16).mod(dimensions)
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

private fun stableId(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(32)
}
