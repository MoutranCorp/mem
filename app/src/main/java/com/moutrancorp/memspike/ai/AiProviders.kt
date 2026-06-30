package com.moutrancorp.memspike.ai

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
                notes = "Deterministic fallback for offline hybrid search; production semantic quality needs a real embedding model.",
            ),
            AiPipelineStatus(
                capability = "Vector retrieval",
                activeProvider = "room",
                activeModel = "exact-scan",
                status = AiCapabilityStatus.Fallback,
                privacy = "On device",
                notes = "Exact scan keeps the data model honest now; large libraries need an approximate vector index.",
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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EmbeddingVector
        return inputId == other.inputId &&
            provider == other.provider &&
            model == other.model &&
            dimensions == other.dimensions &&
            values.contentEquals(other.values)
    }

    override fun hashCode(): Int {
        var result = inputId.hashCode()
        result = 31 * result + provider.hashCode()
        result = 31 * result + model.hashCode()
        result = 31 * result + dimensions
        result = 31 * result + values.contentHashCode()
        return result
    }
}

interface EmbeddingProvider {
    val providerId: String
    val model: String
    val dimensions: Int?

    suspend fun embed(inputs: List<EmbeddingInput>): List<EmbeddingVector>
}

data class VectorHit(
    val id: String,
    val score: Float,
    val provider: String,
    val model: String,
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
