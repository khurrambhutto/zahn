package com.chotaai.llm

import kotlinx.coroutines.flow.Flow

/** Which runtime executes a model. Each one reads a different file format. */
enum class EngineType(val fileExtension: String, val displayName: String) {
    LITERT("litertlm", "LiteRT-LM"),
    LLAMA_CPP("gguf", "llama.cpp");

    companion object {
        fun forFileName(name: String): EngineType? =
            entries.firstOrNull { name.endsWith(".${it.fileExtension}", ignoreCase = true) }
    }
}

enum class Accelerator { CPU, GPU, NPU }

data class ModelConfig(
    val path: String,
    val contextSize: Int = 4096,
    /** 0 picks a sensible default for the device. */
    val threads: Int = 0,
    val accelerator: Accelerator = Accelerator.CPU,
)

data class SamplingParams(
    val temperature: Float = 0.2f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val seed: Int = 0,
    val maxTokens: Int = 512,
)

data class SessionConfig(
    val systemPrompt: String,
    val tools: List<ToolSpec> = emptyList(),
    val sampling: SamplingParams = SamplingParams(),
    /** Reasoning models (e.g. Qwen3) think before answering. Off by default: it is slow on phones. */
    val enableThinking: Boolean = false,
)

/**
 * A loaded model. One engine holds one model in memory; sessions share it.
 *
 * Implementations: `LiteRtEngine` (:llm:litert) and `LlamaCppEngine` (:llm:llamacpp).
 */
interface LlmEngine : AutoCloseable {
    val type: EngineType
    val isLoaded: Boolean

    /** Loads the model. Slow (seconds), so it is a suspend function that must run off the main thread. */
    suspend fun load(config: ModelConfig)

    fun createSession(config: SessionConfig): ChatSession

    /** Frees the model. The engine can be loaded again afterwards. */
    override fun close()
}

/**
 * A multi-turn conversation. The session owns the history; callers only send the new turn.
 *
 * A turn that ends with [GenerationEvent.ToolCalls] should be answered with [TurnInput.ToolResults].
 * If a [TurnInput.User] arrives instead, implementations answer the pending calls with a
 * "skipped" result so the chat template stays well-formed.
 */
interface ChatSession : AutoCloseable {
    fun send(input: TurnInput): Flow<GenerationEvent>

    /** Stops the generation in progress, if any. Safe to call from any thread. */
    fun cancel()
}

sealed interface TurnInput {
    data class User(val text: String) : TurnInput
    data class ToolResults(val results: List<ToolResult>) : TurnInput
}

sealed interface GenerationEvent {
    data class TextDelta(val text: String) : GenerationEvent
    data class ReasoningDelta(val text: String) : GenerationEvent
    data class ToolCalls(val calls: List<ToolCall>) : GenerationEvent
    data class Finished(val stats: GenerationStats? = null) : GenerationEvent
}

data class GenerationStats(
    val promptTokens: Int,
    val generatedTokens: Int,
    val prefillTokensPerSecond: Double,
    val decodeTokensPerSecond: Double,
)

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)
