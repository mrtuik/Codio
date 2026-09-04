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
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.nio.file.Files

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

    private fun extractArchive(archive: File) {
        val rootfs = runtime.rootfsDirectory()
        rootfs.deleteRecursively()
        rootfs.mkdirs()
        val hardLinks = mutableListOf<HardLink>()
        GZIPInputStream(BufferedInputStream(FileInputStream(archive))).use { input ->
            val header = ByteArray(TAR_BLOCK_SIZE)
            var longName: String? = null
            var longLink: String? = null
            var paxPath: String? = null
            var paxLink: String? = null

            while (readTarBlock(input, header)) {
                if (header.all { it == 0.toByte() }) break

                val type = header[156].toInt().toChar()
                val size = tarOctal(header, 124, 12)
                when (type) {
                    'L' -> {
                        longName = readTarText(input, size)
                        skipTarPadding(input, size)
                        continue
                    }
                    'K' -> {
                        longLink = readTarText(input, size)
                        skipTarPadding(input, size)
                        continue
                    }
                    'x', 'g' -> {
                        val pax = readTarText(input, size)
                        parsePax(pax).let {
                            paxPath = it.first ?: paxPath
                            paxLink = it.second ?: paxLink
                        }
                        skipTarPadding(input, size)
                        continue
                    }
                }

                val name = safeArchivePath(
                    paxPath ?: longName ?: tarName(header)
                )
                val link = (paxLink ?: longLink ?: tarField(header, 157, 100))
                    .takeIf { it.isNotBlank() }
                    ?.let { safeLinkTarget(it) }
                val destination = File(rootfs, name)
                destination.parentFile?.mkdirs()

                when (type) {
                    '\u0000', '0', '7' -> {
                        deletePath(destination)
                        FileOutputStream(destination).use { output ->
                            copyExactly(input, output, size)
                        }
                        applyTarMode(destination, tarOctal(header, 100, 8))
                        skipTarPadding(input, size)
                    }
                    '5' -> {
                        destination.mkdirs()
                        applyTarMode(destination, tarOctal(header, 100, 8))
                        skipTarPadding(input, size)
                    }
                    '2' -> {
                        if (link == null) throw IOException("Symlink entry has no target: $name")
                        deletePath(destination)
                        Files.createSymbolicLink(destination.toPath(), link.toPath())
                        skipTarPadding(input, size)
                    }
                    '1' -> {
                        if (link == null) throw IOException("Hard-link entry has no target: $name")
                        // Android may reject hard-link creation inside app-private
                        // storage. Resolve it after extraction by copying bytes.
                        hardLinks += HardLink(
                            destination,
                            File(rootfs, safeArchivePath(link)),
                            tarOctal(header, 100, 8)
                        )
                        skipTarPadding(input, size)
                    }
                    else -> {
                        // Device nodes and other special entries are not needed
                        // for the rootfs; consume their payload safely.
                        skipTarBytes(input, size)
                        skipTarPadding(input, size)
                    }
                }
                longName = null
                longLink = null
                paxPath = null
                paxLink = null
            }
        }

        hardLinks.forEach { hardLink ->
            if (!hardLink.target.isFile) {
                throw IOException("Hard-link target is missing: ${hardLink.target.path}")
            }
            hardLink.destination.parentFile?.mkdirs()
            hardLink.target.inputStream().use { input ->
                hardLink.destination.outputStream().use { output -> input.copyTo(output) }
            }
            applyTarMode(hardLink.destination, hardLink.mode)
        }
        if (!File(rootfs, "bin/sh").exists()) throw IOException("Rootfs is missing /bin/sh")
    }

    private fun readTarBlock(input: java.io.InputStream, block: ByteArray): Boolean {
        var offset = 0
        while (offset < TAR_BLOCK_SIZE) {
            val count = input.read(block, offset, TAR_BLOCK_SIZE - offset)
            if (count < 0) {
                if (offset == 0) return false
                throw IOException("Runtime archive ended in the middle of a tar header")
            }
            offset += count
        }
        return true
    }

    private fun tarName(header: ByteArray): String {
        val name = tarField(header, 0, 100)
        val prefix = tarField(header, 345, 155)
        return if (prefix.isBlank()) name else "$prefix/$name"
    }

    private fun tarField(header: ByteArray, start: Int, length: Int): String =
        String(header, start, length, Charsets.UTF_8).trimEnd('\u0000', ' ')

    private fun tarOctal(header: ByteArray, start: Int, length: Int): Long =
        tarField(header, start, length).trim().ifBlank { "0" }.toLongOrNull(8)
            ?: throw IOException("Invalid tar numeric field")

    private fun readTarText(input: java.io.InputStream, size: Long): String {
        if (size > MAX_METADATA_BYTES) throw IOException("Runtime archive metadata is too large")
        val bytes = ByteArray(size.toInt())
        readExactly(input, bytes)
        return String(bytes, Charsets.UTF_8).trimEnd('\u0000', '\n')
    }

    private fun parsePax(payload: String): Pair<String?, String?> {
        var path: String? = null
        var link: String? = null
        payload.split('\n').forEach { record ->
            val separator = record.indexOf('=')
            if (separator <= 0) return@forEach
            when (record.substring(0, separator).substringAfterLast(' ')) {
                "path" -> path = record.substring(separator + 1).trimEnd('\r')
                "linkpath" -> link = record.substring(separator + 1).trimEnd('\r')
            }
        }
        return path to link
    }

    private fun safeArchivePath(path: String): String {
        val normalized = path.replace('\\', '/').trimStart('/')
        if (normalized.isBlank() || normalized.split('/').any { it == ".." }) {
            throw IOException("Unsafe path in runtime archive")
        }
        return normalized.split('/').filter { it.isNotBlank() && it != "." }.joinToString("/")
    }

    private fun safeLinkTarget(target: String): String {
        if (target.contains('\u0000')) throw IOException("Invalid link target in runtime archive")
        return target
    }

    private fun copyExactly(input: java.io.InputStream, output: java.io.OutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("Runtime archive ended inside a file")
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun readExactly(input: java.io.InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw IOException("Runtime archive ended unexpectedly")
            offset += count
        }
    }

    private fun skipTarBytes(input: java.io.InputStream, size: Long) {
        var remaining = size
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0L) {
                if (input.read() < 0) throw IOException("Runtime archive ended unexpectedly")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun skipTarPadding(input: java.io.InputStream, size: Long) {
        val padding = (TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE
        skipTarBytes(input, padding)
    }

    private fun deletePath(path: File) {
        if (path.exists() || Files.exists(path.toPath())) Files.deleteIfExists(path.toPath())
    }

    private fun applyTarMode(file: File, mode: Long) {
        file.setReadable(mode and 0x100L != 0L, false)
        file.setWritable(mode and 0x80L != 0L, false)
        file.setExecutable(mode and 0x40L != 0L, false)
    }

    private data class HardLink(val destination: File, val target: File, val mode: Long)

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
        private const val TAR_BLOCK_SIZE = 512
        private const val MAX_METADATA_BYTES = 4L * 1024L * 1024L
    }
}