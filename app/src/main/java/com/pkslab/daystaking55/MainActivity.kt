package com.pkslab.daystaking55

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorLayout: View
    private lateinit var fullscreenContainer: FrameLayout

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private var exitDialog: AlertDialog? = null
    private var hasError = false

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            pendingFileCallback?.onReceiveValue(uris)
            pendingFileCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Hardware volume keys control media volume (important for website sounds)
        volumeControlStream = AudioManager.STREAM_MUSIC

        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorLayout = findViewById(R.id.errorLayout)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        findViewById<Button>(R.id.retryButton).setOnClickListener { retryLoad() }

        setupWebView()
        setupBackHandling()

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(HOME_URL)
        }
    }

    // ---------------------------------------------------------------------------------------
    // WebView setup
    // ---------------------------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(Color.parseColor("#0B1220"))

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Lets <audio>, <video> and Web Audio play without a prior tap (website sound/animation)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100
        }

        // Native share sheet for the website's Share buttons (navigator.share is missing in WebView)
        webView.addJavascriptInterface(ShareBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val uri = request?.url ?: return false
                return if (shouldOpenExternally(uri)) {
                    openExternal(uri)
                    true
                } else {
                    false
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                hasError = false
                progressBar.visibility = View.VISIBLE
                injectShareSupport(view, url)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectShareSupport(view, url)
                progressBar.visibility = View.GONE
                if (!hasError) {
                    errorLayout.visibility = View.GONE
                    if (customView == null) webView.visibility = View.VISIBLE
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) showError()
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                // On API 23+ the WebResourceRequest overload above handles this.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M && failingUrl == view?.url) {
                    showError()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }

            // target="_blank" / window.open(): external links go to the browser,
            // internal links are loaded in the same WebView.
            @SuppressLint("SetJavaScriptEnabled")
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popup = WebView(this@MainActivity)
                popup.settings.javaScriptEnabled = true
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return true
                        if (uri.scheme.equals("about", ignoreCase = true)) return false
                        if (shouldOpenExternally(uri)) {
                            openExternal(uri)
                        } else {
                            webView.loadUrl(uri.toString())
                        }
                        v?.post { v.destroy() }
                        return true
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null) return
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.INVISIBLE
                setImmersive(true)
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                pendingFileCallback?.onReceiveValue(null)
                pendingFileCallback = filePathCallback
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    pendingFileCallback = null
                    false
                }
            }
        }

        // Downloads are handed to the system / browser
        webView.setDownloadListener { url, _, _, _, _ -> openExternal(Uri.parse(url)) }
    }

    // ---------------------------------------------------------------------------------------
    // Native share (makes the website's "Share Link" / "Share App" buttons work)
    // ---------------------------------------------------------------------------------------

    private fun injectShareSupport(view: WebView?, url: String?) {
        val uri = url?.let { Uri.parse(it) } ?: return
        if (!isInternal(uri)) return
        view?.evaluateJavascript(SHARE_POLYFILL, null)
    }

    inner class ShareBridge {
        @JavascriptInterface
        fun share(title: String?, text: String?, url: String?) {
            val body = listOfNotNull(
                text?.takeIf { it.isNotBlank() },
                url?.takeIf { it.isNotBlank() }
            ).joinToString("\n")
            if (body.isBlank()) return
            runOnUiThread {
                try {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        if (!title.isNullOrBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
                        putExtra(Intent.EXTRA_TEXT, body)
                    }
                    startActivity(Intent.createChooser(send, null))
                } catch (e: Exception) {
                    toastNoApp()
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // External links
    // ---------------------------------------------------------------------------------------

    private fun isInternal(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host == HOST || host.endsWith(".$HOST")
    }

    private fun shouldOpenExternally(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            null -> false
            "http", "https" -> !isInternal(uri)
            "about", "blob", "data", "javascript", "file" -> false
            else -> true // tel:, mailto:, sms:, whatsapp:, tg:, intent:, market:, upi: ...
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            if (uri.scheme.equals("intent", ignoreCase = true)) {
                val intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                intent.component = null
                intent.selector = null
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    val fallback = intent.getStringExtra("browser_fallback_url")
                    if (fallback != null) {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)))
                    } else {
                        toastNoApp()
                    }
                }
            } else {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
        } catch (e: Exception) {
            toastNoApp()
        }
    }

    private fun toastNoApp() {
        Toast.makeText(this, R.string.no_app_found, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------------------------
    // Back button: go back inside the site, then ask before closing the app
    // ---------------------------------------------------------------------------------------

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    webView.canGoBack() -> webView.goBack()
                    else -> showExitDialog()
                }
            }
        })
    }

    private fun showExitDialog() {
        if (exitDialog?.isShowing == true) return
        exitDialog = AlertDialog.Builder(this)
            .setTitle(R.string.exit_title)
            .setMessage(R.string.exit_message)
            .setPositiveButton(R.string.exit_yes) { _, _ -> finish() }
            .setNegativeButton(R.string.exit_no, null)
            .create()
            .also { it.show() }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun showError() {
        hasError = true
        progressBar.visibility = View.GONE
        webView.visibility = View.INVISIBLE
        errorLayout.visibility = View.VISIBLE
    }

    private fun retryLoad() {
        val current = webView.url
        if (current.isNullOrEmpty() || current.startsWith("about:") || current.startsWith("data:")) {
            webView.loadUrl(HOME_URL)
        } else {
            webView.loadUrl(current)
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        if (!hasError) webView.visibility = View.VISIBLE
        setImmersive(false)
    }

    private fun setImmersive(enable: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enable) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        // Stops page audio/animations while the app is in the background
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        exitDialog?.dismiss()
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val HOME_URL = "https://aitradingpro.top/"
        private const val HOST = "aitradingpro.top"

        // Adds navigator.share / navigator.canShare, backed by the Android share sheet.
        private const val SHARE_POLYFILL = """
            (function () {
              if (window.__dsShareInstalled || !window.AndroidBridge) return;
              window.__dsShareInstalled = true;
              var share = function (data) {
                data = data || {};
                try {
                  AndroidBridge.share(String(data.title || ''), String(data.text || ''), String(data.url || ''));
                  return Promise.resolve();
                } catch (e) { return Promise.reject(e); }
              };
              var canShare = function () { return true; };
              try { Object.defineProperty(navigator, 'share', { value: share, configurable: true, writable: true }); }
              catch (e) { try { navigator.share = share; } catch (e2) {} }
              try { Object.defineProperty(navigator, 'canShare', { value: canShare, configurable: true, writable: true }); }
              catch (e) { try { navigator.canShare = canShare; } catch (e2) {} }
            })();
        """
    }
}
