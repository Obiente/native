package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.JvmNetworkRequestAttempt
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.copyBoundedNetworkResponseTo
import dev.obiente.nextcloudnative.app.isFullDetachedFileResponse
import java.io.FileOutputStream
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Credentials
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
    val authorization = Credentials.basic(session.loginName, session.appPassword)
    val started = System.nanoTime()
    val attempt = JvmNetworkRequestAttempt()
    val request = Request.Builder()
        .url(url)
        .get()
        .tag(JvmNetworkRequestAttempt::class.java, attempt)
        .header("Accept", accept)
        .header("User-Agent", userAgent)
        .header("Authorization", authorization)
        .apply { requestHeaders.forEach { (name, value) -> header(name, value) } }
        .build()
    return suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        val execute = Runnable {
            val result = runCatching {
                val response = try {
                    call.execute()
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
            continuation.resumeWith(result)
        }
        runCatching { client.dispatcher.executorService.execute(execute) }
            .onFailure { failure ->
                continuation.resumeWith(Result.failure(failure))
            }
    }
}
