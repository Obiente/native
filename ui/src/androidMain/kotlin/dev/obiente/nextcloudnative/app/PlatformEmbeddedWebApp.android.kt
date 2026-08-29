package dev.obiente.nextcloudnative.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.net.Uri
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
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
import androidx.compose.runtime.key
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
    val initialOrigin = remember(initialUrl) { requireNotNull(embeddedWebOrigin(initialUrl)) }
    val authorization = remember(session.loginName, session.appPassword) {
        val credentials = "${session.loginName}:${session.appPassword}".encodeToByteArray()
        "Basic ${Base64.encodeToString(credentials, Base64.NO_WRAP)}"
    }
    val webSessionKey = remember(session.serverUrl, session.loginName, authorization, initialUrl) {
        listOf(
            session.serverUrl,
            session.loginName,
            publicContentSha256(authorization.encodeToByteArray()),
            initialUrl,
        ).joinToString("\u0000")
    }
    var progress by remember(webSessionKey) { mutableIntStateOf(0) }
    var webView by remember(webSessionKey) { mutableStateOf<WebView?>(null) }
    var canGoBack by remember(webSessionKey) { mutableStateOf(false) }
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

    DisposableEffect(webSessionKey) {
        onDispose {
            webView?.apply {
                stopLoading()
                webChromeClient = null
                webViewClient = WebViewClient()
                removeAllViews()
                destroy()
            }
            webView = null
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
            fileChooserCallback?.onReceiveValue(null)
            fileChooserCallback = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        key(webSessionKey) {
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
                        removeAllCookies {
                            flush()
                            embeddedWebView.post {
                                if (webView === embeddedWebView) {
                                    embeddedWebView.loadUrl(initialUrl, mapOf("Authorization" to authorization))
                                }
                            }
                        }
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
                            handler: android.webkit.HttpAuthHandler?,
                            host: String?,
                            realm: String?,
                        ) {
                            // WebView does not expose the challenged scheme and port here, so host
                            // equality cannot prove a complete origin. The initial request already
                            // carries a same-origin Authorization header; never disclose credentials
                            // through this ambiguous callback.
                            handler?.cancel()
                        }

                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: SslError?,
                        ) {
                            val trusted = error != null &&
                                embeddedWebOrigin(error.url) == initialOrigin &&
                                error.onlyReportsUntrustedIssuer() &&
                                AndroidServerCertificateTrust.isWebViewCertificateTrusted(
                                    context = context,
                                    serverUrl = error.url,
                                    certificate = error.certificate,
                                )
                            if (trusted) handler?.proceed() else handler?.cancel()
                        }
                    }
                    }
                },
                modifier = Modifier.fillMaxSize().semantics {
                    contentDescription = "Embedded web app"
                },
            )
        }
        if (progress in 0..99) {
            LinearProgressIndicator(progress = { progress / 100f })
        }
    }
}

private fun SslError.onlyReportsUntrustedIssuer(): Boolean =
    primaryError == SslError.SSL_UNTRUSTED &&
        !hasError(SslError.SSL_DATE_INVALID) &&
        !hasError(SslError.SSL_EXPIRED) &&
        !hasError(SslError.SSL_IDMISMATCH) &&
        !hasError(SslError.SSL_INVALID) &&
        !hasError(SslError.SSL_NOTYETVALID)

private fun embeddedWebOrigin(value: String): String? {
    val parsed = Uri.parse(value)
    val scheme = parsed.scheme?.lowercase() ?: return null
    val host = parsed.host?.lowercase() ?: return null
    val port = parsed.port
    return "$scheme://$host${if (port >= 0) ":$port" else ""}"
}
