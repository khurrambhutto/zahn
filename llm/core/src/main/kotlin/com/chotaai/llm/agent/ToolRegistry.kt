package com.chotaai.llm.agent

import com.chotaai.llm.ParamType
import com.chotaai.llm.ToolCall
import com.chotaai.llm.ToolParam
import com.chotaai.llm.ToolResult
import com.chotaai.llm.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A function the assistant can call. Implementations live in the app (tasks, habits, ...). */
interface Tool {
    val spec: ToolSpec

    /**
     * Runs the tool. [args] has already been validated against [spec]: required params are present
     * and values have the declared types. Throw [ToolError] for errors the model should see and recover from.
     */
    suspend fun execute(args: JsonObject): JsonElement
}

class ToolError(message: String) : Exception(message)

data class ToolOutcome(val result: ToolResult, val isError: Boolean)

class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.spec.name }

    val specs: List<ToolSpec> = tools.map { it.spec }

    suspend fun execute(call: ToolCall): ToolOutcome {
        val tool = byName[call.name]
            ?: return error(call, "Unknown tool '${call.name}'. Available: ${byName.keys.joinToString()}")
        val args = try {
            validate(tool.spec, call.arguments)
        } catch (e: ToolError) {
            return error(call, e.message ?: "invalid arguments")
        }
        return try {
            ToolOutcome(ToolResult(call.id, call.name, tool.execute(args)), isError = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolError) {
            error(call, e.message ?: "tool failed")
        } catch (e: Exception) {
            error(call, "Tool crashed: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun error(call: ToolCall, message: String) = ToolOutcome(
        ToolResult(call.id, call.name, buildJsonObject { put("error", message) }),
        isError = true,
    )

    /**
     * Small models often send `"3"` for an integer or `"true"` for a boolean. Coerce what is
     * unambiguous, drop unknown keys, and reject the rest with a message the model can act on.
     */
    private fun validate(spec: ToolSpec, raw: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for (param in spec.params) {
            val value = raw[param.name]
            if (value == null || value is JsonNull || (value is JsonPrimitive && value.isString && value.content.isBlank())) {
                if (param.required) throw ToolError("Missing required argument '${param.name}': ${param.description}")
                continue
            }
            out[param.name] = coerce(param, value)
        }
        return JsonObject(out)
    }

    private fun coerce(param: ToolParam, value: JsonElement): JsonElement {
        val prim = value as? JsonPrimitive
            ?: throw ToolError("Argument '${param.name}' must be a ${param.type.jsonType}")
        val coerced: JsonPrimitive? = when (param.type) {
            ParamType.STRING -> JsonPrimitive(prim.content.trim())
            ParamType.INTEGER -> (prim.longOrNull ?: prim.doubleOrNull?.toLong() ?: prim.content.trim().toLongOrNull())
                ?.let(::JsonPrimitive)
            ParamType.NUMBER -> (prim.doubleOrNull ?: prim.content.trim().toDoubleOrNull())?.let(::JsonPrimitive)
            ParamType.BOOLEAN -> (prim.booleanOrNull ?: prim.content.trim().lowercase().toBooleanStrictOrNull())
                ?.let(::JsonPrimitive)
        }
        coerced ?: throw ToolError("Argument '${param.name}' must be a ${param.type.jsonType}, got '${prim.content}'")
        val allowed = param.enumValues
        if (allowed != null) {
            val match = allowed.firstOrNull { it.equals(coerced.content, ignoreCase = true) }
                ?: throw ToolError("Argument '${param.name}' must be one of ${allowed.joinToString()}")
            return JsonPrimitive(match)
        }
        return coerced
    }
}
