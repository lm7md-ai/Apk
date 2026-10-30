/*
 * WebView Modern Shell — Universal APK Factory & Studio
 * Copyright (C) 2026 Universal APK Factory contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.universal.apkfactory.shell.webview

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * The modern WebView shell (Module 3 — "The Missing Shell Problem").
 *
 * The user's web payload is injected at patch time under `assets/www/` and is
 * served over `https://appassets.androidplatform.net/` through
 * [WebViewAssetLoader]. Serving on an **https origin** (instead of file://)
 * unlocks:
 *
 *  * service workers (PWA support),
 *  * localStorage / IndexedDB (DOM storage is fully enabled),
 *  * WebGL and WebAssembly,
 *  * a secure context for modern browser APIs.
 *
 * Runtime behavior is driven by `assets/shell_config.json` written by the
 * patcher:
 *
 * ```
 * {
 *   "appName": "My App",
 *   "orientation": "portrait" | "landscape" | "unspecified",
 *   "fullscreen": false,
 *   "startPath": "www/index.html"
 * }
 * ```
 *
 * Features:
 *  * `<input type="file">` uploads via the system content picker,
 *  * a JavaScript ↔ Android bridge exposed as `window.AndroidBridge`
 *    (toast, share, vibrate, appInfo, keepScreenOn, exit),
 *  * external http(s)/mailto/tel links handed to matching apps,
 *  * getUserMedia permission mapping onto Android runtime permissions,
 *  * render-crash recovery (process-gone → activity recreation),
 *  * back-navigation history support.
 */
class WebViewModernShellActivity : Activity() {

    private lateinit var webView: WebView
    private var filePathCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    private var shellConfig: ShellConfig = ShellConfig()

    /** Immutable configuration injected by the patcher. */
    data class ShellConfig(
        val appName: String = "App",
        val orientation: String = "unspecified",
        val fullscreen: Boolean = false,
        val startPath: String = DEFAULT_START_PATH,
    ) {
        companion object {
            const val DEFAULT_START_PATH = "www/index.html"

            fun parse(raw: String?): ShellConfig = try {
                if (raw.isNullOrBlank()) ShellConfig() else {
                    val root = JSONObject(raw)
                    ShellConfig(
                        appName = root.optString("appName", "App"),
                        orientation = root.optString("orientation", "unspecified"),
                        fullscreen = root.optBoolean("fullscreen", false),
                        startPath = root.optString("startPath", DEFAULT_START_PATH)
                            .ifBlank { DEFAULT_START_PATH },
                    )
                }
            } catch (error: org.json.JSONException) {
                Log.w(TAG, "Malformed shell_config.json — using defaults", error)
                ShellConfig()
            }
        }
    }

    /* ================================================================== */
    /* Lifecycle                                                           */
    /* ================================================================== */

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shellConfig = ShellConfig.parse(readAssetText(CONFIG_ASSET))
        applyOrientation(shellConfig.orientation)

        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        webView.setLayoutParams(
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(webView)

        configureWebViewSettings(webView.settings)

        webView.webViewClient = ShellWebViewClient(assetLoader)
        webView.webChromeClient = ShellWebChromeClient()
        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(startUrl(shellConfig.startPath))
        }

        if (shellConfig.fullscreen) {
            enterImmersiveFullscreen()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
    }

