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
/**
 * What kind of user request a tool serves. Tools declare this so routing never needs a
 * hard-coded name list (see core/agent/IntentRouter.kt).
 *
 *  - INFORMATION: returns data the assistant reads back to the user (weather, battery, notifications…).
 *  - ACTION: changes something on the phone or launches an app; the effect is the result.
 *  - UI: shows something on screen / drives the browser or another app's UI (open URL, web search,
 *    read screen, tap…). Used only when the user explicitly asks to see/open/search/look.
 */
enum class ToolIntent { INFORMATION, ACTION, UI }

data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParam> = emptyList(),
    val intent: ToolIntent = ToolIntent.ACTION,
    /**
     * True when the raw output is machine-oriented (screen dumps, lists) and must be summarised by
     * the model rather than shown to the user as-is.
     */
    val rawOutput: Boolean = false,
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
