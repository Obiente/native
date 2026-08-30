package dev.obiente.nextcloudnative.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
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
    authenticateWithSession: Boolean,
    onExit: () -> Unit,
    onRetrySession: (() -> Unit)?,
    modifier: Modifier,
) {
    val initialOrigin = remember(initialUrl) { requireNotNull(embeddedWebOrigin(initialUrl)) }
    val authorization = remember(session.loginName, session.appPassword, authenticateWithSession) {
        if (authenticateWithSession) {
            val credentials = "${session.loginName}:${session.appPassword}".encodeToByteArray()
            "Basic ${Base64.encodeToString(credentials, Base64.NO_WRAP)}"
        } else {
            null
        }
    }
    val webSessionKey = remember(session.serverUrl, session.loginName, authorization, initialUrl) {
        listOf(
            session.serverUrl,
            session.loginName,
            authorization?.let { publicContentSha256(it.encodeToByteArray()) }.orEmpty(),
            initialUrl,
        ).joinToString("\u0000")
    }
    var progress by remember(webSessionKey) { mutableIntStateOf(0) }
    var webView by remember(webSessionKey) { mutableStateOf<WebView?>(null) }
    var canGoBack by remember(webSessionKey) { mutableStateOf(false) }
    var failure by remember(webSessionKey) { mutableStateOf<String?>(null) }
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
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            target.post {
                if (webView === target) {
                    val headers = authorization?.let { mapOf("Authorization" to it) }.orEmpty()
                    target.loadUrl(initialUrl, headers)
                }
            }
        }
    }

    BackHandler {
        val activeWebView = webView
        if (failure == null && activeWebView?.canGoBack() == true) activeWebView.goBack() else onExit()
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
                        settings.userAgentString += " NextcloudNativeEmbedded/1"
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
                                failure = null
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                progress = 100
                                canGoBack = view?.canGoBack() == true
                                CookieManager.getInstance().flush()
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean {
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
                    contentDescription = "Embedded Office web app"
                },
            )
        }
        if (progress in 0..99 && failure == null) {
            LinearProgressIndicator(progress = { progress / 100f })
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
                    onClick = {
                        if (onRetrySession != null) {
                            onRetrySession()
                        } else {
                            webView?.let(::loadInitialPage)
                        }
                    },
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
