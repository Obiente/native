package dev.obiente.nextcloudnative.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun PlatformEmbeddedNextcloudWebApp(
    session: NextcloudSession,
    initialUrl: String,
    onExit: () -> Unit,
    modifier: Modifier,
) {
    val initialOrigin = remember(initialUrl) { embeddedWebOrigin(initialUrl) }
    val authorization = remember(session.loginName, session.appPassword) {
        val credentials = "${session.loginName}:${session.appPassword}".encodeToByteArray()
        "Basic ${Base64.encodeToString(credentials, Base64.NO_WRAP)}"
    }
    var progress by remember(initialUrl) { mutableIntStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember(initialUrl) { mutableStateOf(false) }
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        fileChooserCallback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
        fileChooserCallback = null
    }

    BackHandler {
        val activeWebView = webView
        if (activeWebView?.canGoBack() == true) activeWebView.goBack() else onExit()
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                webChromeClient = null
                webViewClient = WebViewClient()
                removeAllViews()
                destroy()
            }
            webView = null
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    val embeddedWebView = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.userAgentString = settings.userAgentString + " NextcloudNativeEmbedded/1"
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(embeddedWebView, true)
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress.coerceIn(0, 100)
                        }

                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?,
                        ): Boolean {
                            val callback = filePathCallback ?: return false
                            val intent = runCatching { fileChooserParams?.createIntent() }.getOrNull()
                                ?: return false
                            fileChooserCallback?.onReceiveValue(null)
                            fileChooserCallback = callback
                            return runCatching {
                                fileChooserLauncher.launch(intent)
                                true
                            }.getOrElse {
                                fileChooserCallback?.onReceiveValue(null)
                                fileChooserCallback = null
                                false
                            }
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            progress = 0
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            progress = 100
                            canGoBack = view?.canGoBack() == true
                            CookieManager.getInstance().flush()
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val scheme = request?.url?.scheme?.lowercase()
                            if (scheme !in setOf("http", "https", "about", "blob", "data")) return true
                            return request?.isForMainFrame == true &&
                                scheme in setOf("http", "https") &&
                                embeddedWebOrigin(request.url.toString()) != initialOrigin
                        }

                        override fun onReceivedHttpAuthRequest(
                            view: WebView?,
                            handler: HttpAuthHandler?,
                            host: String?,
                            realm: String?,
                        ) {
                            val requestOrigin = host?.let { candidate ->
                                val scheme = Uri.parse(initialUrl).scheme ?: return@let null
                                val port = Uri.parse(initialUrl).port
                                "$scheme://${candidate.lowercase()}${if (port >= 0) ":$port" else ""}"
                            }
                            if (handler != null && requestOrigin == initialOrigin) {
                                handler.proceed(session.loginName, session.appPassword)
                            } else {
                                handler?.cancel()
                            }
                        }
                    }
                    loadUrl(initialUrl, mapOf("Authorization" to authorization))
                }
            },
            update = { view ->
                if (view.url == null) {
                    view.loadUrl(initialUrl, mapOf("Authorization" to authorization))
                }
            },
            modifier = Modifier.fillMaxSize().semantics {
                contentDescription = "Embedded web app"
            },
        )
        if (progress in 0..99) {
            LinearProgressIndicator(progress = { progress / 100f })
        }
    }
}

private fun embeddedWebOrigin(value: String): String? {
    val parsed = Uri.parse(value)
    val scheme = parsed.scheme?.lowercase() ?: return null
    val host = parsed.host?.lowercase() ?: return null
    val port = parsed.port
    return "$scheme://$host${if (port >= 0) ":$port" else ""}"
}
