package com.tuik.rlaude

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

    suspend fun sendMessage(
        sessionId: String?,
        projectId: String,
        text: String,
        onChunk: (String) -> Unit = {}
    ): JSONObject =
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
            val headers = mapOf("x-rlaude-directory" to files.projectDir(projectId).absolutePath)
            // The server assigns session ids itself via POST /session — posting
            // straight to /session/{a-uuid-we-made-up}/message hits a session
            // that was never created, which is what produced the generic
            // "UnknownError: Unexpected server error" on every single message.
            val session = sessionId ?: createSession(headers)
            // Stream partial assistant text (if the server emits it) so chat
            // shows the reply growing in as it's generated, instead of a blank
            // "working" state until the whole answer lands at once. This is
            // best-effort: if the event shape ever changes server-side, the
            // stream just yields no chunks and the final POST response below
            // still renders the complete reply as before — nothing regresses.
            //
            // The stream read blocks on socket IO, which coroutine cancellation
            // alone can't interrupt — so we hold the connection here and
            // disconnect() it ourselves once the POST below finishes, which is
            // what actually unblocks the reader thread instead of leaking it.
            val streamConnection = arrayOfNulls<HttpURLConnection>(1)
            val streamJob = launch { streamAssistantText(session, headers, streamConnection, onChunk) }
            try {
                val body = JSONObject()
                    .put("parts", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                val response = request("POST", "/session/$session/message", body, headers)
                response.put("sessionId", session)
            } finally {
                streamConnection[0]?.disconnect()
                streamJob.cancel()
            }
        }

    /**
     * Reads the OpenCode server's SSE event stream (`GET /event`) and reports
     * the assistant's growing reply text for this session via [onChunk] as
     * `message.part.updated`/`message.part.delta` events arrive. The caller
     * disconnects [connectionHolder]'s connection once the owning
     * [sendMessage] call finishes, which is what stops this loop.
     */
    private fun streamAssistantText(
        sessionId: String,
        headers: Map<String, String>,
        connectionHolder: Array<HttpURLConnection?>,
        onChunk: (String) -> Unit
    ) {
        try {
            val connection = (URL(baseUrl + "/event").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 0
                doInput = true
                setRequestProperty("Accept", "text/event-stream")
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            connectionHolder[0] = connection
            val partsById = LinkedHashMap<String, String>()
            connection.inputStream.bufferedReader().forEachLine { line ->
                if (!line.startsWith("data:")) return@forEachLine
                val payload = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: return@forEachLine
                val type = payload.optString("type")
                if (!type.startsWith("message.part")) return@forEachLine
                val part = payload.optJSONObject("properties")?.optJSONObject("part") ?: return@forEachLine
                if (part.optString("sessionID") != sessionId) return@forEachLine
                if (part.optString("type") != "text") return@forEachLine
                val partText = part.optString("text")
                if (partText.isEmpty()) return@forEachLine
                partsById[part.optString("id").ifBlank { "0" }] = partText
                onChunk(partsById.values.joinToString("\n\n"))
            }
        } catch (_: Exception) {
            // Expected once the caller disconnects the connection above, or
            // when this server build doesn't expose /event at all — either
            // way the caller's plain final response still renders normally.
        }
    }

    private fun createSession(headers: Map<String, String>): String {
        val response = request("POST", "/session", JSONObject(), headers)
        val body = response.opt("body") as? JSONObject
        val id = body?.optString("id").orEmpty()
        if (id.isBlank()) throw IllegalStateException("The coding engine did not return a session id")
        return id
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
