package com.agi.assistant.core.ai

import org.json.JSONObject
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.MalformedURLException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why a remote provider call failed; drives the user-facing message and the fallback decision. */
enum class ProviderErrorKind {
    CONFIG, AUTH, NOT_FOUND, BAD_REQUEST, RATE_LIMIT, SERVER, TIMEOUT, NETWORK, MALFORMED, EMPTY, UNKNOWN;

    /** Short, non-technical explanation shown in Settings and chat. Contains no secrets. */
    val userMessage: String
        get() = when (this) {
            CONFIG -> "Provider configuration is incomplete or invalid."
            AUTH -> "The API key was rejected (401/403). Check the key and the provider."
            NOT_FOUND -> "Endpoint or model not found (404). Check the Base URL and model name."
            BAD_REQUEST -> "The provider rejected the request (400). Check the model name."
            RATE_LIMIT -> "Rate limit or quota exceeded (429). Try again later."
            SERVER -> "The provider is having problems (server error). Try again later."
            TIMEOUT -> "The provider did not answer in time."
            NETWORK -> "No internet connection or the server could not be reached."
            MALFORMED -> "The provider sent an unreadable response."
            EMPTY -> "The provider sent an empty response."
            UNKNOWN -> "The provider request failed."
        }
}

/** Removes secrets (API keys) from any text before it reaches logs or the UI. */
object Redactor {
    private val BEARER = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{6,}")
    private val QUERY_KEY = Regex("(?i)([?&](?:key|api_key|apikey|token)=)[^&\\s\"']+")
    private val LOOKS_LIKE_KEY = Regex("\\b(sk|gsk|sk-or|AIza)[-_A-Za-z0-9]{12,}")

    fun redact(text: String?, secrets: List<String> = emptyList()): String {
        var t = text.orEmpty()
        for (s in secrets) if (s.length >= 4) t = t.replace(s, "[REDACTED]")
        t = t.replace(BEARER, "$1[REDACTED]").replace(QUERY_KEY, "$1[REDACTED]").replace(LOOKS_LIKE_KEY, "[REDACTED]")
        return t
    }
}

object ProviderErrors {
    /** Maps an HTTP status + body to a classified exception; the body detail is truncated and redacted. */
    fun fromHttpStatus(code: Int, body: String, secrets: List<String>): AiProviderException {
        val kind = when (code) {
            400, 422 -> ProviderErrorKind.BAD_REQUEST
            401, 403 -> ProviderErrorKind.AUTH
            404 -> ProviderErrorKind.NOT_FOUND
            408 -> ProviderErrorKind.TIMEOUT
            429 -> ProviderErrorKind.RATE_LIMIT
            in 500..599 -> ProviderErrorKind.SERVER
            else -> ProviderErrorKind.UNKNOWN
        }
        val detail = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("error").takeIf { it.isNotBlank() }
                ?: o.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull() ?: body.trim().take(160).takeIf { it.isNotBlank() }
        val msg = "HTTP $code from provider" + (detail?.let { ": " + Redactor.redact(it, secrets).take(200) } ?: "")
        return AiProviderException(msg, kind = kind, httpStatus = code)
    }

    /** Classifies transport-level failures (DNS, connect, TLS, timeout...). */
    fun fromTransport(e: Exception, secrets: List<String>): AiProviderException {
        val kind = when (e) {
            is SocketTimeoutException, is InterruptedIOException -> ProviderErrorKind.TIMEOUT
            is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketException, is SSLException -> ProviderErrorKind.NETWORK
            is MalformedURLException, is IllegalArgumentException -> ProviderErrorKind.CONFIG
            is java.io.FileNotFoundException -> ProviderErrorKind.NOT_FOUND
            else -> ProviderErrorKind.NETWORK
        }
        val detail = Redactor.redact(e.message ?: e.javaClass.simpleName, secrets).take(160)
        return AiProviderException("${kind.userMessage} ($detail)", e, kind)
    }

    /** Wraps anything (already classified or not) into a safe, classified exception. */
    fun wrap(t: Throwable, secrets: List<String>): AiProviderException = when (t) {
        is AiProviderException -> t
        is Exception -> fromTransport(t, secrets)
        else -> AiProviderException(Redactor.redact(t.message ?: t.javaClass.simpleName, secrets), t, ProviderErrorKind.UNKNOWN)
    }

    /** One-line, secret-free text for the UI. */
    fun describe(t: Throwable, secrets: List<String> = emptyList()): String {
        val e = wrap(t, secrets)
        val msg = Redactor.redact(e.message, secrets)
        return if (msg.isBlank()) e.kind.userMessage else msg
    }
}
