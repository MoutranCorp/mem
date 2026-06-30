package com.moutrancorp.memspike.ai

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

