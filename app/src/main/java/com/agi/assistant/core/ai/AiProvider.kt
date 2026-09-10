package com.agi.assistant.core.ai

import com.agi.assistant.core.tools.ToolSpec

/** Everything a provider needs for one model turn. */
data class AiRequest(
    val systemPrompt: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec>,
    val temperature: Double = 0.2,
)

/** The model's answer: free text, tool calls, or both. */
data class AiResponse(
    val text: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    val providerId: String,
    val model: String? = null,
) {
    val hasToolCalls get() = toolCalls.isNotEmpty()
}

class AiProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Provider-independent contract. Implementations exist for the offline
 * rule-based planner, any OpenAI-compatible endpoint (OpenAI, Groq,
 * OpenRouter, Together, Ollama, LM Studio...) and Google Gemini.
 *
 * To add a provider: implement this interface and register it in
 * [AiProviderFactory]. Nothing else in the app has to change.
 */
interface AiProvider {
    val id: String
    val displayName: String
    /** True when the provider talks to a network service. */
    val isRemote: Boolean

    /** Performs one model turn. Must be called off the main thread. */
    suspend fun complete(request: AiRequest): AiResponse
}
