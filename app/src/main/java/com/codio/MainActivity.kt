package com.codio

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        configureWebView()
        webView.loadUrl("file:///android_asset/codio.html")

        lifecycleScope.launch {
            val bootstrap = BootstrapManager(this@MainActivity)
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

    private fun startRuntimeService() {
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, CodioOpenCodeService::class.java)
            )
            serviceBound = bindService(
                Intent(this, CodioOpenCodeService::class.java),
                serviceConnection,
                BIND_AUTO_CREATE
            )
        }.onFailure { emitBootstrap(JSONObject().put("runtimeError", it.message ?: "Service failed to start")) }
    }

    private fun emitBootstrap(status: JSONObject) {
        val event = JSONObject().put("type", "bootstrap").put("payload", status).toString()
        webView.post {
            webView.evaluateJavascript(
                "window.CodioNative && window.CodioNative.onEvent(${JSONObject.quote(event)})",
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
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(
            WebBridge(
                webView = webView,
                files = FileManager(this),
                runtime = RuntimeManager(this, FileManager(this)),
                openCode = OpenCodeManager(this, FileManager(this)),
                bootstrap = BootstrapManager(this),
                scope = lifecycleScope
            ),
            "Codio"
        )
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        webView.removeJavascriptInterface("Codio")
        webView.destroy()
        super.onDestroy()
    }
}