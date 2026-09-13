package com.agi.assistant.core.ai

import com.agi.assistant.core.tools.ToolSpec

/** Outcome of Settings → "Test connection". [message] is safe to display (no secrets). */
data class ConnectionTestResult(val ok: Boolean, val message: String, val kind: ProviderErrorKind? = null)

/**
 * Performs a real round-trip with the configured provider (same [AiProviderFactory] path the
 * agent loop uses) and converts the outcome into a short, secret-free status line.
 */
object ConnectionTester {
    suspend fun test(config: ProviderConfig, factory: (ProviderConfig) -> AiProvider = { AiProviderFactory.create(it) }): ConnectionTestResult {
        if (config.type == ProviderType.LOCAL) return ConnectionTestResult(true, "Offline planner needs no connection.")
        config.validationError()?.let { return ConnectionTestResult(false, "✗ $it", ProviderErrorKind.CONFIG) }
        val provider = try { factory(config) } catch (e: Exception) {
            return ConnectionTestResult(false, "✗ " + ProviderErrors.describe(e, config.secrets), ProviderErrorKind.CONFIG)
        }
        return try {
            val r = provider.complete(AiRequest("Reply with the single word OK.", listOf(ChatMessage(Role.USER, "ping")), emptyList<ToolSpec>(), temperature = 0.0))
            val preview = Redactor.redact((r.text ?: "(tool call)").replace('\n', ' ').take(80), config.secrets)
            ConnectionTestResult(true, "✓ ${provider.displayName} responded (model: ${r.model ?: config.model}): $preview")
        } catch (e: Exception) {
            val wrapped = ProviderErrors.wrap(e, config.secrets)
            ConnectionTestResult(false, "✗ ${wrapped.kind.userMessage} ${Redactor.redact(wrapped.message, config.secrets)}".trim(), wrapped.kind)
        }
    }
}
