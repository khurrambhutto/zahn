package com.chotaai.llm.llamacpp

import com.chotaai.llm.ChatSession
import com.chotaai.llm.EngineType
import com.chotaai.llm.GenerationEvent
import com.chotaai.llm.GenerationStats
import com.chotaai.llm.LlmEngine
import com.chotaai.llm.LlmException
import com.chotaai.llm.ModelConfig
import com.chotaai.llm.SessionConfig
import com.chotaai.llm.ToolCall
import com.chotaai.llm.ToolResult
import com.chotaai.llm.TurnInput
import com.chotaai.llm.toOpenAiJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Runs `.gguf` models (anything from Hugging Face that llama.cpp supports) on the CPU.
 *
 * The model's own Jinja chat template formats the conversation and tools, and llama.cpp parses
 * the tool calls back out, so models with different tool-call formats (Qwen, Llama, Gemma,
 * LFM2, ...) all work without per-model code here.
 */
class LlamaCppEngine(private val nativeLibraryDir: String) : LlmEngine {
    override val type = EngineType.LLAMA_CPP

    @Volatile private var handle = 0L

    override val isLoaded: Boolean get() = handle != 0L

    override suspend fun load(config: ModelConfig) = withContext(Dispatchers.IO) {
        close()
        LlamaNative.backendInit(nativeLibraryDir)
        val threads = config.threads.takeIf { it > 0 } ?: defaultThreads()
        handle = try {
            LlamaNative.loadModel(config.path, config.contextSize, threads)
        } catch (e: IllegalStateException) {
            throw LlmException(e.message ?: "llama.cpp could not load ${config.path}", e)
        }
    }

    /** Model description and chat-template capabilities, as JSON. */
    fun modelInfo(): String? = handle.takeIf { it != 0L }?.let(LlamaNative::modelInfo)

    override fun createSession(config: SessionConfig): ChatSession {
        if (handle == 0L) throw LlmException("No model loaded")
        return LlamaSession(this, config)
    }

    internal fun requireHandle(): Long = handle.takeIf { it != 0L } ?: throw LlmException("Model was unloaded")

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) LlamaNative.freeModel(h)
    }

    private fun defaultThreads(): Int {
        // Phones mix fast and slow cores; using only the fast ones is usually quickest.
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores - 2).coerceIn(2, 4)
    }
}

private val json = Json { ignoreUnknownKeys = true }

