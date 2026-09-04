package com.codio

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Installs the data rootfs and npm package without ever executing a downloaded
 * file through Android's loader. Only libproot.so from jniLibs is executed.
 */
class BootstrapManager(private val context: Context) {
    private val files = FileManager(context)
    private val runtime = RuntimeManager(context, files)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    suspend fun initialize(onProgress: suspend (JSONObject) -> Unit = {}): JSONObject {
        if (runtime.bootstrapComplete()) {
            saveStatus("ready", STEP_NAMES.lastIndex, "Ready", null)
            return status()
        }
        return run(onProgress)
    }

    suspend fun retry(onProgress: suspend (JSONObject) -> Unit = {}): JSONObject = run(onProgress)

    fun status(): JSONObject {
        val state = prefs.getString(KEY_STATE, "idle").orEmpty()
        return JSONObject()
            .put("state", state)
            .put("ready", state == "ready" && runtime.bootstrapComplete())
            .put("inProgress", state == "running")
            .put("stepIndex", prefs.getInt(KEY_STEP_INDEX, 0))
            .put("step", prefs.getString(KEY_STEP, STEP_NAMES.first()))
            .put("message", prefs.getString(KEY_MESSAGE, "").orEmpty())
            .put("progress", prefs.getInt(KEY_PROGRESS, -1))
            .put("error", prefs.getString(KEY_ERROR, "").orEmpty().ifBlank { JSONObject.NULL })
            .put("steps", JSONArray(STEP_NAMES))
            .put("runtime", runtime.status())
    }

    private suspend fun run(onProgress: suspend (JSONObject) -> Unit): JSONObject =
        bootstrapMutex.withLock {
            if (runtime.bootstrapComplete()) {
                saveStatus("ready", STEP_NAMES.lastIndex, "Ready", null)
                return@withLock status()
            }
            try {
                step(0, "Preparing storage", onProgress)
                files.codioRoot.mkdirs()
                File(files.codioRoot, "Runtime/rootfs").mkdirs()

                step(1, "Downloading runtime", onProgress)
                val manifest = manifest()
                val archive = downloadRootfs(manifest, onProgress)

                step(2, "Extracting", onProgress)
                extractArchive(archive)

                step(3, "Installing opencode", onProgress)
                installOpenCode(manifest)

                step(4, "Starting server", onProgress)
                runtime.setBootstrapComplete(true)
                val started = runtime.start()
                if (!started.optBoolean("started") && !started.optBoolean("running")) {
                    throw IOException(started.optString("reason", "The OpenCode server could not start"))
                }

                step(5, "Ready", onProgress)
                saveStatus("ready", STEP_NAMES.lastIndex, "Ready", null)
                status()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                runtime.setBootstrapComplete(false)
                runtime.setLastError(error.message ?: "Bootstrap failed")
                saveStatus(
                    "failed",
                    prefs.getInt(KEY_STEP_INDEX, 0),
                    prefs.getString(KEY_STEP, "Bootstrap failed").orEmpty(),
                    error.message ?: "Bootstrap failed"
                )
                throw error
            }
        }

    private suspend fun step(index: Int, message: String, onProgress: suspend (JSONObject) -> Unit) {
        saveStatus("running", index, message, null)
        onProgress(status())
    }

    private fun manifest(): JSONObject {
        val text = context.assets.open("runtime-manifest.json").bufferedReader().use { it.readText() }
        val all = JSONObject(text)
        val entry = all.optJSONObject(runtime.architecture())
            ?: throw IOException("No runtime package is available for ${runtime.architecture()}")
        val url = entry.optString("url")
        val sha256 = entry.optString("sha256")
        if (url.isBlank() || sha256.length != 64 || sha256.contains("REPLACE")) {
            throw IOException("Runtime manifest is not configured for ${runtime.architecture()}")
        }
        return entry
            .put("version", all.optString("version", "unknown"))
            .put("package", all.optString("opencodePackage", "opencode-ai"))
    }

