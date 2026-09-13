package com.agi.assistant.core.ai.providers

import com.agi.assistant.core.ai.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Talks to any endpoint implementing the OpenAI Chat Completions API with
 * function calling: OpenAI, Groq, OpenRouter, Together, Mistral, DeepSeek,
 * Ollama, LM Studio, vLLM, llama.cpp server, ...
 *
 * Request construction ([buildBody]) and response parsing ([parse]) are pure functions so the
 * wire format is covered by JVM tests; [transport] is injectable for the same reason.
 * Responses are requested non-streaming (`stream: false`): the agent loop consumes whole turns
 * (text and/or tool calls), so no partial-token path exists in the current architecture.
 */
class OpenAiCompatibleProvider(
    private val config: ProviderConfig,
    private val transport: HttpTransport = UrlConnectionTransport,
) : AiProvider {
    override val id = "openai_compatible"
    override val displayName = "OpenAI-compatible (${config.model})"
    override val isRemote = true

    /** The exact URL that will be called: configured Base URL + `/chat/completions`. */
    val endpoint: String get() = OpenAiEndpoint.chatCompletions(config.baseUrl)

    override suspend fun complete(request: AiRequest): AiResponse {
        config.validationError()?.let { throw AiProviderException(it, kind = ProviderErrorKind.CONFIG) }
        val json = HttpJson.post(endpoint, buildBody(request), headers(), config.timeoutMs, config.secrets, transport)
        return parse(json)
    }

    /** Headers for the request; the key only ever travels in the Authorization header. */
    fun headers(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey}"
        // OpenRouter attribution headers are harmless for other providers.
        headers["HTTP-Referer"] = "https://github.com/SojiBhuiya/Agi-personal-"
        headers["X-Title"] = "AGI Personal Assistant"
        return headers
    }

    /** Builds the chat-completions body: configured model, system prompt first, full history, tools. */
    fun buildBody(request: AiRequest): JSONObject {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
        request.messages.forEach { m ->
            when (m.role) {
                Role.SYSTEM -> messages.put(JSONObject().put("role", "system").put("content", m.content))
                Role.USER -> messages.put(JSONObject().put("role", "user").put("content", m.content))
                Role.ASSISTANT -> {
                    val o = JSONObject().put("role", "assistant")
                    if (m.content.isNotBlank() || m.toolCalls.isEmpty()) o.put("content", m.content)
                    if (m.toolCalls.isNotEmpty()) {
                        o.put("tool_calls", JSONArray().apply {
                            m.toolCalls.forEach { c ->
                                put(JSONObject().apply {
                                    put("id", c.id)
                                    put("type", "function")
                                    put("function", JSONObject().put("name", c.name).put("arguments", c.argumentsJson().toString()))
                                })
                            }
                        })
                    }
                    messages.put(o)
                }
                Role.TOOL -> messages.put(
                    JSONObject().put("role", "tool").put("tool_call_id", m.toolCallId ?: "call_0").put("content", m.content)
                )
            }
        }

        return JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("temperature", request.temperature)
            put("stream", false)
            if (request.tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    request.tools.forEach { t ->
                        put(JSONObject().apply {
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", t.parametersSchema())
                            })
                        })
                    }
                })
                put("tool_choice", "auto")
            }
        }
    }

    /** Parses a chat-completions response; malformed/empty shapes become classified exceptions. */
    fun parse(json: JSONObject): AiResponse {
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
            ?: throw AiProviderException(
                "Provider returned no choices: ${Redactor.redact(json.toString().take(120), config.secrets)}",
                kind = ProviderErrorKind.MALFORMED,
            )
        val message = choice.optJSONObject("message")
            ?: throw AiProviderException("Provider response has no message.", kind = ProviderErrorKind.MALFORMED)
        val text = contentText(message.opt("content"))
        val calls = mutableListOf<ToolCall>()
        message.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val fn = c.optJSONObject("function") ?: continue
                val name = fn.optString("name").takeIf { it.isNotBlank() } ?: continue
                val argsRaw = fn.optString("arguments", "{}")
                val args = runCatching { JSONObject(argsRaw).toMap() }.getOrElse { emptyMap() }
                calls += ToolCall(c.optString("id").ifEmpty { "call_${UUID.randomUUID()}" }, name, args)
            }
        }
        if (text == null && calls.isEmpty())
            throw AiProviderException("Provider returned an empty message.", kind = ProviderErrorKind.EMPTY)
        return AiResponse(text, calls, id, json.optString("model").ifBlank { config.model })
    }

    /** `content` may be a string, null, or (multimodal servers) an array of `{type:"text", text}` parts. */
    private fun contentText(raw: Any?): String? = when (raw) {
        null, JSONObject.NULL -> null
        is String -> raw.takeIf { it.isNotBlank() && it != "null" }
        is JSONArray -> buildString {
            for (i in 0 until raw.length()) raw.optJSONObject(i)?.optString("text")?.let { append(it) }
        }.takeIf { it.isNotBlank() }
        else -> raw.toString().takeIf { it.isNotBlank() }
    }
}
