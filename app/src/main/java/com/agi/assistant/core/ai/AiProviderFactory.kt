package com.agi.assistant.core.ai

import com.agi.assistant.core.ai.providers.GeminiProvider
import com.agi.assistant.core.ai.providers.LocalRuleProvider
import com.agi.assistant.core.ai.providers.OpenAiCompatibleProvider

/** Single place that knows about concrete provider classes. */
object AiProviderFactory {
    fun create(config: ProviderConfig): AiProvider = when (config.type) {
        ProviderType.LOCAL -> LocalRuleProvider()
        ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(config)
        ProviderType.GEMINI -> GeminiProvider(config)
    }

    val fallback: AiProvider = LocalRuleProvider()
}
