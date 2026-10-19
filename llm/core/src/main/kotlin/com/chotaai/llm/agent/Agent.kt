package com.chotaai.llm.agent

import com.chotaai.llm.ChatSession
import com.chotaai.llm.GenerationEvent
import com.chotaai.llm.GenerationStats
import com.chotaai.llm.ToolCall
import com.chotaai.llm.TurnInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ReasoningDelta(val text: String) : AgentEvent
    data class ToolStarted(val call: ToolCall) : AgentEvent
    data class ToolFinished(val call: ToolCall, val result: JsonElement, val isError: Boolean) : AgentEvent
    data class Done(val stats: GenerationStats?, val hitToolLimit: Boolean = false) : AgentEvent
}

/**
 * The tool loop, shared by every engine:
 *
 * user text → model → tool calls? → run them → feed results back → model → ... → final text.
 */
class Agent(
    private val session: ChatSession,
    private val tools: ToolRegistry,
    private val maxToolRounds: Int = 4,
) {
    fun run(userText: String): Flow<AgentEvent> = flow {
        var input: TurnInput = TurnInput.User(userText)
        var lastStats: GenerationStats? = null

        for (round in 0..maxToolRounds) {
            var calls: List<ToolCall> = emptyList()
            session.send(input).collect { event ->
                when (event) {
                    is GenerationEvent.TextDelta -> emit(AgentEvent.TextDelta(event.text))
                    is GenerationEvent.ReasoningDelta -> emit(AgentEvent.ReasoningDelta(event.text))
                    is GenerationEvent.ToolCalls -> calls = event.calls
                    is GenerationEvent.Finished -> lastStats = event.stats ?: lastStats
                }
            }
            if (calls.isEmpty()) {
                emit(AgentEvent.Done(lastStats))
                return@flow
            }

            val results = calls.map { call ->
                emit(AgentEvent.ToolStarted(call))
                val outcome = tools.execute(call)
                emit(AgentEvent.ToolFinished(call, outcome.result.content, outcome.isError))
                outcome.result
            }
            input = TurnInput.ToolResults(results)
        }
        // The model kept calling tools. The results of the last round were never sent back,
        // so the actions happened but the model didn't get to summarize them.
        emit(AgentEvent.Done(lastStats, hitToolLimit = true))
    }
}
