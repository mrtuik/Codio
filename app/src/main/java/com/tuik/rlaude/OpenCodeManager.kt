package com.tuik.rlaude

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class OpenCodeManager(context: Context, private val files: FileManager) {
    private val runtime = RuntimeManager(context, files)
    private val baseUrl = "http://127.0.0.1:4096"

    suspend fun status(): JSONObject = withContext(Dispatchers.IO) {
        runtime.status().put("health", runtime.health())
    }

    suspend fun sendMessage(sessionId: String?, projectId: String, text: String): JSONObject =
        withContext(Dispatchers.IO) {
            require(text.trim().isNotEmpty()) { "Message cannot be empty" }
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

    private fun request(method: String, path: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap()): JSONObject {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 1500
            readTimeout = 60_000
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
        } finally {
            connection.disconnect()
        }
    }
}