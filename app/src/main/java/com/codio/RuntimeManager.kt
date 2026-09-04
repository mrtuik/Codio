package com.codio

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class RuntimeManager(private val context: Context, private val files: FileManager) {
    private var process: Process? = null
    private val runtimeDir = File(files.codioRoot, "Runtime")
    private val binary = File(runtimeDir, "opencode")
    private val port = 4096

    fun architecture(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    fun status(): JSONObject {
        val installed = binary.exists() && binary.canExecute()
        return JSONObject()
            .put("installed", installed)
            .put("running", process?.isAlive == true)
            .put("architecture", architecture())
            .put("version", if (installed) "managed-runtime" else JSONObject.NULL)
            .put("server", "http://127.0.0.1:$port")
    }

    suspend fun health(): JSONObject = withContext(Dispatchers.IO) {
        val connection = runCatching {
            (URL("http://127.0.0.1:$port/global/health").openConnection() as HttpURLConnection).apply {
                connectTimeout = 800
                readTimeout = 800
                requestMethod = "GET"
            }
        }.getOrNull() ?: return@withContext JSONObject().put("healthy", false).put("reason", "unreachable")
        try {
            val code = connection.responseCode
            JSONObject().put("healthy", code in 200..299).put("statusCode", code)
        } catch (error: Exception) {
            JSONObject().put("healthy", false).put("reason", error.message ?: "unreachable")
        } finally {
            connection.disconnect()
        }
    }

    suspend fun start(): JSONObject = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) return@withContext status().put("started", false)
        if (!binary.exists() || !binary.canExecute()) {
            return@withContext status().put("started", false).put("reason", "Runtime is not installed")
        }
        process = ProcessBuilder(binary.absolutePath, "serve", "--hostname", "127.0.0.1", "--port", port.toString())
            .directory(File(files.codioRoot, "OpenCode"))
            .redirectErrorStream(true)
            .start()
        status().put("started", true)
    }

    fun stop() {
        process?.destroy()
        process = null
    }

    suspend fun installFromFile(source: File, expectedSha256: String): JSONObject = withContext(Dispatchers.IO) {
        val actual = sha256(source)
        require(actual.equals(expectedSha256, ignoreCase = true)) { "Runtime checksum did not match" }
        runtimeDir.mkdirs()
        source.copyTo(binary, overwrite = true)
        check(binary.setExecutable(true)) { "Could not mark runtime executable" }
        status().put("installed", true)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read >= 0) {
                if (read > 0) digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}