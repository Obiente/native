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
    val result = downloadJvmDetachedFile(
        client = client,
        session = session,
        url = url,
        output = output,
        maximumBytes = maximumBytes,
        userAgent = userAgent,
        failureMessage = failureMessage,
        limitMessage = limitMessage,
        accept = accept,
        requestHeaders = requestHeaders,
        handoffEtag = handoffEtag,
        validateResponseEtag = validateResponseEtag,
        acceptOcEtag = false,
        onNetworkFailure = onNetworkFailure,
    )
    return DesktopDetachedDownload(result.byteCount, result.etag)
}
