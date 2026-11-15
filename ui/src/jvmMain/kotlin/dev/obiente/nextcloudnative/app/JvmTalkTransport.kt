package dev.obiente.nextcloudnative.app

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** A client whose read timeout outlasts the longest Talk long poll this app requests. */
fun OkHttpClient.forTalkReads(): OkHttpClient = newBuilder()
    .readTimeout(TALK_POLL_WAIT_SECONDS + TALK_READ_TIMEOUT_MARGIN_SECONDS, TimeUnit.SECONDS)
    .build()

/**
 * Executes a reviewed Talk read for Android and desktop.
 *
 * The network call is cancelled with the coroutine, so leaving a conversation or hiding the app
 * does not keep a long poll open. Writes do not use this path: they go through the platform's
 * account mutation transport.
 */
suspend fun executeJvmTalkRead(
    client: OkHttpClient,
    session: NextcloudSession,
    request: TalkTransportRequest,
    userAgent: String,
    onNetworkFailure: (Throwable) -> Unit = {},
): TalkTransportResponse {
    require(request.isRead) { "Talk writes use the account mutation transport." }
    val httpRequest = NextcloudAuthenticatedRequestPolicy(session, userAgent)
        .requestBuilder(request.url(session.serverUrl))
        .header("Accept", "application/json")
        .header("OCS-APIRequest", "true")
        .get()
        .build()
    return executeCancellableNextcloudAuthenticatedRequest(client, httpRequest, onNetworkFailure) { response, shouldContinue ->
        val limit = if (response.isSuccessful) request.maximumResponseBytes else TALK_ERROR_RESPONSE_BYTES
        val body = response.body
        if (body.contentLength() > limit) throw NextcloudResponseTooLargeException(limit, response.code)
        val output = ByteArrayOutputStream()
        body.byteStream().copyBoundedNetworkResponseTo(
            output = output,
            maxBytes = limit,
            onLimitExceeded = { throw NextcloudResponseTooLargeException(limit, response.code) },
            onNetworkReadFailure = onNetworkFailure,
            shouldContinue = shouldContinue,
        )
        TalkTransportResponse(response.code, output.toByteArray(), response.header("X-Chat-Last-Given")?.toLongOrNull())
    }
}

private const val TALK_READ_TIMEOUT_MARGIN_SECONDS = 20L
private const val TALK_ERROR_RESPONSE_BYTES = 64L * 1024L
