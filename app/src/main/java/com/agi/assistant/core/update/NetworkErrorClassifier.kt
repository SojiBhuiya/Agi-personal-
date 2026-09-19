package com.agi.assistant.core.update

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Maps a failure raised while talking to the GitHub Releases API to an [UpdateError] plus a
 * short, credential-free explanation. Pure JVM, so it is unit-tested directly.
 *
 * Only *classifies*; it never changes how TLS is validated. A TLS failure stays a failure.
 */
object NetworkErrorClassifier {

    data class Classified(val reason: UpdateError, val message: String, val retryable: Boolean)

    const val HOST = "api.github.com"

    fun classify(t: Throwable, host: String = HOST): Classified {
        // Walk the cause chain: Android's HttpsURLConnection wraps the root cause fairly often
        // (e.g. SSLHandshakeException -> CertificateException, IOException -> UnknownHostException).
        val chain = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(8).toList()
        fun <T : Throwable> has(cls: Class<T>) = chain.firstOrNull { cls.isInstance(it) }

        has(UnknownHostException::class.java)?.let {
            return Classified(UpdateError.DNS, "Could not resolve $host (${brief(it)}). The phone's DNS returned no address for GitHub. Check that Wi‑Fi/mobile data is really online (no captive portal) and try again; some IPv6-only networks without DNS64 cannot reach GitHub, which is IPv4-only.", retryable = true)
        }
        // TLS before generic socket errors: SSLException is an IOException.
        (has(SSLPeerUnverifiedException::class.java) ?: has(SSLHandshakeException::class.java) ?: has(CertificateException::class.java) ?: has(SSLException::class.java) ?: has(GeneralSecurityException::class.java))?.let {
            return Classified(UpdateError.TLS, "Secure connection to $host failed (${brief(it)}). The certificate could not be verified – usually a wrong date/time on the phone, a filtering proxy or a captive portal. The update check will not bypass certificate validation.", retryable = false)
        }
        (has(SocketTimeoutException::class.java) ?: has(InterruptedIOException::class.java))?.let {
            return Classified(UpdateError.TIMEOUT, "GitHub did not answer in time (${brief(it)}). The connection is very slow or blocked; try again on a better network.", retryable = true)
        }
        (has(ConnectException::class.java) ?: has(NoRouteToHostException::class.java) ?: has(PortUnreachableException::class.java))?.let {
            return Classified(UpdateError.NETWORK, "Could not connect to $host (${brief(it)}). No route to GitHub from this network (IPv4/IPv6 routing or firewall).", retryable = true)
        }
        (has(SocketException::class.java) ?: has(EOFException::class.java))?.let {
            return Classified(UpdateError.NETWORK, "Connection to $host was interrupted (${brief(it)}).", retryable = true)
        }
        has(IOException::class.java)?.let {
            return Classified(UpdateError.NETWORK, "Could not reach GitHub (${brief(it)}).", retryable = true)
        }
        return Classified(UpdateError.UNKNOWN, "Update check failed: ${brief(t)}", retryable = false)
    }

    /** `SimpleName: message` with the message trimmed to one line; never includes headers or bodies. */
    fun brief(t: Throwable): String {
        val m = t.message?.lineSequence()?.firstOrNull()?.trim().orEmpty().take(160)
        return if (m.isEmpty()) t.javaClass.simpleName else "${t.javaClass.simpleName}: $m"
    }

    /** Classifies a non-2xx GitHub answer. [body] may carry GitHub's JSON `message`. */
    fun classifyHttp(code: Int, body: String): Classified {
        val apiMessage = runCatching { org.json.JSONObject(body).optString("message") }.getOrDefault("").take(120)
        val msg = when (code) {
            404 -> "No releases have been published yet."
            403, 429 -> "GitHub rate limit reached. Try again later." + if (apiMessage.isNotEmpty()) " ($apiMessage)" else ""
            401 -> "GitHub rejected the request (HTTP 401)."
            in 500..599 -> "GitHub is having trouble (HTTP $code). Please try again later."
            else -> "GitHub returned HTTP $code." + if (apiMessage.isNotEmpty()) " ($apiMessage)" else ""
        }
        return Classified(UpdateError.HTTP, msg, retryable = code in 500..599 || code == 408)
    }
}