    override fun onPause() {
        webView.onPause()
        webView.pauseTimers()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    /* ================================================================== */
    /* Web settings                                                        */
    /* ================================================================== */

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebViewSettings(settings: WebSettings) {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true            // localStorage + IndexedDB
        settings.databaseEnabled = true              // Web SQL (legacy apps)
        settings.allowFileAccess = false             // payload is https-served
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.textZoom = 100
        // Modern WebView enables WebGL and WebAssembly out of the box; the
        // https origin additionally guarantees a secure context (required by
        // many WASM toolchains).
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        WebView.setWebContentsDebuggingEnabled(false)
    }

    /* ================================================================== */
    /* WebView client: asset interception + external links                 */
    /* ================================================================== */

    private inner class ShellWebViewClient(
        private val assetLoader: WebViewAssetLoader,
    ) : WebViewClient() {

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            // WebViewAssetLoader handles only requests for its own domain and
            // passes everything else back to the WebView unchanged. The
            // platform WebResourceRequest is adapted to the loader's
            // WebRequest interface first.
            return assetLoader.intercept(
                WebViewAssetLoader.WebRequestAdapter(request)
            )
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest,
        ): Boolean {
            val url = request.url
            return when {
                // Internal payload navigation stays inside the WebView.
                url.host == ASSET_DOMAIN -> false

                url.scheme == "http" || url.scheme == "https" -> {
                    openExternally(Intent(Intent.ACTION_VIEW, url))
                    true
                }

                url.scheme == "mailto" || url.scheme == "tel" ||
                    url.scheme == "sms" || url.scheme == "geo" ||
                    url.scheme == "market" -> {
                    openExternally(Intent(Intent.ACTION_VIEW, url))
                    true
                }

                url.scheme == "intent" -> try {
                    val parsed = Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME)
                    parsed.addCategory(Intent.CATEGORY_BROWSABLE)
                    parsed.component = null
                    openExternally(parsed)
                    true
                } catch (error: Exception) {
                    true
                }

                else -> false
            }
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            if (!request.isForMainFrame) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                error.errorCode == WebViewClient.ERROR_FILE_NOT_FOUND
            ) {
                view.loadDataWithBaseURL(
                    startUrl(shellConfig.startPath),
                    ERROR_PAGE_HTML,
                    "text/html",
                    "utf-8",
                    null
                )
            }
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail,
        ): Boolean {
            Log.e(TAG, "WebView render process gone (crashed=${detail.didCrash()})")
            recreate()
            return true
        }
    }

    /* ================================================================== */
    /* Chrome client: file upload + getUserMedia permissions               */
    /* ================================================================== */

    private inner class ShellWebChromeClient : WebChromeClient() {

        override fun onShowFileChooser(
            view: WebView,
            callback: android.webkit.ValueCallback<Array<Uri>>,
            params: WebChromeClient.FileChooserParams,
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            return try {
                startActivityForResult(params.createIntent(), REQUEST_FILE_CHOOSER)
                true
            } catch (error: ActivityNotFoundException) {
                filePathCallback = null
                Toast.makeText(
                    this@WebViewModernShellActivity,
                    "No content picker available for file uploads",
                    Toast.LENGTH_SHORT
                ).show()
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val requested = request.resources.toList()
            val androidPermissions = requested.mapNotNull { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                        Manifest.permission.CAMERA

                    PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                        Manifest.permission.RECORD_AUDIO

                    else -> null
                }
            }
            if (androidPermissions.isEmpty()) {
                request.deny()
                return
            }
            val missing = androidPermissions.filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) {
                runOnUiThread { request.grant(requested.toTypedArray()) }
                return
            }
            pendingPermissionRequest = request
            requestPermissions(androidPermissions.toTypedArray(), REQUEST_WEB_PERMISSIONS)
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            pendingPermissionRequest = null
        }
    }

    private var pendingPermissionRequest: PermissionRequest? = null

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_WEB_PERMISSIONS) return
        val request = pendingPermissionRequest ?: return
        pendingPermissionRequest = null
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        runOnUiThread {
            if (granted) request.grant(request.resources) else request.deny()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            val callback = filePathCallback ?: return
            filePathCallback = null
            val result = if (resultCode == RESULT_OK) {
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            } else {
                null
            }
            callback.onReceiveValue(result ?: emptyArray())
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /* ================================================================== */
    /* JavaScript ↔ Android bridge                                         */
    /* ================================================================== */

    /**
     * Exposed to JavaScript as `window.AndroidBridge`. All methods run on a
     * dedicated bridge thread — UI work is hopped through runOnUiThread.
     */
    private inner class AndroidBridge {

        @JavascriptInterface
        fun toast(message: String) {
            runOnUiThread {
                Toast.makeText(
                    this@WebViewModernShellActivity,
                    message,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        @JavascriptInterface
        fun share(text: String, title: String) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_TITLE, title)
            }
            openExternally(Intent.createChooser(intent, title.ifBlank { "Share" }))
        }

        @JavascriptInterface
        fun vibrate(milliseconds: Long) {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(VIBRATOR_MANAGER_SERVICE)
                        as android.os.VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        milliseconds.coerceIn(0L, 5000L),
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(milliseconds.coerceIn(0L, 5000L))
            }
        }

        @JavascriptInterface
        fun keepScreenOn(enabled: Boolean) {
            runOnUiThread {
                if (enabled) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        @JavascriptInterface
        fun appInfo(): String = JSONObject().apply {
            put("appName", shellConfig.appName)
            put("orientation", shellConfig.orientation)
            put("fullscreen", shellConfig.fullscreen)
            put("startPath", shellConfig.startPath)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("shell", "webview_modern_shell")
            put("shellVersion", "2.0")
        }.toString()

        @JavascriptInterface
        fun exit() {
            runOnUiThread {
                finishAndRemoveTask()
            }
        }
    }

    /* ================================================================== */
    /* Appearance helpers                                                  */
    /* ================================================================== */

    private fun applyOrientation(raw: String) {
        requestedOrientation = when (raw.lowercase()) {
            "portrait" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun enterImmersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    /* ================================================================== */
    /* Utilities                                                           */
    /* ================================================================== */

    private fun startUrl(path: String): String = "https://$ASSET_DOMAIN/$path"

    private fun readAssetText(assetPath: String): String? = try {
        assets.open(assetPath).buffered().use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }
    } catch (error: Exception) {
        null
    }

    private fun openExternally(intent: Intent) {
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, "No app can handle this link", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "WebViewShell"

        /** The https origin WebViewAssetLoader serves `assets/` from. */
        const val ASSET_DOMAIN = "appassets.androidplatform.net"
        const val CONFIG_ASSET = "shell_config.json"

        private const val REQUEST_FILE_CHOOSER = 1001
        private const val REQUEST_WEB_PERMISSIONS = 1002

        /** Shown when the injected payload has no reachable start page. */
        private val ERROR_PAGE_HTML = """
            <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{font-family:sans-serif;background:#0B1B2B;color:#E6EEF6;display:flex;
            align-items:center;justify-content:center;height:100vh;margin:0;padding:24px;
            box-sizing:border-box}div{max-width:32em;text-align:center}
            h1{font-size:1.3em}p{line-height:1.5;opacity:.8}</style></head>
            <body><div><h1>App content not found</h1>
            <p>This shell was packaged without a reachable start page.
            Rebuild the app from Universal APK Factory with a project that
            contains an <b>index.html</b>.</p></div></body></html>
        """.trimIndent()
    }
}