    private suspend fun downloadRootfs(
        manifest: JSONObject,
        onProgress: suspend (JSONObject) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val availableBytes = StatFs(files.codioRoot.absolutePath).availableBytes
        val minimumBytes = 700L * 1024L * 1024L
        if (availableBytes < minimumBytes) {
            val availableMb = availableBytes / (1024L * 1024L)
            throw IOException(
                "Not enough storage — need at least 700 MB free, only $availableMb MB available"
            )
        }

        val runtimeDir = File(files.codioRoot, "Runtime").apply { mkdirs() }
        val target = File(runtimeDir, "rootfs-${manifest.getString("version")}.tar.gz")
        if (target.exists() && sha256(target).equals(manifest.getString("sha256"), ignoreCase = true)) {
            return@withContext target
        }
        val connection = (URL(manifest.getString("url")).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Runtime download failed with HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            val part = File(target.absolutePath + ".part")
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var read = input.read(buffer)
                    while (read >= 0) {
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val percent = if (total > 0) (downloaded * 100 / total).toInt() else -1
                            saveStatus("running", 1, "Downloading runtime", null, percent)
                            onProgress(status())
                        }
                        read = input.read(buffer)
                    }
                }
            }
            if (!part.renameTo(target)) throw IOException("Could not finalize runtime download")
            if (!sha256(target).equals(manifest.getString("sha256"), ignoreCase = true)) {
                target.delete()
                throw IOException("Runtime SHA-256 verification failed")
            }
            target
        } finally {
            connection.disconnect()
        }
    }

    private fun extractArchive(archive: File) {
        val rootfs = runtime.rootfsDirectory()
        rootfs.deleteRecursively()
        rootfs.mkdirs()
        val listing = ProcessBuilder("/system/bin/tar", "-tzf", archive.absolutePath)
            .redirectErrorStream(true).start()
        val names = listing.inputStream.bufferedReader().readLines()
        if (!listing.waitFor(60, TimeUnit.SECONDS)) {
            listing.destroyForcibly()
            throw IOException("Runtime archive listing timed out")
        }
        if (listing.exitValue() != 0 || names.any { it.startsWith("/") || it.split("/").contains("..") }) {
            throw IOException("Runtime archive failed safety validation")
        }
        val extract = ProcessBuilder(
            "/system/bin/tar", "-xzf", archive.absolutePath, "-C", rootfs.absolutePath
        ).redirectErrorStream(true).start()
        val output = extract.inputStream.bufferedReader().readText().take(2_000)
        if (!extract.waitFor(120, TimeUnit.SECONDS) || extract.exitValue() != 0) {
            throw IOException("Runtime extraction failed: $output")
        }
        if (!File(rootfs, "bin/sh").exists()) throw IOException("Rootfs is missing /bin/sh")
    }

    private fun installOpenCode(manifest: JSONObject) {
        val packageName = manifest.getString("package")
        require(packageName.matches(Regex("[a-zA-Z0-9._@/-]+"))) { "Invalid OpenCode package name" }
        runProot("/bin/sh", "-c", "npm install -g $packageName")
        val verified = runProot("opencode", "--version")
        if (verified.exitCode != 0) throw IOException("OpenCode verification failed: ${verified.output}")
        runtime.markVersion(verified.output.lineSequence().firstOrNull()?.trim().orEmpty())
    }

    private fun runProot(vararg command: String): CommandResult {
        val proot = runtime.prootBinary()
        if (!proot.canExecute()) throw IOException("Bundled proot is missing for ${runtime.architecture()}")
        val process = ProcessBuilder(
            listOf(
                proot.absolutePath, "--link2symlink", "-0", "-r", runtime.rootfsDirectory().absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/root"
            ) + command.toList()
        )
            .directory(File(files.codioRoot, "OpenCode"))
            .redirectErrorStream(true)
            .apply {
                environment()["HOME"] = "/root"
                environment()["TERM"] = "dumb"
            }
            .start()
        val output = process.inputStream.bufferedReader().readText().take(20_000)
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("Runtime command timed out")
        }
        return CommandResult(process.exitValue(), output)
    }

    private fun saveStatus(
        state: String,
        index: Int,
        message: String,
        error: String?,
        progress: Int? = null
    ) {
        val editor = prefs.edit()
            .putString(KEY_STATE, state)
            .putInt(KEY_STEP_INDEX, index)
            .putString(KEY_STEP, message)
            .putString(KEY_MESSAGE, message)
            .putString(KEY_ERROR, error.orEmpty())
            .putInt(KEY_PROGRESS, progress ?: -1)
        editor.apply()
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

    private data class CommandResult(val exitCode: Int, val output: String)

    companion object {
        private val bootstrapMutex = Mutex()
        private val STEP_NAMES = listOf(
            "Preparing storage", "Downloading runtime", "Extracting",
            "Installing opencode", "Starting server", "Ready"
        )
        private const val PREFS = "codio_bootstrap"
        private const val KEY_STATE = "state"
        private const val KEY_STEP_INDEX = "step_index"
        private const val KEY_STEP = "step"
        private const val KEY_MESSAGE = "message"
        private const val KEY_ERROR = "error"
        private const val KEY_PROGRESS = "progress"
    }
}