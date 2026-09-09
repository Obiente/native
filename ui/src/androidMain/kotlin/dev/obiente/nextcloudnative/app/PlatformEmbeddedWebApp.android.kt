package dev.obiente.nextcloudnative.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebStorage
import java.io.ByteArrayInputStream
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal actual fun PlatformEmbeddedNextcloudWebApp(
    session: NextcloudSession,
    initialUrl: String,
    onExit: () -> Unit,
    onRetrySession: () -> Unit,
    modifier: Modifier,
) {
    val initialOrigin = remember(initialUrl) { requireNotNull(embeddedWebOrigin(initialUrl)) }
    val navigation = remember(session.serverUrl, initialUrl) {
        OfficeEditorNavigation(session.serverUrl, initialUrl)
    }
    val exitEditor by rememberUpdatedState(onExit)
    val webSessionKey = remember(session.serverUrl, session.loginName, initialUrl) {
        listOf(
            session.serverUrl,
            session.loginName,
            initialUrl,
        ).joinToString("\u0000")
    }
    var progress by remember(webSessionKey) { mutableIntStateOf(0) }
    var webView by remember(webSessionKey) { mutableStateOf<WebView?>(null) }
    var failure by remember(webSessionKey) { mutableStateOf<String?>(null) }
    var navigationNotice by remember(webSessionKey) { mutableStateOf(false) }
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        fileChooserCallback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data),
        )
        fileChooserCallback = null
    }

    fun loadInitialPage(target: WebView) {
        failure = null
        progress = 0
        target.clearCache(true)
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            target.post {
                if (webView === target) {
                    target.loadUrl(initialUrl)
                }
            }
        }
    }

    BackHandler { exitEditor() }

    DisposableEffect(webSessionKey) {
        onDispose {
            webView?.apply {
                stopLoading()
                clearCache(true)
                clearHistory()
                webChromeClient = null
                webViewClient = WebViewClient()
                removeAllViews()
                destroy()
            }
            webView = null
            WebStorage.getInstance().deleteAllData()
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
                        settings.javaScriptCanOpenWindowsAutomatically = false
                        settings.setSupportMultipleWindows(true)
                        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        settings.userAgentString += " NextcloudNativeEmbedded/1"
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(embeddedWebView, true)
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: Message?,
                            ): Boolean {
                                navigationNotice = true
                                return false
                            }

                            override fun onCloseWindow(window: WebView?) { exitEditor() }

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
                                if (!navigation.allowsMainFrame(url)) {
                                    view?.stopLoading()
                                    view?.visibility = View.INVISIBLE
                                    failure = "This link leaves the document editor. Return to the native document browser."
                                    return
                                }
                                progress = 0
                                failure = null
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                progress = 100
                                CookieManager.getInstance().flush()
                            }

                            override fun onPageCommitVisible(view: WebView?, url: String?) {
                                if (!navigation.allowsMainFrame(url)) {
                                    view?.visibility = View.INVISIBLE
                                    failure = "This link leaves the document editor. Return to the native document browser."
                                }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                if (request?.isForMainFrame == true &&
                                    !navigation.allowsMainFrame(request.url.toString())
                                ) {
                                    view?.post {
                                        failure = "This link leaves the document editor. Return to the native document browser."
                                    }
                                    return WebResourceResponse(
                                        "text/plain", "UTF-8", 403, "Blocked", emptyMap(),
                                        ByteArrayInputStream(ByteArray(0)),
                                    )
                                }
                                return null
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
                                val allowed = request != null && navigation.allowsNavigation(
                                    request.url.toString(), request.isForMainFrame, request.hasGesture(),
                                )
                                if (!allowed) navigationNotice = true
                                return !allowed
                            }

                            override fun onReceivedHttpAuthRequest(
                                view: WebView?,
                                handler: android.webkit.HttpAuthHandler?,
                                host: String?,
                                realm: String?,
                            ) {
                                handler?.cancel()
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (request?.isForMainFrame == true) {
                                    view?.stopLoading()
                                    failure = "The embedded Office page could not be loaded."
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                errorResponse: WebResourceResponse?,
                            ) {
                                if (request?.isForMainFrame == true && errorResponse?.statusCode?.let { it >= 400 } == true) {
                                    view?.stopLoading()
                                    failure = "Nextcloud returned HTTP ${errorResponse.statusCode} while loading Office."
                                }
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
                                if (trusted) {
                                    handler?.proceed()
                                } else {
                                    handler?.cancel()
                                    view?.stopLoading()
                                    failure = "The Office certificate could not be verified."
                                }
                            }
                        }
                        loadInitialPage(this)
                    }
                },
                modifier = Modifier.fillMaxSize().semantics {
                    contentDescription = "Office document editor"
                },
            )
        }
        if (progress in 0..99 && failure == null) {
            LinearProgressIndicator(progress = { progress / 100f })
        }
        if (navigationNotice && failure == null) {
            Column(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .background(MaterialTheme.colorScheme.surface).padding(16.dp),
            ) {
                Text("This link is outside the editor. Use Back to choose another file.")
                TextButton(onClick = { navigationNotice = false }) { Text("Keep editing") }
            }
        }
        failure?.let { message ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Office could not be opened", style = MaterialTheme.typography.titleLarge)
                Text(
                    message,
                    modifier = Modifier.padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = onRetrySession,
                    modifier = Modifier.padding(top = 24.dp),
                ) {
                    Text("Retry")
                }
                TextButton(onClick = onExit) { Text("Back") }
            }
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
    return canonicalEmbeddedWebOrigin(parsed.scheme, parsed.host, parsed.port)
}

internal fun canonicalEmbeddedWebOrigin(schemeValue: String?, hostValue: String?, port: Int): String? {
    val scheme = schemeValue?.lowercase() ?: return null
    val host = hostValue?.lowercase() ?: return null
    if (scheme !in setOf("http", "https") || host.isBlank() || port !in -1..65535) return null
    val effectivePort = when {
        port >= 0 -> port
        scheme == "https" -> 443
        scheme == "http" -> 80
        else -> return null
    }
    return "$scheme://$host:$effectivePort"
}
