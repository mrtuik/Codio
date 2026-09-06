package com.tuik.rlaude

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class OpenCodeManager(context: Context, private val files: FileManager) {
    private val runtime = RuntimeManager(context, files)
    private val baseUrl = "http://127.0.0.1:4096"

    suspend fun status(): JSONObject = withContext(Dispatchers.IO) {
        runtime.status().put("health", runtime.health())
    }

    suspend fun sendMessage(sessionId: String?, projectId: String, text: String): JSONObject =
        withContext(Dispatchers.IO) {
            require(text.trim().isNotEmpty()) { "Message cannot be empty" }
            if (!runtime.hasApiKey()) {
                throw IllegalStateException(
                    "No API key configured. Please go to Settings and set your AI Model API key (e.g. Google Gemini, OpenRouter, Anthropic, or OpenAI)."
                )
            }
            val startupError = ensureRunning()
            if (startupError != null) throw IllegalStateException(startupError)
            val directory = files.projectDir(projectId).canonicalPath
            val session = sessionId?.takeIf { it.isNotBlank() } ?: createSession(directory)
            val provider = runtime.currentProvider()
            val modelName = runtime.currentModel()
            val cleanModel = modelName.substringAfter("/")

            val body = JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                if (provider.isNotBlank() && cleanModel.isNotBlank()) {
                    put("model", JSONObject().put("providerID", provider).put("modelID", cleanModel))
                }
            }

            val response = requireSuccess(request(
                "POST",
                "/session/$session/message",
                body,
                mapOf("x-opencode-directory" to directory)
            ))
            response
                .put("sessionId", session)
                .put("reply", extractReply(response.opt("body")))
        }

    suspend fun restart(): JSONObject {
        runtime.stop()
        return runtime.start()
    }

    private fun createSession(directory: String): String {
        val response = requireSuccess(
            request(
                "POST",
                "/session",
                JSONObject().put("title", "Rlaude chat"),
                mapOf("x-opencode-directory" to directory)
            )
        )
        val body = response.optJSONObject("body")
            ?: throw IOException("OpenCode returned no session data")
        return body.optString("id").ifBlank { body.optString("sessionID") }.ifBlank {
            throw IOException("OpenCode returned a session without an id")
        }
    }

    private fun requireSuccess(response: JSONObject): JSONObject {
        if (response.optBoolean("ok")) return response
        val body = response.opt("body")
        val status = response.optInt("statusCode", 0)
        var detail = extractErrorDetail(body)
        if (detail.isBlank()) {
            detail = "HTTP $status"
            val lastLine = runtime.lastOutput().trim()
            if (lastLine.isNotBlank()) {
                detail += " — Engine: $lastLine"
            }
        }
        throw IOException("OpenCode request failed: $detail")
    }

    private fun extractErrorDetail(body: Any?): String {
        if (body == null) return ""
        if (body is String) return body.trim()
        if (body is JSONObject) {
            for (key in listOf("message", "error", "detail", "details", "reason", "_tag")) {
                val value = body.opt(key)
                if (value is String && value.isNotBlank()) return value
                if (value is JSONObject) {
                    val nested = extractErrorDetail(value)
                    if (nested.isNotBlank()) return nested
                }
            }
            val data = body.opt("data")
            if (data != null) {
                val nested = extractErrorDetail(data)
                if (nested.isNotBlank()) return nested
            }
            return body.toString()
        }
        return body.toString()
    }

    private fun extractReply(value: Any?): String {
        when (value) {
            is JSONObject -> {
                for (key in listOf("text", "content", "message")) {
                    val direct = value.opt(key) as? String
                    if (!direct.isNullOrBlank()) return direct
                }
                val data = value.optJSONObject("data")
                if (data != null) {
                    val nested = extractReply(data)
                    if (nested.isNotBlank()) return nested
                }
                val parts = value.optJSONArray("parts")
                if (parts != null) return extractReply(parts)
            }
            is JSONArray -> {
                val result = buildList {
                    for (index in 0 until value.length()) {
                        val part = value.opt(index)
                        if (part is JSONObject && part.optString("type") == "text") {
                            val text = part.opt("text") as? String
                            if (!text.isNullOrBlank()) add(text)
                        }
                    }
                }
                return result.joinToString("\n\n")
            }
            is String -> return value
        }
        return ""
    }

    private suspend fun ensureRunning(): String? {
        if (runtime.health().optBoolean("healthy")) return null
        val started = runtime.start()
        repeat(10) {
            delay(500)
            if (runtime.health().optBoolean("healthy")) return null
        }
        repeat(45) {
            delay(1000)
            if (runtime.health().optBoolean("healthy")) return null
        }
        val output = runtime.lastOutput().ifBlank { "(no output from the opencode process yet)" }
        val reason = started.optString("reason").ifBlank { runtime.lastError() }
        return buildString {
            append("The on-device coding engine didn't become healthy.")
            if (reason.isNotBlank()) append(" Reason: ").append(reason).append(".")
            append(" Last engine output: ").append(output)
        }
    }

    private fun request(method: String, path: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): JSONObject {
        var lastError: Exception? = null
        repeat(3) {
            val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 4000
                readTimeout = 120_000
                doInput = true
                setRequestProperty("Accept", "application/json")
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