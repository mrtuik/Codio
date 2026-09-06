package com.tuik.rlaude

import android.content.Context
import android.os.PowerManager
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors
import java.util.zip.GZIPInputStream

/**
 * Installs the data rootfs and npm package without ever executing a downloaded
 * file through Android's loader. Only libproot.so from jniLibs is executed.
 */
class BootstrapManager(private val context: Context) {
    private val files = FileManager(context)
    private val runtime = RuntimeManager(context, files)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val wakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Rlaude:bootstrap")
    }

    suspend fun initialize(onProgress: suspend (JSONObject) -> Unit = {}): JSONObject {
        if (runtime.bootstrapComplete()) {
            saveStatus("ready", STEP_NAMES.lastIndex, "Ready", null)
            return status()
        }
        return run(onProgress)
    }

    suspend fun retry(onProgress: suspend (JSONObject) -> Unit = {}): JSONObject = run(onProgress)

    /**
     * Reads the last persisted step without starting or changing anything.
     * If the previous run was killed silently (native crash, OS low-memory
     * kill) with no Java exception to catch, the state will still read
     * "running" here because saveStatus() commits to SharedPreferences
     * synchronously *before* each risky step executes. This is the only way
     * to localize such a crash without adb/root log access.
     */
    fun lastInterruptedStep(): String? {
        val state = prefs.getString(KEY_STATE, "idle")
        if (state != "running") return null
        val index = prefs.getInt(KEY_STEP_INDEX, 0)
        val name = prefs.getString(KEY_STEP, STEP_NAMES.getOrElse(index) { "unknown" })
        return "Step ${index + 1}/${STEP_NAMES.size}: $name"
    }

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
                // Held for the whole bootstrap so the OS doesn't treat the
                // process as idle/killable while extraction and opencode
                // verification block below (both are native process calls
                // that used to run on the caller's dispatcher, i.e. the
                // main thread, with no protection — that's what caused the
                // silent low-memory-style kills at "Extracting").
                wakeLock.acquire(10 * 60 * 1000L)

                step(0, "Preparing storage", onProgress)
                files.rlaudeRoot.mkdirs()
                File(files.rlaudeRoot, "Runtime/rootfs").mkdirs()

                step(1, "Downloading runtime", onProgress)
                val manifest = manifest()
                val archive = downloadRootfs(manifest, onProgress)

                step(2, "Extracting", onProgress)
                withContext(Dispatchers.IO) { extractArchive(archive) }

                step(3, "Installing opencode", onProgress)
                withContext(Dispatchers.IO) { installOpenCode(manifest) }

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
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
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
        val availableBytes = StatFs(files.rlaudeRoot.absolutePath).availableBytes
        val minimumBytes = 700L * 1024L * 1024L
        if (availableBytes < minimumBytes) {
            val availableMb = availableBytes / (1024L * 1024L)
            throw IOException(
                "Not enough storage — need at least 700 MB free, only $availableMb MB available"
            )
        }

        val runtimeDir = File(files.rlaudeRoot, "Runtime").apply { mkdirs() }
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
                    var lastProgressAt = 0L
                    var lastProgress = -2
                    var read = input.read(buffer)
                    while (read >= 0) {
                        if (read > 0) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val percent = if (total > 0) (downloaded * 100 / total).toInt() else -1
                            val now = System.currentTimeMillis()
                            // Do not enqueue a WebView event and SharedPreferences
                            // write for every 64 KB chunk. Large rootfs downloads
                            // can otherwise flood the UI and trigger memory pressure.
                            if (percent != lastProgress || now - lastProgressAt >= 500L) {
                                saveStatus("running", 1, "Downloading runtime", null, percent)
                                onProgress(status())
                                lastProgress = percent
                                lastProgressAt = now
                            }
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

    /**
     * Extracts the runtime archive with an in-process tar reader instead of
     * /system/bin/tar. Toybox/BSD tar builds differ between devices and reject
     * archives GNU tar produced (pax headers, entry ordering), which surfaced
     * as "Runtime archive failed safety validation" on the Extracting step.
     * The same safety rules still apply: no absolute paths and no entries
     * escaping the rootfs directory.
     */
    private fun extractArchive(archive: File) {
        val rootfs = runtime.rootfsDirectory()
        rootfs.deleteRecursively()
        rootfs.mkdirs()
        val root = rootfs.canonicalFile.toPath()
        archive.inputStream().buffered(256 * 1024).use { raw ->
            GZIPInputStream(raw).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        extractEntry(tar, entry, root)
                        entry = tar.nextEntry
                    }
                }
            }
        }
        repairAbsoluteSymlinks(rootfs)
        val shell = File(rootfs, "bin/sh")
        if (!shell.exists()) {
            val target = runCatching {
                if (Files.isSymbolicLink(shell.toPath())) {
                    Files.readSymbolicLink(shell.toPath()).toString()
                } else {
                    null
                }
            }.getOrNull()
            val detail = target?.let { " (link target: $it)" }.orEmpty()
            throw IOException("Rootfs is missing /bin/sh$detail")
        }
    }

    private fun extractEntry(input: TarArchiveInputStream, entry: TarArchiveEntry, root: Path) {
        val name = entry.name
        val target = root.resolve(name).normalize()
        if (name.startsWith("/") || !target.startsWith(root)) {
            throw IOException("Runtime archive failed safety validation")
        }
        when {
            entry.isDirectory -> Files.createDirectories(target)
            entry.isSymbolicLink -> {
                Files.createDirectories(target.parent)
                Files.deleteIfExists(target)
                Files.createSymbolicLink(target, Paths.get(entry.linkName))
            }
            entry.isLink -> {
                val linkSource = root.resolve(entry.linkName).normalize()
                if (linkSource.startsWith(root) && Files.exists(linkSource)) {
                    Files.createDirectories(target.parent)
                    Files.copy(linkSource, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            else -> {
                Files.createDirectories(target.parent)
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
                if (entry.mode and 0x49 != 0) {
                    target.toFile().setExecutable(true, false)
                }
            }
        }
    }

    /**
     * Android extracts the archive below the app's private directory, while
     * Alpine creates links as if the archive were the real filesystem root.
     * Turn links such as /bin/sh -> /bin/busybox into links relative to the
     * extracted root so they also work when launched through proot.
     */
    private fun repairAbsoluteSymlinks(rootfs: File) {
        val root = rootfs.toPath()
        val links = Files.walk(root).use { stream ->
            stream
                .filter { Files.isSymbolicLink(it) }
                .collect(Collectors.toList())
        }
        for (link in links) {
            val target = Files.readSymbolicLink(link).toString()
            if (!target.startsWith("/")) continue
            val targetInsideRoot = root
                .resolve(target.removePrefix("/"))
                .normalize()
            if (!targetInsideRoot.startsWith(root)) {
                throw IOException("Unsafe runtime symlink: $link -> $target")
            }
            val relativeTarget = link.parent.relativize(targetInsideRoot)
            Files.delete(link)
            Files.createSymbolicLink(link, relativeTarget)
        }
    }


    private fun installOpenCode(manifest: JSONObject) {
        // opencode-ai is already installed inside the rootfs archive itself
        // (baked in during the CI runtime build). Re-running npm install on
        // the device here was redundant network+CPU+RAM work under proot and
        // is the likely cause of low-memory crashes right after extraction.
        // Only verify the pre-installed binary responds.
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
            .directory(File(files.rlaudeRoot, "OpenCode"))
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
        private const val PREFS = "rlaude_bootstrap"
        private const val KEY_STATE = "state"
        private const val KEY_STEP_INDEX = "step_index"
        private const val KEY_STEP = "step"
        private const val KEY_MESSAGE = "message"
        private const val KEY_ERROR = "error"
        private const val KEY_PROGRESS = "progress"
    }
}
