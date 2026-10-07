package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.JvmNetworkRequestAttempt
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.downloadJvmDetachedFile
import java.io.FileOutputStream
import okhttp3.OkHttpClient

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
    handoffEtag: String? = null,
    validateResponseEtag: (String?) -> Unit = {},
    onNetworkFailure: (Long, JvmNetworkRequestAttempt, Throwable) -> Unit,
): AndroidDetachedDownload {
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
        acceptOcEtag = true,
        onNetworkFailure = onNetworkFailure,
    )
    return AndroidDetachedDownload(result.byteCount, result.mimeType, result.etag)
}
