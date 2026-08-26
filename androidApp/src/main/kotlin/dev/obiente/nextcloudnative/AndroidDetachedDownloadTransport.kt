package dev.obiente.nextcloudnative

import android.util.Base64
import dev.obiente.nextcloudnative.app.JvmNetworkRequestAttempt
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.copyBoundedNetworkResponseTo
import dev.obiente.nextcloudnative.app.isFullDetachedFileResponse
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal suspend fun downloadAndroidDetachedFile(
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
    onNetworkFailure: (Long, JvmNetworkRequestAttempt, Throwable) -> Unit,
): AndroidDetachedDownload = withContext(Dispatchers.IO) {
    require(maximumBytes > 0L)
    val authorization = Base64.encodeToString(
        "${session.loginName}:${session.appPassword}".toByteArray(StandardCharsets.UTF_8),
        Base64.NO_WRAP,
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
        AndroidDetachedDownload(
            byteCount = body.byteStream().copyBoundedNetworkResponseTo(
                output = output,
                maxBytes = maximumBytes,
                onLimitExceeded = { error(limitMessage) },
                onNetworkReadFailure = { failure -> onNetworkFailure(started, attempt, failure) },
            ),
            mimeType = body.contentType()?.toString(),
        )
    }
}
