package com.agi.assistant.core.ai

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Raw HTTP result; [body] is decoded as UTF-8 so Bangla/English text survives unchanged. */
data class HttpResponse(val code: Int, val body: String)

/**
 * The only seam between providers and the network. Production uses [UrlConnectionTransport];
 * tests plug in a fake so request construction, parsing and every failure mode are deterministic.
 */
interface HttpTransport {
    fun post(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse
}

/**
 * HttpURLConnection-based transport (no third-party HTTP stack). The response stream is read to
 * the end and closed but the connection is NOT `disconnect()`ed on success: on Android that keeps
 * the TLS socket in the keep-alive pool, so the second request of a tool turn (and the next user
 * turn) skips the TCP+TLS handshake.
 */
object UrlConnectionTransport : HttpTransport {
    override fun post(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): HttpResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use(BufferedReader::readText) }.orEmpty()
            return HttpResponse(code, text)
        } catch (e: Exception) {
            conn.disconnect() // broken exchange: do not return this socket to the pool
            throw e
        }
    }
}

/**
 * JSON-over-HTTP helper shared by the remote providers. Every failure (transport, HTTP status,
 * malformed JSON) is turned into an [AiProviderException] with a classified [ProviderErrorKind]
 * and a message that never contains the API key.
 */
object HttpJson {
    fun post(
        url: String,
        body: JSONObject,
        headers: Map<String, String>,
        timeoutMs: Int,
        secrets: List<String> = emptyList(),
        transport: HttpTransport = UrlConnectionTransport,
    ): JSONObject {
        val resp = try {
            transport.post(url, body.toString(), headers, timeoutMs)
        } catch (e: AiProviderException) {
            throw e
        } catch (e: Exception) {
            throw ProviderErrors.fromTransport(e, secrets)
        }
        if (resp.code !in 200..299) throw ProviderErrors.fromHttpStatus(resp.code, resp.body, secrets)
        if (resp.body.isBlank()) throw AiProviderException("Provider returned an empty response.", kind = ProviderErrorKind.EMPTY)
        return try {
            JSONObject(resp.body)
        } catch (e: Exception) {
            throw AiProviderException(
                "Provider returned invalid JSON: ${Redactor.redact(resp.body.take(120), secrets)}",
                e, ProviderErrorKind.MALFORMED,
            )
        }
    }
}
