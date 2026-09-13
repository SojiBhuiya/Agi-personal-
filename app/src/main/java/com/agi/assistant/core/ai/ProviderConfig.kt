package com.agi.assistant.core.ai

/** Which backend family the user selected in Settings. */
enum class ProviderType(val label: String) {
    LOCAL("Offline rule-based (no API key)"),
    OPENAI_COMPATIBLE("OpenAI-compatible API (OpenAI, Groq, OpenRouter, Ollama...)"),
    GEMINI("Google Gemini API");
}

/** Preset endpoints that can be selected from the Settings screen. */
data class ProviderPreset(
    val name: String,
    val type: ProviderType,
    val baseUrl: String,
    val model: String,
    val note: String,
)

/**
 * Gemini model-ID rules. The REST path is `models/{id}:generateContent`, so the id must be a
 * concrete model such as `gemini-2.5-flash-lite`; a bare family name like "gemini" yields HTTP 404.
 */
object GeminiModels {
    const val DEFAULT = "gemini-2.5-flash-lite"

    /** Accepts `models/gemini-…` or `gemini-…` and returns the bare id; other input is returned trimmed. */
    fun normalize(model: String): String = model.trim().removePrefix("models/").trim()

    /** Null when usable, otherwise a user-facing reason (never mentions secrets). */
    fun validationError(model: String): String? {
        val m = normalize(model)
        if (m.isBlank()) return "Model name is required (e.g. $DEFAULT)."
        if (m.equals("gemini", ignoreCase = true))
            return "\"$m\" is not a Gemini model ID. Use a concrete model such as $DEFAULT."
        if (!Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$").matches(m)) return "Model ID contains invalid characters."
        if (!m.contains('-')) return "\"$m\" is not a Gemini model ID. Use a concrete model such as $DEFAULT."
        return null
    }
}

object ProviderPresets {
    val all = listOf(
        ProviderPreset("Offline (built-in)", ProviderType.LOCAL, "", "", "Deterministic command parser. Works without internet."),
        ProviderPreset("Groq (free tier)", ProviderType.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "Fast free tier. Create a key at console.groq.com"),
        ProviderPreset("OpenRouter", ProviderType.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free", "Many free models. Key from openrouter.ai"),
        ProviderPreset("Google Gemini", ProviderType.GEMINI, "https://generativelanguage.googleapis.com", GeminiModels.DEFAULT, "Free tier at aistudio.google.com"),
        ProviderPreset("OpenAI", ProviderType.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-4o-mini", "Paid. Key from platform.openai.com"),
        ProviderPreset("Ollama (local network)", ProviderType.OPENAI_COMPATIBLE, "http://192.168.1.10:11434/v1", "llama3.2", "Run models on your PC; no API key needed."),
    )
}

/** Resolved runtime configuration for the active provider. */
data class ProviderConfig(
    val type: ProviderType,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val timeoutMs: Int = 60_000,
) {
    /** Returns a human-readable problem, or null when the config can be used. Never mentions the key value. */
    fun validationError(): String? {
        if (type == ProviderType.LOCAL) return null
        val url = baseUrl.trim()
        if (url.isBlank()) return "Base URL is required."
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "https" && scheme != "http") return "Base URL must start with https:// (or http:// for a local server)."
        if (url.length <= scheme.length + 3) return "Base URL is incomplete."
        if (model.isBlank()) return "Model name is required."
        if (type == ProviderType.GEMINI) {
            GeminiModels.validationError(model)?.let { return it }
            if (apiKey.isBlank()) return "API key is required for Gemini."
        }
        return null
    }

    /** Values that must never appear in logs or error messages. */
    val secrets: List<String> get() = if (apiKey.isBlank()) emptyList() else listOf(apiKey)
}

/** Pure URL rules for OpenAI-compatible servers, kept separate so they are unit-testable. */
object OpenAiEndpoint {
    /**
     * `https://host/v1` -> `https://host/v1/chat/completions`. Tolerates a trailing slash and a
     * Base URL that already ends in `/chat/completions`. Never appends `/v1` automatically:
     * the configured Base URL is used exactly as given.
     */
    fun chatCompletions(baseUrl: String): String {
        val b = baseUrl.trim().trimEnd('/')
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }
}
