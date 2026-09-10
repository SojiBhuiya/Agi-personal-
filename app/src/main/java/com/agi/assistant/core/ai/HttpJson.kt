package com.agi.assistant.core.ai

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** Minimal JSON-over-HTTP helper built on HttpURLConnection (no third party HTTP stack). */
object HttpJson {
    fun post(url: String, body: JSONObject, headers: Map<String, String>, timeoutMs: Int): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull()
                throw AiProviderException("HTTP $code from provider: ${detail ?: text.take(300)}")
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
