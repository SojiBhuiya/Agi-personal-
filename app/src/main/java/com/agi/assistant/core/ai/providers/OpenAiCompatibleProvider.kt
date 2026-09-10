package com.agi.assistant.core.ai.providers

import com.agi.assistant.core.ai.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Talks to any endpoint implementing the OpenAI Chat Completions API with
 * function calling: OpenAI, Groq, OpenRouter, Together, Mistral, DeepSeek,
 * Ollama, LM Studio, vLLM, llama.cpp server, ...
 */
class OpenAiCompatibleProvider(private val config: ProviderConfig) : AiProvider {
    override val id = "openai_compatible"
    override val displayName = "OpenAI-compatible (${config.model})"
    override val isRemote = true

    override suspend fun complete(request: AiRequest): AiResponse {
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

        val body = JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("temperature", request.temperature)
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

        val headers = mutableMapOf<String, String>()
        if (config.apiKey.isNotBlank()) headers["Authorization"] = "Bearer ${config.apiKey}"
        // OpenRouter attribution headers are harmless for other providers.
        headers["HTTP-Referer"] = "https://github.com/SojiBhuiya/Agi-personal-"
        headers["X-Title"] = "AGI Personal Assistant"

        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val json = HttpJson.post(url, body, headers, config.timeoutMs)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
            ?: throw AiProviderException("Provider returned no choices: ${json.toString().take(200)}")
        val message = choice.getJSONObject("message")
        val text = message.optString("content", "").takeIf { it.isNotBlank() && it != "null" }
        val calls = mutableListOf<ToolCall>()
        message.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val fn = c.getJSONObject("function")
                val argsRaw = fn.optString("arguments", "{}")
                val args = runCatching { JSONObject(argsRaw).toMap() }.getOrElse { emptyMap() }
                calls += ToolCall(c.optString("id").ifEmpty { "call_${UUID.randomUUID()}" }, fn.getString("name"), args)
            }
        }
        return AiResponse(text, calls, id, json.optString("model", config.model))
    }
}
