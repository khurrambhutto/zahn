package com.chotaai.llm

import com.chotaai.llm.agent.Agent
import com.chotaai.llm.agent.AgentEvent
import com.chotaai.llm.agent.Tool
import com.chotaai.llm.agent.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTest {
    private class ScriptedSession(private val turns: List<List<GenerationEvent>>) : ChatSession {
        val inputs = mutableListOf<TurnInput>()
        override fun send(input: TurnInput): Flow<GenerationEvent> {
            inputs += input
            return flowOf(*turns[inputs.size - 1].toTypedArray())
        }
        override fun cancel() = Unit
        override fun close() = Unit
    }

    private val addTask = object : Tool {
        val received = mutableListOf<JsonObject>()
        override val spec = ToolSpec(
            "add_task", "Add a task",
            listOf(
                ToolParam("title", ParamType.STRING, "Task title"),
                ToolParam("priority", ParamType.INTEGER, "1-3", required = false),
            ),
        )
        override suspend fun execute(args: JsonObject): JsonElement {
            received += args
            return JsonPrimitive("ok")
        }
    }

    private fun call(name: String, vararg args: Pair<String, String>) =
        ToolCall("c1", name, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))

    @Test
    fun runsToolThenReturnsFinalText() = runTest {
        val session = ScriptedSession(
            listOf(
                listOf(GenerationEvent.ToolCalls(listOf(call("add_task", "title" to "Buy milk", "priority" to "2")))),
                listOf(GenerationEvent.TextDelta("Added."), GenerationEvent.Finished()),
            ),
        )
        val events = Agent(session, ToolRegistry(listOf(addTask))).run("add buy milk").toList()

        assertEquals(2L, addTask.received.single()["priority"]!!.jsonPrimitive.content.toLong())
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals(false, finished.isError)
        assertTrue(session.inputs[1] is TurnInput.ToolResults)
        assertEquals(AgentEvent.TextDelta("Added."), events[events.size - 2])
    }

    @Test
    fun missingRequiredArgIsReportedToModel() = runTest {
        val session = ScriptedSession(
            listOf(
                listOf(GenerationEvent.ToolCalls(listOf(call("add_task")))),
                listOf(GenerationEvent.TextDelta("What should the task be?")),
            ),
        )
        val events = Agent(session, ToolRegistry(listOf(addTask))).run("add a task").toList()

        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertTrue(finished.isError)
        assertTrue(finished.result.jsonObject["error"]!!.jsonPrimitive.content.contains("title"))
        assertTrue(addTask.received.isEmpty())
    }

    @Test
    fun unknownToolIsAnError() = runTest {
        val session = ScriptedSession(
            listOf(
                listOf(GenerationEvent.ToolCalls(listOf(call("fly_to_moon")))),
                listOf(GenerationEvent.TextDelta("Sorry.")),
            ),
        )
        val events = Agent(session, ToolRegistry(listOf(addTask))).run("go").toList()
        assertTrue(events.filterIsInstance<AgentEvent.ToolFinished>().single().isError)
    }
}
