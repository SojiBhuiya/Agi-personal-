package com.agi.assistant.core.ai

/**
 * Beginner-friendly API setup. The normal UI collects only a provider choice, an optional local
 * label ("API name") and the API key; everything technical (type, Base URL, model, endpoint,
 * auth header) is resolved here from [SimpleProvider] defaults. Advanced settings keep full
 * control over the same [ProviderConfig]. Pure Kotlin, JVM-tested.
 */
enum class SimpleProvider(val label: String, val type: ProviderType, val baseUrl: String, val model: String, val keyHint: String) {
    GEMINI("Google Gemini", ProviderType.GEMINI, "https://generativelanguage.googleapis.com", GeminiModels.DEFAULT, "Get a free key at aistudio.google.com → Get API key"),
    OFFLINE("Offline (no API key)", ProviderType.LOCAL, "", "", "Built-in command planner. Works without internet.");

    companion object {
        /** Which simple choice a stored config corresponds to, or null when it is a custom/advanced setup. */
        fun forConfig(config: ProviderConfig): SimpleProvider? = when (config.type) {
            ProviderType.LOCAL -> OFFLINE
            ProviderType.GEMINI -> GEMINI // any Gemini setup is shown as "Google Gemini" (custom model kept)
            ProviderType.OPENAI_COMPATIBLE -> null
        }
    }
}

/** What the simple screen holds. [apiName] is a local display label only. */
data class SimpleSetupInput(val provider: SimpleProvider, val apiName: String, val apiKey: String)

/** Abstraction over SecureSettings so the flow is testable without Android. */
interface ProviderSettingsStore {
    var providerType: ProviderType
    var baseUrl: String
    var model: String
    /** Encrypted at rest by the real implementation; the flow only ever passes the plain value through. */
    var apiKey: String
    var apiName: String
}

object SimpleSetup {
    /**
     * Builds the runtime config from the simple input. When the user already has a Gemini setup
     * with a valid custom model (Advanced), [existing] keeps that model instead of the default.
     * The API name is deliberately NOT part of the result: it never reaches a provider.
     */
    fun toConfig(input: SimpleSetupInput, existing: ProviderConfig? = null): ProviderConfig {
        val p = input.provider
        val model = if (p == SimpleProvider.GEMINI && existing?.type == ProviderType.GEMINI &&
            existing.model.isNotBlank() && GeminiModels.validationError(existing.model) == null &&
            existing.baseUrl.trimEnd('/') == p.baseUrl
        ) GeminiModels.normalize(existing.model) else p.model
        val baseUrl = if (p == SimpleProvider.GEMINI && existing?.type == ProviderType.GEMINI && existing.baseUrl.isNotBlank()) existing.baseUrl else p.baseUrl
        return ProviderConfig(p.type, baseUrl, model, input.apiKey.trim())
    }

    /**
     * One-time safe migration for stored settings (runs at every startup; idempotent):
     * a Gemini config whose model is the invalid bare "gemini" (or blank / `models/` prefixed)
     * becomes [GeminiModels.DEFAULT] / normalised. Valid custom Gemini models and every
     * non-Gemini configuration are left untouched. Returns true when something changed.
     */
    fun migrate(store: ProviderSettingsStore): Boolean {
        if (store.providerType != ProviderType.GEMINI) return false
        var changed = false
        val m = store.model
        val normalized = GeminiModels.normalize(m)
        val fixed = when {
            normalized.isBlank() || normalized.equals("gemini", ignoreCase = true) -> GeminiModels.DEFAULT
            GeminiModels.validationError(normalized) == null -> normalized
            else -> normalized // unknown but well-formed: keep, the validator will explain at test time
        }
        if (fixed != m) { store.model = fixed; changed = true }
        if (store.baseUrl.isBlank()) { store.baseUrl = SimpleProvider.GEMINI.baseUrl; changed = true }
        return changed
    }

    /** Validation before any network call. Returns a first-time-user friendly message or null. */
    fun validate(input: SimpleSetupInput): String? {
        if (input.provider == SimpleProvider.OFFLINE) return null
        if (input.apiKey.isBlank()) return "Please paste your ${input.provider.label} API key first."
        if (input.apiKey.trim().length < 8) return "That API key looks too short. Copy the full key from your provider."
        if (input.apiKey.trim().any { it.isWhitespace() }) return "The API key must not contain spaces or line breaks."
        return null
    }

    /**
     * First-time-user wording for a failed real request. Shows the model for model/endpoint errors,
     * never the key. [technical] is the redacted provider message (may be appended briefly).
     */
    fun friendlyError(kind: ProviderErrorKind, config: ProviderConfig, technical: String?): String {
        val model = GeminiModels.normalize(config.model)
        val base = when (kind) {
            ProviderErrorKind.AUTH -> "The API key was not accepted. Check that you copied the whole key and that it is active."
            ProviderErrorKind.NOT_FOUND -> "The model \"$model\" is not available for this key or endpoint. Open Advanced settings to choose another model."
            ProviderErrorKind.BAD_REQUEST -> "The provider rejected the request for model \"$model\". Try another model in Advanced settings."
            ProviderErrorKind.RATE_LIMIT -> "The provider is rate-limiting this key right now (429). Wait a minute and try again."
            ProviderErrorKind.SERVER -> "The provider's servers are having trouble. Please try again in a few minutes."
            ProviderErrorKind.TIMEOUT -> "The provider did not answer in time. Check your connection and try again."
            ProviderErrorKind.NETWORK -> "No internet connection, or the provider could not be reached."
            ProviderErrorKind.CONFIG -> "The configuration is incomplete. ${technical.orEmpty()}".trim()
            ProviderErrorKind.MALFORMED, ProviderErrorKind.EMPTY -> "The provider sent an unexpected reply. Try again; if it persists, try another model."
            ProviderErrorKind.UNKNOWN -> "The connection test failed. ${technical.orEmpty()}".trim()
        }
        return Redactor.redact(base, config.secrets)
    }

    /** Result of Test & Save. [message] is display-safe. */
    data class Outcome(val saved: Boolean, val message: String, val kind: ProviderErrorKind? = null)

    /**
     * Test & Save: validate → build config → REAL request through [ConnectionTester] (same provider
     * classes the agent uses) → persist only on success. Offline provider is saved without a request.
     */
    suspend fun testAndSave(
        input: SimpleSetupInput,
        store: ProviderSettingsStore,
        factory: (ProviderConfig) -> AiProvider = { AiProviderFactory.create(it) },
    ): Outcome {
        validate(input)?.let { return Outcome(false, "✗ $it", ProviderErrorKind.CONFIG) }
        val existing = ProviderConfig(store.providerType, store.baseUrl, store.model, store.apiKey)
        val config = toConfig(input, existing)
        if (config.type == ProviderType.LOCAL) {
            persist(store, config, input.apiName)
            return Outcome(true, "✓ Offline planner selected. No API key needed.")
        }
        val result = ConnectionTester.test(config, factory)
        if (!result.ok) {
            val technical = result.message.removePrefix("✗").trim()
            return Outcome(false, "✗ " + friendlyError(result.kind ?: ProviderErrorKind.UNKNOWN, config, technical), result.kind)
        }
        persist(store, config, input.apiName)
        val label = input.apiName.trim().ifBlank { input.provider.label }
        return Outcome(true, "✓ $label is connected and saved. You can start chatting.")
    }

    private fun persist(store: ProviderSettingsStore, config: ProviderConfig, apiName: String) {
        store.providerType = config.type
        store.baseUrl = config.baseUrl
        store.model = config.model
        store.apiKey = config.apiKey
        store.apiName = apiName.trim()
    }
}
