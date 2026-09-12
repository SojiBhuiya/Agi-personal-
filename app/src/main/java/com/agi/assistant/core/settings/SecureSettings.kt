package com.agi.assistant.core.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.agi.assistant.core.ai.ProviderConfig
import com.agi.assistant.core.ai.ProviderType
import com.agi.assistant.core.update.UpdatePreferences
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists user settings. Secrets (API keys) are encrypted with an AES-GCM
 * key that lives in the Android Keystore, so they never exist in plain text
 * on disk and are never compiled into the APK.
 */
class SecureSettings(context: Context) : UpdatePreferences {
    private val prefs: SharedPreferences = context.getSharedPreferences("assistant_settings", Context.MODE_PRIVATE)

    // ---- Provider -----------------------------------------------------------
    var providerType: ProviderType
        get() = runCatching { ProviderType.valueOf(prefs.getString(KEY_PROVIDER, ProviderType.LOCAL.name)!!) }.getOrDefault(ProviderType.LOCAL)
        set(v) = prefs.edit().putString(KEY_PROVIDER, v.name).apply()

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_BASE_URL, v.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_MODEL, v.trim()).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, null)?.let { decrypt(it) } ?: ""
        set(v) {
            if (v.isBlank()) prefs.edit().remove(KEY_API_KEY).apply()
            else prefs.edit().putString(KEY_API_KEY, encrypt(v.trim())).apply()
        }

    var fallbackToLocal: Boolean
        get() = prefs.getBoolean(KEY_FALLBACK, true)
        set(v) = prefs.edit().putBoolean(KEY_FALLBACK, v).apply()

    var speakReplies: Boolean
        get() = prefs.getBoolean(KEY_TTS, true)
        set(v) = prefs.edit().putBoolean(KEY_TTS, v).apply()

    var confirmSensitiveActions: Boolean
        get() = prefs.getBoolean(KEY_CONFIRM, true)
        set(v) = prefs.edit().putBoolean(KEY_CONFIRM, v).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(v) = prefs.edit().putBoolean(KEY_ONBOARDED, v).apply()

    // ---- Update prompts ---------------------------------------------------------
    override var postponedTag: String?
        get() = prefs.getString(KEY_UPDATE_POSTPONED_TAG, null)
        set(v) = prefs.edit().putString(KEY_UPDATE_POSTPONED_TAG, v).apply()

    override var postponedAt: Long
        get() = prefs.getLong(KEY_UPDATE_POSTPONED_AT, 0L)
        set(v) = prefs.edit().putLong(KEY_UPDATE_POSTPONED_AT, v).apply()

    /** "versionName|versionCode" of the release whose installer was launched; cleared once it is installed. */
    var stagedUpdate: String?
        get() = prefs.getString(KEY_UPDATE_STAGED, null)
        set(v) = prefs.edit().putString(KEY_UPDATE_STAGED, v).apply()

    fun providerConfig() = ProviderConfig(providerType, baseUrl, model, apiKey)

    // ---- Encryption ---------------------------------------------------------

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val (ivB64, dataB64) = stored.split(":", limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(dataB64, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        private const val KEY_ALIAS = "agi_assistant_settings_key"
        private const val KEY_PROVIDER = "provider_type"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY = "api_key_enc"
        private const val KEY_FALLBACK = "fallback_local"
        private const val KEY_TTS = "speak_replies"
        private const val KEY_CONFIRM = "confirm_sensitive"
        private const val KEY_ONBOARDED = "onboarding_done"
        private const val KEY_UPDATE_POSTPONED_TAG = "update_postponed_tag"
        private const val KEY_UPDATE_STAGED = "update_staged"
        private const val KEY_UPDATE_POSTPONED_AT = "update_postponed_at"
    }
}
