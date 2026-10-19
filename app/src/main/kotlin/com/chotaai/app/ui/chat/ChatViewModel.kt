package com.chotaai.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chotaai.app.AppContainer
import com.chotaai.app.ai.EngineState
import com.chotaai.app.ai.SUMMARY_KEY
import com.chotaai.app.ai.SystemPrompt
import com.chotaai.llm.ChatSession
import com.chotaai.llm.LlmEngine
import com.chotaai.llm.SamplingParams
import com.chotaai.llm.SessionConfig
import com.chotaai.llm.agent.Agent
import com.chotaai.llm.agent.AgentEvent
import com.chotaai.llm.agent.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicLong

sealed interface ChatItem {
    val id: Long

    data class User(override val id: Long, val text: String) : ChatItem
    data class Assistant(
        override val id: Long,
        val text: String = "",
        val reasoning: String = "",
        val streaming: Boolean = true,
        val stats: String? = null,
    ) : ChatItem
    data class ToolActivity(
        override val id: Long,
        val toolName: String,
        val arguments: String,
        val summary: String? = null,
        val isError: Boolean = false,
    ) : ChatItem
    data class Notice(override val id: Long, val text: String) : ChatItem
}

data class ChatUiState(
    val engine: EngineState = EngineState.NoModel,
    val items: List<ChatItem> = emptyList(),
    val generating: Boolean = false,
)

class ChatViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private val ids = AtomicLong()
    private val registry = ToolRegistry(container.tools.all)
    private var session: ChatSession? = null
    private var agent: Agent? = null
    private var generation: Job? = null

    init {
        viewModelScope.launch {
            container.engineManager.state.collect { engine ->
                _state.update { it.copy(engine = engine) }
                if (engine is EngineState.Ready) startSession(engine.engine) else endSession()
            }
        }
    }

    fun send(text: String) {
        val message = text.trim()
        val agent = agent
        if (message.isEmpty() || agent == null || generation?.isActive == true) return
        addItem(ChatItem.User(ids.incrementAndGet(), message))

        generation = viewModelScope.launch {
            _state.update { it.copy(generating = true) }
            var assistantId = startAssistantBubble()
            try {
                agent.run(message).collect { event ->
                    when (event) {
                        is AgentEvent.TextDelta -> updateAssistant(assistantId) { it.copy(text = it.text + event.text) }
                        is AgentEvent.ReasoningDelta -> updateAssistant(assistantId) { it.copy(reasoning = it.reasoning + event.text) }
                        is AgentEvent.ToolStarted -> {
                            removeIfEmpty(assistantId)
                            addItem(ChatItem.ToolActivity(ids.incrementAndGet(), event.call.name, event.call.arguments.toString()))
                        }
                        is AgentEvent.ToolFinished -> {
                            val result = event.result as? JsonObject
                            val summary = result?.get(if (event.isError) "error" else SUMMARY_KEY)?.jsonPrimitive?.contentOrNull
                            updateLastTool(event.call.name) { it.copy(summary = summary ?: event.result.toString(), isError = event.isError) }
                            // The model answers after seeing the results; give it a fresh bubble below the tool rows.
                            assistantId = startAssistantBubble()
                        }
                        is AgentEvent.Done -> {
                            val stats = event.stats?.let { "%.1f tok/s".format(it.decodeTokensPerSecond) }
                            updateAssistant(assistantId) { it.copy(streaming = false, stats = stats) }
                            removeIfEmpty(assistantId)
                            if (event.hitToolLimit) addItem(ChatItem.Notice(ids.incrementAndGet(), "Stopped after too many tool calls."))
                        }
                    }
                }
            } catch (e: CancellationException) {
                updateAssistant(assistantId) { it.copy(streaming = false) }
                removeIfEmpty(assistantId)
                throw e
            } catch (e: Exception) {
                removeIfEmpty(assistantId)
                addItem(ChatItem.Notice(ids.incrementAndGet(), "Error: ${e.message ?: e::class.java.simpleName}"))
            } finally {
                _state.update { it.copy(generating = false) }
            }
        }
    }

    fun stop() {
        session?.cancel()
        generation?.cancel()
    }

    fun newChat() {
        stop()
        val engine = (_state.value.engine as? EngineState.Ready)?.engine ?: return
        viewModelScope.launch {
            generation?.join()
            startSession(engine)
        }
    }

    private suspend fun startSession(engine: LlmEngine) {
        endSession()
        val settings = container.settings.current()
        session = engine.createSession(
            SessionConfig(
                systemPrompt = SystemPrompt.build(),
                tools = registry.specs,
                sampling = SamplingParams(temperature = settings.temperature),
            ),
        ).also { agent = Agent(it, registry) }
        _state.update { it.copy(items = emptyList()) }
    }

    private fun endSession() {
        generation?.cancel()
        session?.close()
        session = null
        agent = null
    }

    private fun startAssistantBubble(): Long {
        val id = ids.incrementAndGet()
        addItem(ChatItem.Assistant(id))
        return id
    }

    private fun addItem(item: ChatItem) = _state.update { it.copy(items = it.items + item) }

    private fun updateAssistant(id: Long, change: (ChatItem.Assistant) -> ChatItem.Assistant) = _state.update { s ->
        s.copy(items = s.items.map { if (it.id == id && it is ChatItem.Assistant) change(it) else it })
    }

    private fun updateLastTool(name: String, change: (ChatItem.ToolActivity) -> ChatItem.ToolActivity) = _state.update { s ->
        val index = s.items.indexOfLast { it is ChatItem.ToolActivity && it.toolName == name && it.summary == null }
        if (index < 0) s else s.copy(items = s.items.toMutableList().also { it[index] = change(it[index] as ChatItem.ToolActivity) })
    }

    private fun removeIfEmpty(id: Long) = _state.update { s ->
        s.copy(items = s.items.filterNot { it.id == id && it is ChatItem.Assistant && it.text.isBlank() && it.reasoning.isBlank() })
    }

    override fun onCleared() = endSession()
}
