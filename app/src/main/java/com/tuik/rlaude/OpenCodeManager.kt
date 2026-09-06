package com.tuik.rlaude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Talks to the local OpenCode HTTP server. Takes the app-wide RuntimeManager
 * singleton (see RuntimeManager.getInstance) instead of building its own —
 * a private instance here used to mean Stop/Restart from Settings acted on a
 * `process` handle that never actually launched anything.
 */
class OpenCodeManager(private val runtime: RuntimeManager, private val files: FileManager) {
    private val baseUrl = "http://127.0.0.1:4096"

    suspend fun status(): JSONObject = withContext(Dispatchers.IO) {
        runtime.status().put("health", runtime.health())
    }

    suspend fun sendMessage(sessionId: String?, projectId: String, text: String): JSONObject =
        withContext(Dispatchers.IO) {
            require(text.trim().isNotEmpty()) { "Message cannot be empty" }
            if (!ensureRunning()) {
                val detail = runtime.lastOutput().ifBlank { runtime.lastError() }.ifBlank { null }
                throw IllegalStateException(
                    "The on-device coding engine didn't start." +
                        (detail?.let { " Last output: $it" } ?: "") +
                        " Check Settings › Runtime, or Diagnostics."
                )
            }
            val session = sessionId ?: UUID.randomUUID().toString()
            val body = JSONObject()
                .put("sessionID", session)
                .put("parts", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text)))
            val response = request(
                "POST",
                "/session/$session/message",
                body,
                mapOf("x-rlaude-directory" to files.projectDir(projectId).absolutePath)
            )
            response.put("sessionId", session)
        }

    suspend fun restart(): JSONObject {
        runtime.stop()
        return runtime.start()
    }

    /**
     * The opencode server can take a few seconds to bind its port after the
     * proot process is launched. Chat used to send one HTTP request with a
     * short timeout and give up ("Failed to connect"), even while the server
     * was still starting normally. This starts the runtime if needed and
     * polls health for up to ~25s before the caller gives up — but bails out
     * immediately if the process has already died, instead of waiting out
     * the full timeout for a process that's already gone.
     */
    private suspend fun ensureRunning(): Boolean {
        if (runtime.health().optBoolean("healthy")) return true
        runtime.start()
        repeat(25) {
            delay(1000)
            if (runtime.health().optBoolean("healthy")) return true
            if (!runtime.isProcessAlive()) return false
        }
        return false
    }

    private fun request(method: String, path: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): JSONObject {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 4000
                readTimeout = 120_000
                doInput = true
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            try {
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return JSONObject()
                    .put("ok", code in 200..299)
                    .put("statusCode", code)
                    .put("body", runCatching { JSONObject(text) }.getOrElse { text })
            } catch (error: java.net.ConnectException) {
                lastError = error
                Thread.sleep(1000)
            } finally {
                connection.disconnect()
            }
        }
        throw lastError ?: IllegalStateException("Could not reach the local coding engine")
    }
}
