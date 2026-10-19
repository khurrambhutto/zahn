package com.chotaai.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class ParamType(val jsonType: String) {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
}

data class ToolParam(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String>? = null,
)

/** Engine-neutral description of a function the model may call. */
data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam> = emptyList(),
) {
    /** JSON Schema of the arguments object. */
    fun parametersSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for (p in params) {
                putJsonObject(p.name) {
                    put("type", p.type.jsonType)
                    put("description", p.description)
                    p.enumValues?.let { values -> putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } } }
                }
            }
        }
        putJsonArray("required") { params.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } }
    }

    /** `{"name", "description", "parameters"}`, the shape LiteRT-LM's OpenApiTool expects. */
    fun toFunctionJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        put("parameters", parametersSchema())
    }

    /** OpenAI chat-completions shape, which llama.cpp's chat templates consume. */
    fun toOpenAiJson(): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", toFunctionJson())
    }
}

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

data class ToolResult(
    val callId: String,
    val name: String,
    val content: JsonElement,
)

fun List<ToolSpec>.toOpenAiJson(): JsonArray = JsonArray(map { it.toOpenAiJson() })
