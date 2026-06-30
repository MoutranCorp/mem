package com.moutrancorp.memspike.ai

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sqrt

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
                activeModel = "tool-preview",
                status = AiCapabilityStatus.Fallback,
                privacy = "On device",
                notes = "Current actions are deterministic previews; final Q&A and planning need a model-backed runtime.",
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
