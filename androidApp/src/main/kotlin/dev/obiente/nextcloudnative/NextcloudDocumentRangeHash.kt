package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudAuthenticatedRedirectException
import dev.obiente.nextcloudnative.app.NextcloudAuthenticatedRequestPolicy
import dev.obiente.nextcloudnative.app.NextcloudSession
import dev.obiente.nextcloudnative.app.buildNextcloudFileUrl
import dev.obiente.nextcloudnative.app.executeNextcloudAuthenticatedRequest
import dev.obiente.nextcloudnative.app.hashExactJvmFileSyncSlice
import dev.obiente.nextcloudnative.app.isExactHttpByteContentRange
import java.io.ByteArrayInputStream

internal fun NextcloudDocumentWebDav.readFileRangeHash(
    session: NextcloudSession,
    userId: String,
    path: String,
    expectedEtag: String,
    expectedBytes: Long,
    offset: Long,
    length: Int,
    cancellation: DocumentRequestCancellation = NoDocumentRequestCancellation,
): String {
    require(expectedEtag.isNotBlank())
    require(offset >= 0L && length >= 0 && offset <= expectedBytes - length)
    if (length == 0) {
        require(expectedBytes == 0L)
        return hashExactJvmFileSyncSlice(ByteArrayInputStream(byteArrayOf()), 0)
    }
    val endInclusive = offset + length - 1L
    val request = NextcloudAuthenticatedRequestPolicy(session, RANGE_HASH_USER_AGENT)
        .requestBuilder(buildNextcloudFileUrl(session.serverUrl, userId, path))
        .header("If-Match", expectedEtag)
        .header("Range", "bytes=$offset-$endInclusive")
        .get()
        .build()
    cancellation.throwIfCancelled()
    val requestClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    try {
        return executeNextcloudAuthenticatedRequest(
            client = requestClient,
            initialRequest = request,
            executeCall = { call ->
                cancellation.setOnCancelAction(call::cancel)
                call.execute()
            },
        ) { response ->
            check(response.code == 206) {
                "The server did not honor bounded content verification (HTTP ${response.code})."
            }
            require(isExactHttpByteContentRange(response.header("Content-Range"), offset, endInclusive)) {
                "The server returned a different content-verification range."
            }
            response.header("ETag")?.let { returned ->
                require(returned == expectedEtag) { "The server file changed during content verification." }
            }
            hashExactJvmFileSyncSlice(response.body.byteStream(), length, requireExhausted = true)
        }
    } catch (failure: NextcloudAuthenticatedRedirectException) {
        throw failure.toDocumentException("verify document range")
    } finally {
        cancellation.setOnCancelAction(null)
        cancellation.throwIfCancelled()
    }
}

private const val RANGE_HASH_USER_AGENT = "Nextcloud-Native/0.1.0 (Android DocumentsProvider)"
