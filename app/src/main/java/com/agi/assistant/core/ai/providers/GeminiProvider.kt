package com.agi.assistant.core.ai.providers

import com.agi.assistant.core.ai.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Google Gemini `generateContent` API with function calling. */
class GeminiProvider(
    private val config: ProviderConfig,
    private val transport: HttpTransport = UrlConnectionTransport,
) : AiProvider {
    override val id = "gemini"
    override val displayName = "Gemini (${GeminiModels.normalize(config.model)})"
    override val isRemote = true

    /** `{baseUrl}/v1beta/models/{model}:generateContent` with the model id normalised (no `models/` prefix). */
    val endpoint: String get() = config.baseUrl.trimEnd('/') + "/v1beta/models/${GeminiModels.normalize(config.model)}:generateContent"

    override suspend fun complete(request: AiRequest): AiResponse {
        val contents = JSONArray()
        // Gemini requires tool responses to be grouped after the model call.
        request.messages.forEach { m ->
            when (m.role) {
                Role.SYSTEM -> contents.put(part("user", "System note: ${m.content}"))
                Role.USER -> contents.put(part("user", m.content))
                Role.ASSISTANT -> {
                    val parts = JSONArray()
                    if (m.content.isNotBlank()) parts.put(JSONObject().put("text", m.content))
                    m.toolCalls.forEach { c ->
                        parts.put(JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", c.argumentsJson())))
                    }
                    if (parts.length() == 0) parts.put(JSONObject().put("text", ""))
                    contents.put(JSONObject().put("role", "model").put("parts", parts))
                }
                Role.TOOL -> {
                    val fr = JSONObject().put("functionResponse", JSONObject().apply {
                        put("name", m.toolName ?: "tool")
                        put("response", JSONObject().put("result", m.content))
                    })
                    // Merge consecutive tool responses into one "user" content.
                    val last = if (contents.length() > 0) contents.getJSONObject(contents.length() - 1) else null
                    if (last != null && last.optString("role") == "user" && last.getJSONArray("parts").optJSONObject(0)?.has("functionResponse") == true) {
                        last.getJSONArray("parts").put(fr)
                    } else {
                        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(fr)))
                    }
                }
            }
        }

        val body = JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.systemPrompt))))
            put("contents", contents)
            put("generationConfig", JSONObject().put("temperature", request.temperature))
            if (request.tools.isNotEmpty()) {
                put("tools", JSONArray().put(JSONObject().put("function_declarations", JSONArray().apply {
                    request.tools.forEach { t ->
                        put(JSONObject().apply {
                            put("name", t.name)
                            put("description", t.description)
                            if (t.params.isNotEmpty()) put("parameters", t.parametersSchema())
                        })
                    }
                })))
            }
        }

        config.validationError()?.let { throw AiProviderException(it, kind = ProviderErrorKind.CONFIG) }
        // Key goes in a header (not the query string) so it can never leak through URL logging.
        val url = endpoint
        val json = HttpJson.post(url, body, headers(), config.timeoutMs, config.secrets, transport)
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw AiProviderException("Gemini returned no candidates: ${Redactor.redact(json.toString().take(120), config.secrets)}", kind = ProviderErrorKind.MALFORMED)
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            p.optString("text").takeIf { it.isNotBlank() }?.let { text.append(it) }
            p.optJSONObject("functionCall")?.let { fc ->
                calls += ToolCall("call_${UUID.randomUUID()}", fc.getString("name"), fc.optJSONObject("args")?.toMap() ?: emptyMap())
            }
        }
        return AiResponse(text.toString().ifBlank { null }, calls, id, config.model)
    }

    /** Only the key header; no user-facing label or other local setting ever goes on the wire. */
    fun headers(): Map<String, String> = mapOf("x-goog-api-key" to config.apiKey)

    private fun part(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))
}
