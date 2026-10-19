package com.chotaai.llm.litert

import com.chotaai.llm.Accelerator
import com.chotaai.llm.ChatSession
import com.chotaai.llm.EngineType
import com.chotaai.llm.GenerationEvent
import com.chotaai.llm.LlmEngine
import com.chotaai.llm.LlmException
import com.chotaai.llm.ModelConfig
import com.chotaai.llm.SessionConfig
import com.chotaai.llm.ToolCall
import com.chotaai.llm.ToolResult
import com.chotaai.llm.ToolSpec
import com.chotaai.llm.TurnInput
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs `.litertlm` models with Google's LiteRT-LM (CPU, GPU, or NPU).
 *
 * LiteRT-LM applies the model's own chat template and parses its tool calls. Its automatic tool
 * execution is turned off so the shared [com.chotaai.llm.agent.Agent] loop runs the tools for
 * both engines.
 *
 * @param nativeLibraryDir `context.applicationInfo.nativeLibraryDir`, needed for the NPU backend.
 * @param cacheDir where LiteRT-LM may cache compiled GPU kernels; speeds up later loads.
 */
class LiteRtEngine(
    private val nativeLibraryDir: String,
    private val cacheDir: String?,
) : LlmEngine {
    override val type = EngineType.LITERT

    @Volatile private var engine: Engine? = null

    override val isLoaded: Boolean get() = engine != null

    override suspend fun load(config: ModelConfig) = withContext(Dispatchers.IO) {
        close()
        val backend = when (config.accelerator) {
            Accelerator.CPU -> Backend.CPU(threadCount = config.threads.takeIf { it > 0 })
            Accelerator.GPU -> Backend.GPU()
            Accelerator.NPU -> Backend.NPU(nativeLibraryDir = nativeLibraryDir)
        }
        val newEngine = Engine(
            EngineConfig(
                modelPath = config.path,
                backend = backend,
                maxNumTokens = config.contextSize,
                cacheDir = cacheDir,
            ),
        )
        try {
            newEngine.initialize()
        } catch (e: Exception) {
            newEngine.close()
            throw LlmException("LiteRT-LM could not load ${config.path}: ${e.message}", e)
        }
        engine = newEngine
    }

    override fun createSession(config: SessionConfig): ChatSession {
        val engine = engine ?: throw LlmException("No model loaded")
        val conversation = engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(config.systemPrompt),
                tools = config.tools.map { tool(SpecTool(it)) },
                samplerConfig = SamplerConfig(
                    topK = config.sampling.topK,
                    topP = config.sampling.topP.toDouble(),
                    temperature = config.sampling.temperature.toDouble(),
                    seed = config.sampling.seed,
                ),
                automaticToolCalling = false,
                maxOutputToken = config.sampling.maxTokens,
                thinkingConfig = ThinkingConfig(enableThinking = config.enableThinking),
            ),
        )
        return LiteRtSession(conversation)
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}

/** Describes a tool to LiteRT-LM. Never executed: automatic tool calling is off. */
private class SpecTool(private val spec: ToolSpec) : OpenApiTool {
    override fun getToolDescriptionJsonString(): String = spec.toFunctionJson().toString()
    override fun execute(paramsJsonString: String): String =
        error("Tools are executed by the Agent, not by LiteRT-LM")
}

private class LiteRtSession(private val conversation: Conversation) : ChatSession {
    private var pendingCalls: List<ToolCall> = emptyList()

    override fun send(input: TurnInput): Flow<GenerationEvent> = callbackFlow {
        val message = when (input) {
            is TurnInput.User -> {
                if (pendingCalls.isNotEmpty()) skipPendingCalls()
                Message.user(input.text)
            }
            is TurnInput.ToolResults -> toolMessage(input.results)
        }
        pendingCalls = emptyList()

        val done = AtomicBoolean(false)
        val calls = mutableListOf<ToolCall>()
        conversation.sendMessageAsync(
            message,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    // Streaming messages are deltas. Tool calls arrive whole, in one message.
                    for (content in message.contents.contents) {
                        if (content is Content.Text && content.text.isNotEmpty()) {
                            trySend(GenerationEvent.TextDelta(content.text))
                        }
                    }
                    for (thought in message.channels.values) {
                        if (thought.isNotEmpty()) trySend(GenerationEvent.ReasoningDelta(thought))
                    }
                    message.toolCalls.mapTo(calls) {
                        ToolCall(UUID.randomUUID().toString(), it.name, it.arguments.toJsonObject())
                    }
                }

                override fun onDone() {
                    done.set(true)
                    if (calls.isNotEmpty()) {
                        pendingCalls = calls.toList()
                        trySend(GenerationEvent.ToolCalls(pendingCalls))
                    }
                    trySend(GenerationEvent.Finished())
                    close()
                }

                override fun onError(throwable: Throwable) {
                    done.set(true)
                    close(throwable)
                }
            },
        )
        awaitClose { if (!done.get()) conversation.cancelProcess() }
    }.flowOn(Dispatchers.IO)

    /** The user moved on without the tool results being sent; close the turn so the history stays valid. */
    private fun skipPendingCalls() {
        val skipped = pendingCalls.map { ToolResult(it.id, it.name, buildJsonObject { put("error", "skipped") }) }
        pendingCalls = emptyList()
        conversation.sendMessage(toolMessage(skipped))
    }

    private fun toolMessage(results: List<ToolResult>): Message =
        Message.tool(Contents.of(results.map { Content.ToolResponse(it.name, it.content.toPlain()) }))

    override fun cancel() = conversation.cancelProcess()

    override fun close() = conversation.close()
}

private fun Map<String, Any?>.toJsonObject(): JsonObject = JsonObject(mapValues { (_, v) -> v.toJsonElement() })

private fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    else -> JsonPrimitive(toString())
}

/** LiteRT-LM serializes tool responses with Gson, so hand it plain Kotlin values. */
private fun JsonElement.toPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull ?: content
    is JsonObject -> mapValues { (_, v) -> v.toPlain() }
    is JsonArray -> map { it.toPlain() }
}
