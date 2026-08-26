package dev.obiente.nextcloudnative.app

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal suspend fun downloadDesktopDetachedFile(
    client: OkHttpClient,
    session: NextcloudSession,
    url: String,
    output: FileOutputStream,
    maximumBytes: Long,
    userAgent: String,
    failureMessage: (Int) -> String,
    limitMessage: String,
    accept: String = "application/octet-stream",
    requestHeaders: Map<String, String> = emptyMap(),
    handoffEtag: String? = null,
    validateResponseEtag: (String?) -> Unit = {},
    onNetworkFailure: (Long, JvmNetworkRequestAttempt, Throwable) -> Unit,
): DesktopDetachedDownload = withContext(Dispatchers.IO) {
    require(maximumBytes > 0L)
    val authorization = Base64.getEncoder().encodeToString(
        "${session.loginName}:${session.appPassword}".toByteArray(StandardCharsets.UTF_8),
    )
    val started = System.nanoTime()
    val attempt = JvmNetworkRequestAttempt()
    val request = Request.Builder()
        .url(url)
        .get()
        .tag(JvmNetworkRequestAttempt::class.java, attempt)
        .header("Accept", accept)
        .header("User-Agent", userAgent)
        .header("Authorization", "Basic $authorization")
        .apply { requestHeaders.forEach { (name, value) -> header(name, value) } }
        .build()
    val response = try {
        client.newCall(request).execute()
    } catch (failure: Throwable) {
        onNetworkFailure(started, attempt, failure)
        throw failure
    }
    response.use {
        check(isFullDetachedFileResponse(response.code)) { failureMessage(response.code) }
        val body = response.body
        val contentLength = body.contentLength()
        check(contentLength == -1L || contentLength <= maximumBytes) { limitMessage }
        val copied = body.byteStream().copyBoundedNetworkResponseTo(
            output = output,
            maxBytes = maximumBytes,
            onLimitExceeded = { error(limitMessage) },
            onNetworkReadFailure = { failure -> onNetworkFailure(started, attempt, failure) },
        )
        val responseEtag = response.header("ETag")
        validateResponseEtag(responseEtag)
        DesktopDetachedDownload(copied, handoffEtag ?: responseEtag)
    }
}