private class LlamaSession(
    private val engine: LlamaCppEngine,
    private val config: SessionConfig,
) : ChatSession {
    /** OpenAI chat-completions messages. The first is the system prompt. */
    private val history = mutableListOf(message("system", config.systemPrompt))
    private var pendingCalls: List<ToolCall> = emptyList()
    private val toolsJson = config.tools.toOpenAiJson()

    override fun send(input: TurnInput): Flow<GenerationEvent> = channelFlow {
        val rollbackSize = history.size
        val rollbackCalls = pendingCalls
        when (input) {
            is TurnInput.User -> {
                skipPendingCalls()
                history += message("user", input.text)
            }
            is TurnInput.ToolResults -> {
                input.results.forEach { history += toolMessage(it) }
                pendingCalls = emptyList()
            }
        }

        try {
            val result = generateWithTrimming { kind, utf8 ->
                val text = String(utf8, Charsets.UTF_8)
                trySend(
                    if (kind == LlamaNative.DELTA_REASONING) GenerationEvent.ReasoningDelta(text)
                    else GenerationEvent.TextDelta(text),
                )
                true
            }
            val assistant = result["message"]!!.jsonObject
            history += sanitizeAssistant(assistant)
            val calls = parseToolCalls(assistant)
            if (calls.isNotEmpty()) {
                pendingCalls = calls
                send(GenerationEvent.ToolCalls(calls))
            }
            send(GenerationEvent.Finished(parseStats(result)))
        } catch (e: Throwable) {
            // Keep the history consistent: the turn never happened.
            while (history.size > rollbackSize) history.removeAt(history.lastIndex)
            pendingCalls = rollbackCalls
            throw e
        }
    }.buffer(Channel.UNLIMITED)

    /** Drops the oldest exchanges when the conversation outgrows the context window. */
    private suspend fun generateWithTrimming(callback: LlamaNative.DeltaCallback): JsonObject {
        repeat(MAX_TRIMS + 1) {
            try {
                return generate(callback)
            } catch (e: IllegalStateException) {
                val overflow = e.message?.startsWith("context_overflow") == true
                if (!overflow || !trimOldestExchange()) throw LlmException(e.message ?: "Generation failed", e)
            }
        }
        throw LlmException("Conversation too long for the context window")
    }

    private suspend fun generate(callback: LlamaNative.DeltaCallback): JsonObject = coroutineScope {
        val handle = engine.requireHandle()
        val request = buildJsonObject {
            put("messages", kotlinx.serialization.json.JsonArray(history))
            put("tools", toolsJson)
            put("temperature", config.sampling.temperature)
            put("top_k", config.sampling.topK)
            put("top_p", config.sampling.topP)
            put("seed", config.sampling.seed)
            put("max_tokens", config.sampling.maxTokens)
            put("enable_thinking", config.enableThinking)
        }.toString()

        val call = async(Dispatchers.IO) { LlamaNative.generate(handle, request, callback) }
        try {
            json.parseToJsonElement(String(call.await(), Charsets.UTF_8)).jsonObject
        } catch (e: CancellationException) {
            LlamaNative.cancel(handle) // unblocks the native loop so the scope can finish
            throw e
        }
    }

    /** Removes everything from the first user message up to (not including) the next one. */
    private fun trimOldestExchange(): Boolean {
        val nextUser = (2 until history.size).firstOrNull { role(history[it]) == "user" } ?: return false
        repeat(nextUser - 1) { history.removeAt(1) }
        return true
    }

    private fun skipPendingCalls() {
        pendingCalls.forEach { history += toolMessage(ToolResult(it.id, it.name, buildJsonObject { put("error", "skipped") })) }
        pendingCalls = emptyList()
    }

    override fun cancel() {
        runCatching { LlamaNative.cancel(engine.requireHandle()) }
    }

    override fun close() = cancel()

    companion object {
        const val MAX_TRIMS = 8
    }
}

private fun message(role: String, content: String) = buildJsonObject {
    put("role", role)
    put("content", content)
}

private fun role(message: JsonObject) = message["role"]?.jsonPrimitive?.contentOrNull

private fun toolMessage(result: ToolResult) = buildJsonObject {
    put("role", "tool")
    put("tool_call_id", result.callId)
    put("name", result.name)
    val content = result.content
    put("content", if (content is JsonPrimitive && content.isString) content.content else content.toString())
}

/** llama.cpp may return `"content": null` next to tool calls; templates expect a string. */
private fun sanitizeAssistant(message: JsonObject): JsonObject {
    val content = message["content"]
    if (content != null && content !is JsonNull) return message
    return JsonObject(message + ("content" to JsonPrimitive("")))
}

private fun parseToolCalls(message: JsonObject): List<ToolCall> {
    val calls = message["tool_calls"]?.takeIf { it !is JsonNull }?.jsonArray ?: return emptyList()
    return calls.map { element ->
        val call = element.jsonObject
        val function = call["function"]!!.jsonObject
        ToolCall(
            id = call["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            name = function["name"]!!.jsonPrimitive.content,
            arguments = parseArguments(function["arguments"]),
        )
    }
}

private fun parseArguments(raw: JsonElement?): JsonObject = when {
    raw is JsonObject -> raw
    raw is JsonPrimitive && raw.isString -> runCatching { json.parseToJsonElement(raw.content).jsonObject }
        .getOrDefault(JsonObject(emptyMap()))
    else -> JsonObject(emptyMap())
}

private fun parseStats(result: JsonObject): GenerationStats {
    val prompt = result["prompt_tokens"]!!.jsonPrimitive.int
    val reused = result["reused_tokens"]!!.jsonPrimitive.int
    val generated = result["generated_tokens"]!!.jsonPrimitive.int
    val prefillMs = result["prefill_ms"]!!.jsonPrimitive.double
    val decodeMs = result["decode_ms"]!!.jsonPrimitive.double
    return GenerationStats(
        promptTokens = prompt,
        generatedTokens = generated,
        prefillTokensPerSecond = if (prefillMs > 0) (prompt - reused) / prefillMs * 1000 else 0.0,
        decodeTokensPerSecond = if (decodeMs > 0) generated / decodeMs * 1000 else 0.0,
    )
}
