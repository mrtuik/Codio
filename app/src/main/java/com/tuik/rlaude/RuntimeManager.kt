package com.tuik.rlaude

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
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

    fun lastOutput(): String = prefs.getString(KEY_LAST_OUTPUT, "").orEmpty()

    fun status(): JSONObject {
        val installed = bootstrapComplete() && rootfs.isDirectory && prootBinary().canExecute()
        val running = process?.isAlive == true
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

    fun defaultModelFor(provider: String): String = when (provider.lowercase()) {
        "opencode" -> "big-pickle"
        "google" -> "gemini-2.0-flash"
        "openrouter" -> "anthropic/claude-3.5-sonnet"
        "anthropic" -> "claude-3-5-sonnet-20241022"
        "openai" -> "gpt-4o"
        else -> ""
    }

    fun currentProvider(): String =
        prefs.getString(KEY_MODEL_PROVIDER, DEFAULT_PROVIDER).orEmpty().ifBlank { DEFAULT_PROVIDER }

    fun currentModel(): String {
        val saved = prefs.getString(KEY_MODEL_ID, "").orEmpty().trim()
        return saved.ifBlank { defaultModelFor(currentProvider()) }
    }

    fun isKeyRequired(provider: String = currentProvider()): Boolean {
        return provider.lowercase() != "opencode"
    }

    fun hasApiKey(): Boolean = !secureStorage.get(KEY_API_KEY).isNullOrBlank()

    suspend fun start(): JSONObject = withContext(Dispatchers.IO) {
        if (process?.isAlive == true) {
            return@withContext status().put("started", false)
        }
        prefs.edit().putBoolean(KEY_RUNTIME_RUNNING, false).apply()
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

        // 1. Ensure storage directory exists inside guest rootfs so proot can bind-mount it
        val guestMount = File(rootfs, files.rlaudeRoot.absolutePath.removePrefix("/"))
        guestMount.mkdirs()
        File(rootfs, "tmp").mkdirs()

        // 2. Configure DNS so OpenCode can resolve opencode.ai and AI model hosts
        val etcDir = File(rootfs, "etc").apply { mkdirs() }
        File(etcDir, "resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 8.8.4.4\n")
        val hostsFile = File(etcDir, "hosts")
        if (!hostsFile.exists() || hostsFile.length() == 0L) {
            hostsFile.writeText("127.0.0.1 localhost\n::1 localhost\n")
        }

        writeModelFilesIntoRootfs()

        val serverCommand = "opencode serve --hostname 127.0.0.1 --port $port"
        val prootArgs = listOf(
            proot.absolutePath,
            "--link2symlink", "-0",
            "-r", rootfs.absolutePath,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "${files.rlaudeRoot.absolutePath}:${files.rlaudeRoot.absolutePath}",
            "-b", "${context.cacheDir.absolutePath}:/tmp",
            "-w", "/root",
            "/bin/sh", "-c", serverCommand
        )

        val provider = currentProvider()
        val apiKey = secureStorage.get(KEY_API_KEY).orEmpty().trim()

        process = ProcessBuilder(prootArgs)
            .directory(File(files.rlaudeRoot, "OpenCode"))
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = "/root"
                environment()["TERM"] = "dumb"
                environment()["PATH"] = ROOTFS_PATH
                environment()["PROOT_LOADER"] = loaderBinary().absolutePath
                environment()["PROOT_TMP_DIR"] = context.cacheDir.absolutePath

                if (apiKey.isNotEmpty()) {
                    environment()["OPENCODE_API_KEY"] = apiKey
                    when (provider.lowercase()) {
                        "anthropic" -> environment()["ANTHROPIC_API_KEY"] = apiKey
                        "openai" -> environment()["OPENAI_API_KEY"] = apiKey
                        "google" -> {
                            environment()["GEMINI_API_KEY"] = apiKey
                            environment()["GOOGLE_GENERATIVE_AI_API_KEY"] = apiKey
                        }
                        "openrouter" -> environment()["OPENROUTER_API_KEY"] = apiKey
                        "opencode" -> environment()["OPENCODE_API_KEY"] = apiKey
                        else -> environment()["${provider.uppercase()}_API_KEY"] = apiKey
                    }
                }
            }
            .start()

        prefs.edit().putBoolean(KEY_RUNTIME_RUNNING, true).apply()
        setLastError(null)
        val launched = process
        thread(name = "rlaude-opencode-output", isDaemon = true) {
            runCatching {
                launched?.inputStream?.bufferedReader()?.forEachLine { line ->
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
        .put("provider", currentProvider())
        .put("model", currentModel())
        .put("hasApiKey", hasApiKey())
        .put("isKeyRequired", isKeyRequired())

    fun setModelConfig(provider: String, model: String, apiKey: String?) {
        val cleanProvider = provider.trim()
        if (cleanProvider.isEmpty()) throw IllegalArgumentException("Choose a provider")
        prefs.edit()
            .putString(KEY_MODEL_PROVIDER, cleanProvider)
            .putString(KEY_MODEL_ID, model.trim())
            .apply()
        if (!apiKey.isNullOrBlank()) {
            secureStorage.put(KEY_API_KEY, apiKey.trim())
        }
        writeModelFilesIntoRootfs()
    }

    private fun writeModelFilesIntoRootfs() {
        val provider = currentProvider()
        if (!rootfs.isDirectory) return
        val model = currentModel()
        val apiKey = secureStorage.get(KEY_API_KEY).orEmpty().trim()

        runCatching {
            val configDir = File(rootfs, "root/.config/opencode").apply { mkdirs() }
            val config = JSONObject()
            if (model.isNotBlank()) {
                val fullModel = if (model.contains("/")) model else "$provider/$model"
                config.put("model", fullModel)
            }
            File(configDir, "opencode.json").writeText(config.toString(2))

            if (apiKey.isNotBlank()) {
                val auth = JSONObject().put(provider, JSONObject().put("type", "api").put("key", apiKey))
                val authDir = File(rootfs, "root/.local/share/opencode").apply { mkdirs() }
                File(authDir, "auth.json").writeText(auth.toString(2))
                File(configDir, "auth.json").writeText(auth.toString(2))
            }
        }
    }

    private fun fail(message: String): JSONObject {
        setLastError(message)
        return status().put("started", false).put("reason", message)
    }

    private fun redact(value: String): String =
        value.replace(Regex("(?i)(token|key|secret|password)=\\S+"), "$1=[redacted]")

    companion object {
        @Volatile
        private var process: Process? = null
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
        private const val DEFAULT_PROVIDER = "opencode"
    }
}