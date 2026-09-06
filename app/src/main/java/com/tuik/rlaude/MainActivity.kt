package com.tuik.rlaude

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
        }
    }

    // Must be registered unconditionally before onCreate finishes — a field
    // initializer runs during construction, which satisfies that requirement.
    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) handlePickedFile(uri) else emitFileEvent(JSONObject().put("cancelled", true))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bootstrap = BootstrapManager(this)
        // Read this before initialize() runs — the first thing initialize()
        // does when retrying is overwrite this same persisted step.
        val interruptedStep = bootstrap.lastInterruptedStep()
        showDiagnosticsIfAny(interruptedStep)
        webView = WebView(this)
        setContentView(webView)
        configureWebView()
        webView.loadUrl("file:///android_asset/rlaude.html")

        lifecycleScope.launch {
            try {
                val result = bootstrap.initialize { status -> emitBootstrap(status) }
                emitBootstrap(result)
                if (result.optBoolean("ready")) startRuntimeService()
            } catch (error: Exception) {
                emitBootstrap(
                    JSONObject()
                        .put("state", "failed")
                        .put("ready", false)
                        .put("error", error.message ?: "Bootstrap failed")
                )
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    42
                )
            }
        }
    }

    private fun showDiagnosticsIfAny(interruptedStep: String?) {
        val crashLog = RlaudeApplication.latestCrashLog(this)
        if (crashLog == null && interruptedStep == null) return

        val message = buildString {
            if (interruptedStep != null) {
                append("The previous run never finished. It was last known to be on:\n\n")
                append(interruptedStep)
                append("\n\nNo exception was caught for this, which usually means the app ")
                append("process was killed directly (often low memory) rather than crashing ")
                append("with a Java error.\n")
            }
            if (crashLog != null) {
                if (interruptedStep != null) append("\n---\n\n")
                append(crashLog)
            }
        }
        val textView = TextView(this).apply {
            text = message
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Rlaude didn't finish last time")
            .setView(ScrollView(this).apply { addView(textView) })
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Rlaude diagnostics", message))
                RlaudeApplication.clearCrashLogs(this)
            }
            .setNegativeButton("Dismiss") { _, _ ->
                RlaudeApplication.clearCrashLogs(this)
            }
            .setCancelable(false)
            .show()
    }

    private fun startRuntimeService() {
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, RlaudeOpenCodeService::class.java)
            )
            serviceBound = bindService(
                Intent(this, RlaudeOpenCodeService::class.java),
                serviceConnection,
                BIND_AUTO_CREATE
            )
        }.onFailure { emitBootstrap(JSONObject().put("runtimeError", it.message ?: "Service failed to start")) }
    }

    private fun emitBootstrap(status: JSONObject) {
        val event = JSONObject().put("type", "bootstrap").put("payload", status).toString()
        webView.post {
            webView.evaluateJavascript(
                "window.RlaudeNative && window.RlaudeNative.onEvent(${JSONObject.quote(event)})",
                null
            )
        }
    }

    private fun launchFilePicker() {
        webView.post { runCatching { filePickerLauncher.launch("*/*") } }
    }

    private fun handlePickedFile(uri: Uri) {
        runCatching {
            val name = queryFileName(uri) ?: uri.lastPathSegment ?: "file"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            if (bytes.size > MAX_ATTACHMENT_BYTES) {
                emitFileEvent(JSONObject().put("error", "\"$name\" is larger than the 300 KB attachment limit"))
                return
            }
            val content = String(bytes, Charsets.UTF_8)
            emitFileEvent(JSONObject().put("name", name).put("content", content).put("size", bytes.size))
        }.onFailure {
            emitFileEvent(JSONObject().put("error", it.message ?: "Could not read that file"))
        }
    }

    private fun queryFileName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return null
    }

    private fun emitFileEvent(payload: JSONObject) {
        val event = JSONObject().put("type", "fileAttached").put("payload", payload).toString()
        webView.post {
            webView.evaluateJavascript(
                "window.RlaudeNative && window.RlaudeNative.onEvent(${JSONObject.quote(event)})",
                null
            )
        }
    }

    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = true
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                try {
                    val dir = java.io.File(filesDir, "crash_logs").apply { mkdirs() }
                    val stamp = java.text.SimpleDateFormat(
                        "yyyy-MM-dd_HH-mm-ss", java.util.Locale.US
                    ).format(java.util.Date())
                    java.io.File(dir, "crash-$stamp.txt").writeText(
                        "WebView renderer process gone.\n" +
                            "didCrash=${detail?.didCrash()}\n" +
                            "rendererPriorityAtExit=${detail?.rendererPriorityAtExit()}"
                    )
                } catch (_: Throwable) {
                    // Never let logging the renderer crash cause another crash.
                }
                finish()
                return true
            }
        }
        webView.webChromeClient = WebChromeClient()
        val files = FileManager(this)
        val runtime = RuntimeManager.getInstance(this, files)
        webView.addJavascriptInterface(
            WebBridge(
                webView = webView,
                files = files,
                runtime = runtime,
                openCode = OpenCodeManager(runtime, files),
                bootstrap = BootstrapManager(this),
                chatStore = ChatStore(files),
                scope = lifecycleScope,
                onPickFile = { launchFilePicker() }
            ),
            "Rlaude"
        )
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        webView.removeJavascriptInterface("Rlaude")
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val MAX_ATTACHMENT_BYTES = 300 * 1024
    }
}
