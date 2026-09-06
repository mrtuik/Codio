package com.tuik.rlaude

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Owns the long-lived OpenCode process. The only executable launched from
 * Android app-private storage is the pre-installed proot .so in nativeLibraryDir.
 * The rootfs and npm-installed packages remain data files inside that rootfs.
 */
class RuntimeManager(private val context: Context, private val files: FileManager) {
    private val runtimeDir = File(files.rlaudeRoot, "Runtime")
    private val rootfs = File(runtimeDir, "rootfs")
    private val port = 4096
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secureStorage = SecureStorage(context)
    @Volatile private var process: Process? = null

    fun architecture(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    fun prootBinary(): File = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    fun loaderBinary(): File = File(context.applicationInfo.nativeLibraryDir, "libproot_loader.so")

    fun rootfsDirectory(): File = rootfs

    fun bootstrapComplete(): Boolean = prefs.getBoolean(KEY_BOOTSTRAP_COMPLETE, false)

    fun setBootstrapComplete(value: Boolean) {
        prefs.edit().putBoolean(KEY_BOOTSTRAP_COMPLETE, value).apply()
    }

    fun setLastError(message: String?) {
        prefs.edit().putString(KEY_LAST_ERROR, message.orEmpty()).apply()
    }

    fun lastError(): String = prefs.getString(KEY_LAST_ERROR, "").orEmpty()

    fun status(): JSONObject {
        val installed = bootstrapComplete() && rootfs.isDirectory && prootBinary().canExecute()
        val running = process?.isAlive == true || prefs.getBoolean(KEY_RUNTIME_RUNNING, false)
        return JSONObject()
            .put("installed", installed)
            .put("bootstrapComplete", bootstrapComplete())
            .put("running", running)
            .put("architecture", architecture())
            .put("version", if (installed) prefs.getString(KEY_RUNTIME_VERSION, "managed-rootfs") else JSONObject.NULL)
            .put("lastError", lastError().ifBlank { JSONObject.NULL })
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
        if (process?.isAlive == true || prefs.getBoolean(KEY_RUNTIME_RUNNING, false)) {
            return@withContext status().put("started", false)
        }
        if (!bootstrapComplete()) {
            return@withContext status().put("started", false).put("reason", "Runtime bootstrap is incomplete")
        }
        if (!rootfs.isDirectory) {
            return@withContext fail("Runtime rootfs is missing")
        }
        val proot = prootBinary()
        if (!proot.canExecute()) {
            return@withContext fail("Bundled proot is missing for ${architecture()}")
        }
        writeModelFilesIntoRootfs()
        val serverCommand = "opencode serve --hostname 127.0.0.1 --port $port --print"
        process = ProcessBuilder(
            proot.absolutePath,
            "--link2symlink", "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-w", "/root",
            "/bin/sh", "-c", serverCommand
        )
            .directory(File(files.rlaudeRoot, "OpenCode"))
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = "/root"
                environment()["TERM"] = "dumb"
                environment()["PATH"] = ROOTFS_PATH
                environment()["PROOT_LOADER"] = loaderBinary().absolutePath
                environment()["PROOT_TMP_DIR"] = context.cacheDir.absolutePath
            }
            .start()
        prefs.edit().putBoolean(KEY_RUNTIME_RUNNING, true).apply()
        setLastError(null)
        val launched = process
        thread(name = "rlaude-opencode-output", isDaemon = true) {
            runCatching {
                launched?.inputStream?.bufferedReader()?.forEachLine { line ->
                    // Keep only a short, redacted diagnostic tail. Chat uses HTTP.
                    prefs.edit().putString(KEY_LAST_OUTPUT, redact(line).take(500)).apply()
                }
            }
            prefs.edit().putBoolean(KEY_RUNTIME_RUNNING, false).apply()
        }
        status().put("started", true)
    }

    fun stop() {
        process?.destroy()
        process = null
        prefs.edit().putBoolean(KEY_RUNTIME_RUNNING, false).apply()
    }

    fun markVersion(version: String) {
        prefs.edit().putString(KEY_RUNTIME_VERSION, version).apply()
    }

    fun modelConfig(): JSONObject = JSONObject()
        .put("provider", prefs.getString(KEY_MODEL_PROVIDER, "").orEmpty())
        .put("model", prefs.getString(KEY_MODEL_ID, "").orEmpty())
        .put("hasApiKey", !secureStorage.get(KEY_API_KEY).isNullOrBlank())

    /** Mirrors what `opencode auth login` + a hand-edited opencode.json would do. */
    fun setModelConfig(provider: String, model: String, apiKey: String?) {
        val cleanProvider = provider.trim()
        if (cleanProvider.isEmpty()) throw IllegalArgumentException("Choose a provider")
        prefs.edit()
            .putString(KEY_MODEL_PROVIDER, cleanProvider)
            .putString(KEY_MODEL_ID, model.trim())
            .apply()
        if (!apiKey.isNullOrBlank()) secureStorage.put(KEY_API_KEY, apiKey.trim())
        writeModelFilesIntoRootfs()
    }

    private fun writeModelFilesIntoRootfs() {
        val provider = prefs.getString(KEY_MODEL_PROVIDER, "").orEmpty()
        if (provider.isBlank() || !rootfs.isDirectory) return
        val model = prefs.getString(KEY_MODEL_ID, "").orEmpty()
        val apiKey = secureStorage.get(KEY_API_KEY)
        runCatching {
            val configDir = File(rootfs, "root/.config/opencode").apply { mkdirs() }
            val config = JSONObject()
            if (model.isNotBlank()) config.put("model", "$provider/$model")
            File(configDir, "opencode.json").writeText(config.toString(2))
            if (!apiKey.isNullOrBlank()) {
                val authDir = File(rootfs, "root/.local/share/opencode").apply { mkdirs() }
                val auth = JSONObject().put(provider, JSONObject().put("type", "api").put("key", apiKey))
                File(authDir, "auth.json").writeText(auth.toString(2))
            }
        }
    }

    suspend fun installFromFile(source: File, expectedSha256: String): JSONObject = withContext(Dispatchers.IO) {
        val actual = sha256(source)
        require(actual.equals(expectedSha256, ignoreCase = true)) { "Runtime checksum did not match" }
        runtimeDir.mkdirs()
        source.copyTo(File(runtimeDir, "runtime-package"), overwrite = true)
        status().put("installed", false).put("packageVerified", true)
    }

    private fun fail(message: String): JSONObject {
        setLastError(message)
        return status().put("started", false).put("reason", message)
    }

    private fun redact(value: String): String =
        value.replace(Regex("(?i)(token|key|secret|password)=\\S+"), "$1=[redacted]")

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

    companion object {
        private const val ROOTFS_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        private const val PREFS = "rlaude_runtime"
        private const val KEY_BOOTSTRAP_COMPLETE = "bootstrap_complete"
        private const val KEY_RUNTIME_RUNNING = "runtime_running"
        private const val KEY_RUNTIME_VERSION = "runtime_version"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_LAST_OUTPUT = "last_output"
        private const val KEY_MODEL_PROVIDER = "model_provider"
        private const val KEY_MODEL_ID = "model_id"
        private const val KEY_API_KEY = "model_api_key"
    }
}