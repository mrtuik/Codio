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
 *
 * This class must be a true singleton for the whole app process — MainActivity,
 * BootstrapManager, RlaudeOpenCodeService, and OpenCodeManager used to each build
 * their own separate instance, which meant each held its own `process` handle for
 * the same underlying rootfs/port. That made "Stop"/"Restart" act on an instance
 * that never actually launched the process, and made getRuntimeStatus() trust a
 * persisted flag instead of the real child process — so the sidebar could say
 * "Runtime ready" while chat still failed to connect. Always obtain this class
 * through getInstance().
 */
class RuntimeManager private constructor(context: Context, private val files: FileManager) {
    private val appContext = context.applicationContext
    private val runtimeDir = File(files.rlaudeRoot, "Runtime")
    private val rootfs = File(runtimeDir, "rootfs")
    private val port = 4096
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val secureStorage = SecureStorage(appContext)
    @Volatile private var process: Process? = null

    fun architecture(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

    fun prootBinary(): File = File(appContext.applicationInfo.nativeLibraryDir, "libproot.so")

    fun loaderBinary(): File = File(appContext.applicationInfo.nativeLibraryDir, "libproot_loader.so")

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

    fun isProcessAlive(): Boolean = process?.isAlive == true

    /**
     * Synchronous, short-timeout probe used by status() so the UI never shows
     * "Runtime ready" unless the server is actually answering right now.
     */
    private fun healthSync(): Boolean = try {
        val connection = (URL("http://127.0.0.1:$port/global/health").openConnection() as HttpURLConnection).apply {
            connectTimeout = 400
            readTimeout = 400
            requestMethod = "GET"
        }
        val code = try { connection.responseCode } finally { connection.disconnect() }
        code in 200..299
    } catch (_: Exception) {
        false
    }

    fun status(): JSONObject {
        val installed = bootstrapComplete() && rootfs.isDirectory && prootBinary().canExecute()
        // Only probe over HTTP if there's a reason to think something might be
        // listening — avoids a pointless round trip before install/bootstrap.
        val worthProbing = isProcessAlive() || prefs.getBoolean(KEY_RUNTIME_RUNNING, false) || installed
        val healthy = if (worthProbing) healthSync() else false
        if (prefs.getBoolean(KEY_RUNTIME_RUNNING, false) != healthy) {
            // Self-heal a persisted flag that no longer matches reality — e.g. the
            // process that set it died, or the app cold-started after a kill.
            prefs.edit().putBoolean(KEY_RUNTIME_RUNNING, healthy).apply()
        }
        return JSONObject()
            .put("installed", installed)
            .put("bootstrapComplete", bootstrapComplete())
            .put("running", healthy)
            .put("healthy", healthy)
            .put("architecture", architecture())
            .put("version", if (installed) prefs.getString(KEY_RUNTIME_VERSION, "managed-rootfs") else JSONObject.NULL)
            .put("lastError", lastError().ifBlank { JSONObject.NULL })
            .put("lastOutput", lastOutput().ifBlank { JSONObject.NULL })
            .put("modelProvider", effectiveProvider())
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
        if (isProcessAlive()) {
            return@withContext status().put("started", false)
        }
        // Don't trust the persisted "running" flag to gate a (re)launch — it can
        // go stale (process died, or a different app process set it). A real
        // health probe is cheap and is the only thing that can't lie.
        if (healthSync()) {
            prefs.edit().putBoolean(KEY_RUNTIME_RUNNING, true).apply()
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
        val serverCommand = "opencode serve --hostname 127.0.0.1 --port $port"
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
                environment()["PROOT_TMP_DIR"] = appContext.cacheDir.absolutePath
            }
            .start()
        prefs.edit().putBoolean(KEY_RUNTIME_RUNNING, true).apply()
        setLastError(null)
        prefs.edit().putString(KEY_LAST_OUTPUT, "").apply()
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

    private fun effectiveProvider(): String =
        prefs.getString(KEY_MODEL_PROVIDER, "").orEmpty().ifBlank { DEFAULT_PROVIDER }

    fun modelConfig(): JSONObject {
        val provider = effectiveProvider()
        return JSONObject()
            .put("provider", provider)
            .put("model", prefs.getString(KEY_MODEL_ID, "").orEmpty())
            .put("hasApiKey", !secureStorage.get(KEY_API_KEY).isNullOrBlank())
            .put("requiresApiKey", provider != DEFAULT_PROVIDER)
            .put("isDefault", prefs.getString(KEY_MODEL_PROVIDER, "").isNullOrBlank())
    }

    /** Mirrors what `opencode auth login` + a hand-edited opencode.json would do. */
    fun setModelConfig(provider: String, model: String, apiKey: String?) {
        // An empty provider means "reset to the default", not an error — OpenCode
        // Zen needs nothing else to work, so it's always a valid choice.
        val cleanProvider = provider.trim().ifBlank { DEFAULT_PROVIDER }
        prefs.edit()
            .putString(KEY_MODEL_PROVIDER, if (cleanProvider == DEFAULT_PROVIDER) "" else cleanProvider)
            .putString(KEY_MODEL_ID, model.trim())
            .apply()
        if (!apiKey.isNullOrBlank()) secureStorage.put(KEY_API_KEY, apiKey.trim())
        writeModelFilesIntoRootfs()
    }

    private fun writeModelFilesIntoRootfs() {
        if (!rootfs.isDirectory) return
        val provider = effectiveProvider()
        // OpenCode Zen still needs *a* model id server-side — an empty model
        // with only a provider name was answering every chat request with a
        // generic "Unexpected server error" once the server itself came up.
        val model = prefs.getString(KEY_MODEL_ID, "").orEmpty()
            .ifBlank { if (provider == DEFAULT_PROVIDER) DEFAULT_MODEL else "" }
        val apiKey = secureStorage.get(KEY_API_KEY)
        runCatching {
            val configDir = File(rootfs, "root/.config/opencode").apply { mkdirs() }
            val config = JSONObject()
            if (model.isNotBlank()) config.put("model", "$provider/$model")
            File(configDir, "opencode.json").writeText(config.toString(2))
            // OpenCode Zen (the "opencode" provider) is free and works with no
            // key at all — opencode falls back to a public key for it on its
            // own. Only write auth.json for providers that actually need one.
            if (provider != DEFAULT_PROVIDER && !apiKey.isNullOrBlank()) {
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
        @Volatile private var instance: RuntimeManager? = null

        /** Always use this instead of the constructor — see class doc. */
        fun getInstance(context: Context, files: FileManager): RuntimeManager =
            instance ?: synchronized(this) {
                instance ?: RuntimeManager(context, files).also { instance = it }
            }

        /** Provider id used by default — OpenCode Zen, free, no API key required. */
        const val DEFAULT_PROVIDER = "opencode"

        /** Known-free OpenCode Zen model id, used when no model is explicitly set. */
        const val DEFAULT_MODEL = "grok-code-fast-1"

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
