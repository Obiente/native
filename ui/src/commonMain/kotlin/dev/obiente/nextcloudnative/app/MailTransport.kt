package dev.obiente.nextcloudnative.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Classifies Mail responses once, so callers handle typed outcomes instead of HTTP details.
 *
 * Reads: a transport failure is Offline, an unreadable body is Malformed. Writes: a status Mail
 * documents as a decision (4xx) is a rejection; a 5xx, a transport failure after the request may
 * have left, or an unreadable success is unknown, because the change may have been stored.
 */
internal class MailTransport(
    private val execute: suspend (NextcloudApiRequest) -> NextcloudApiResponse,
    private val uploadFile: suspend (NextcloudMultipartUploadRequest) -> NextcloudApiResponse,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun <T> read(request: NextcloudApiRequest, parse: (ByteArray) -> T): MailReadOutcome<T> {
        val response = try {
            execute(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MailReadOutcome.Failed(MailFailure(MailFailureKind.Offline))
        }
        mailFailureForStatus(response.status)?.let { return MailReadOutcome.Failed(it) }
        return try {
            MailReadOutcome.Loaded(withContext(parseDispatcher) { parse(response.body) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: MailProtocolException) {
            MailReadOutcome.Failed(MailFailure(MailFailureKind.Malformed))
        } catch (_: IllegalArgumentException) {
            MailReadOutcome.Failed(MailFailure(MailFailureKind.Malformed))
        }
    }

    suspend fun <T> write(request: NextcloudApiRequest, parse: (ByteArray) -> T): MailWriteOutcome<T> {
        val response = try {
            execute(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MailWriteOutcome.Unknown
        }
        return classifyWrite(response, parse)
    }

    suspend fun <T> upload(request: NextcloudMultipartUploadRequest, parse: (ByteArray) -> T): MailWriteOutcome<T> {
        val response = try {
            uploadFile(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return MailWriteOutcome.Unknown
        }
        return classifyWrite(response, parse)
    }

    private suspend fun <T> classifyWrite(response: NextcloudApiResponse, parse: (ByteArray) -> T): MailWriteOutcome<T> {
        if (response.status >= 500 || response.status !in 200..499) return MailWriteOutcome.Unknown
        mailFailureForStatus(response.status)?.let { return MailWriteOutcome.Rejected(it, response.body) }
        return try {
            MailWriteOutcome.Confirmed(withContext(parseDispatcher) { parse(response.body) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: MailProtocolException) {
            MailWriteOutcome.Unknown
        } catch (_: IllegalArgumentException) {
            MailWriteOutcome.Unknown
        }
    }
}

/** Parser for writes whose success response carries nothing the client needs. */
internal val ignoreMailBody: (ByteArray) -> Unit = {}
