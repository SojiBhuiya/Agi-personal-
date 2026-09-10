package com.agi.assistant.core.tools

import org.json.JSONArray
import org.json.JSONObject

enum class ParamType(val jsonType: String) { STRING("string"), INTEGER("integer"), NUMBER("number"), BOOLEAN("boolean") }

data class ToolParam(
    val name: String,
    val type: ParamType,
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String>? = null,
)

/**
 * Describes a tool to the model. Serialised as a JSON-Schema style object that
 * both OpenAI-compatible APIs and Gemini understand.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam> = emptyList(),
) {
    fun parametersSchema(): JSONObject {
        val props = JSONObject()
        val required = JSONArray()
        params.forEach { p ->
            props.put(p.name, JSONObject().apply {
                put("type", p.type.jsonType)
                put("description", p.description)
                p.enumValues?.let { put("enum", JSONArray(it)) }
            })
            if (p.required) required.put(p.name)
        }
        return JSONObject().apply {
            put("type", "object")
            put("properties", props)
            if (required.length() > 0) put("required", required)
        }
    }
}
