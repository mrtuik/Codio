package com.codio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        configureWebView()
        webView.loadUrl("file:///android_asset/codio.html")

        lifecycleScope.launchWhenCreated {
            BootstrapManager(this@MainActivity).initialize()
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
            ContextCompat.startForegroundService(
                this@MainActivity,
                Intent(this@MainActivity, CodioOpenCodeService::class.java)
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
                scope = lifecycleScope
            ),
            "Codio"
        )
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("Codio")
        webView.destroy()
        super.onDestroy()
    }
}