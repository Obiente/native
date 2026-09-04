package dev.obiente.nextcloudnative.app

import java.io.FileOutputStream
import okhttp3.OkHttpClient

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
): DesktopDetachedDownload {
    require(maximumBytes > 0L)
    val started = System.nanoTime()
    val attempt = JvmNetworkRequestAttempt()
    val request = NextcloudAuthenticatedRequestPolicy(session, userAgent)
        .requestBuilder(url)
        .get()
        .tag(JvmNetworkRequestAttempt::class.java, attempt)
        .header("Accept", accept)
        .apply { requestHeaders.forEach { (name, value) -> header(name, value) } }
        .build()
    return executeCancellableNextcloudAuthenticatedRequest(
        client = client,
        initialRequest = request,
        onNetworkFailure = { failure -> onNetworkFailure(started, attempt, failure) },
    ) { response, shouldContinue ->
        check(isFullDetachedFileResponse(response.code)) { failureMessage(response.code) }
        val body = response.body
        val contentLength = body.contentLength()
        check(contentLength == -1L || contentLength <= maximumBytes) { limitMessage }
        val copied = body.byteStream().copyBoundedNetworkResponseTo(
            output = output,
            maxBytes = maximumBytes,
            onLimitExceeded = { error(limitMessage) },
            onNetworkReadFailure = { failure -> onNetworkFailure(started, attempt, failure) },
            shouldContinue = shouldContinue,
        )
        val responseEtag = response.header("ETag")
        validateResponseEtag(responseEtag)
        DesktopDetachedDownload(copied, handoffEtag ?: responseEtag)
    }
}
