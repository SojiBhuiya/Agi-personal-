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

object ProviderPresets {
    val all = listOf(
        ProviderPreset("Offline (built-in)", ProviderType.LOCAL, "", "", "Deterministic command parser. Works without internet."),
        ProviderPreset("Groq (free tier)", ProviderType.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "Fast free tier. Create a key at console.groq.com"),
        ProviderPreset("OpenRouter", ProviderType.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free", "Many free models. Key from openrouter.ai"),
        ProviderPreset("Google Gemini", ProviderType.GEMINI, "https://generativelanguage.googleapis.com", "gemini-2.0-flash", "Free tier at aistudio.google.com"),
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
)
