package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.JvmNetworkRequestAttempt
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.copyBoundedNetworkResponseTo
import dev.obiente.nextcloudnative.app.executeCancellableJvmHttpCall
import dev.obiente.nextcloudnative.app.isFullDetachedFileResponse
import dev.obiente.nextcloudnative.app.nextcloudBasicAuthorization
import java.io.FileOutputStream
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
): AndroidDetachedDownload {
    require(maximumBytes > 0L)
    val started = System.nanoTime()
    val attempt = JvmNetworkRequestAttempt()
    val request = Request.Builder()
        .url(url)
        .get()
        .tag(JvmNetworkRequestAttempt::class.java, attempt)
        .header("Accept", accept)
        .header("User-Agent", userAgent)
        .header("Authorization", nextcloudBasicAuthorization(session))
        .apply { requestHeaders.forEach { (name, value) -> header(name, value) } }
        .build()
    val call = client.newCall(request)
    return executeCancellableJvmHttpCall(client, call) { activeCall, shouldContinue ->
        val response = try {
            activeCall.execute()
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
                    shouldContinue = shouldContinue,
                ),
                mimeType = body.contentType()?.toString(),
            )
        }
    }
}
