package com.agi.assistant.core.ai.providers

import com.agi.assistant.core.ai.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Google Gemini `generateContent` REST API with function calling.
 *
 * Gemini 3 attaches an opaque `thoughtSignature` to the Part that carries a `functionCall`; the
 * same part must be resent with `thought_signature` byte-for-byte on every later turn, otherwise
 * the API answers HTTP 400 "Function call is missing a thought_signature". [parseResponse] keeps
 * it on the matching [ToolCall.providerSignature] and [buildBody] puts it back on exactly that
 * part (parallel calls: only the parts that had one). Never logged.
 */
class GeminiProvider(
    private val config: ProviderConfig,
    private val transport: HttpTransport = UrlConnectionTransport,
) : AiProvider {
    override val id = "gemini"
    override val displayName = "Gemini (${GeminiModels.normalize(config.model)})"
    override val isRemote = true

    /** `{baseUrl}/v1beta/models/{model}:generateContent` with the model id normalised (no `models/` prefix). */
    val endpoint: String get() = config.baseUrl.trimEnd('/') + "/v1beta/models/${GeminiModels.normalize(config.model)}:generateContent"

    /** True for gemini-3* model ids (thinking models with their own tuned defaults). */
    val isGemini3: Boolean get() = GeminiModels.normalize(config.model).lowercase().startsWith("gemini-3")

    override suspend fun complete(request: AiRequest): AiResponse {
        config.validationError()?.let { throw AiProviderException(it, kind = ProviderErrorKind.CONFIG) }
        val json = HttpJson.post(endpoint, buildBody(request), headers(), config.timeoutMs, config.secrets, transport)
        return parseResponse(json)
    }

    /** Builds the generateContent body from the provider-neutral history (pure; JVM-tested). */
    fun buildBody(request: AiRequest): JSONObject {
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
                        val fc = JSONObject().put("functionCall", JSONObject().put("name", c.name).put("args", c.argumentsJson()))
                        // Echo the signature on exactly the part Gemini returned it with; never invent one.
                        c.providerSignature?.let { fc.put(THOUGHT_SIGNATURE_WIRE, it) }
                        parts.put(fc)
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

        return JSONObject().apply {
            put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", request.systemPrompt))))
            put("contents", contents)
            // Gemini 3 docs: leave temperature at the model default (1.0); explicit low values can cause
            // looping/degraded output. Older Gemini models keep the request temperature.
            if (!isGemini3) put("generationConfig", JSONObject().put("temperature", request.temperature))
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
    }

    /** Parses a generateContent response; keeps each functionCall's thought signature on its ToolCall. */
    fun parseResponse(json: JSONObject): AiResponse {
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw AiProviderException("Gemini returned no candidates: ${Redactor.redact(json.toString().take(120), config.secrets)}", kind = ProviderErrorKind.MALFORMED)
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            p.optString("text").takeIf { it.isNotBlank() }?.let { text.append(it) }
            p.optJSONObject("functionCall")?.let { fc ->
                val name = fc.optString("name").takeIf { it.isNotBlank() } ?: return@let
                calls += ToolCall(
                    "call_${UUID.randomUUID()}", name, fc.optJSONObject("args")?.toMap() ?: emptyMap(),
                    providerSignature = signatureOf(p),
                )
            }
        }
        if (text.isBlank() && calls.isEmpty())
            throw AiProviderException("Gemini returned an empty message.", kind = ProviderErrorKind.EMPTY)
        return AiResponse(text.toString().ifBlank { null }, calls, id, config.model)
    }

    /** Reads the part-level signature exactly as sent (REST uses camelCase; accept snake_case too). */
    private fun signatureOf(part: JSONObject): String? =
        part.optString("thoughtSignature").takeIf { it.isNotEmpty() }
            ?: part.optString(THOUGHT_SIGNATURE_WIRE).takeIf { it.isNotEmpty() }

    /** Only the key header; no user-facing label or other local setting ever goes on the wire. */
    fun headers(): Map<String, String> = mapOf("x-goog-api-key" to config.apiKey)

    companion object {
        /** Request wire field for the generateContent REST API. */
        const val THOUGHT_SIGNATURE_WIRE = "thought_signature"
    }

    private fun part(role: String, text: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))
}
