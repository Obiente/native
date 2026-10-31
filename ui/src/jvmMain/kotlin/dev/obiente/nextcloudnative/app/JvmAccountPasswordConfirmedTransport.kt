package dev.obiente.nextcloudnative.app

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Sends one password-confirmed OCS request for Android and desktop.
 *
 * The [client] must carry the platform's certificate trust. This transport removes cookies and
 * automatic retries, so the password is sent once, to the account origin only, and no server
 * session outlives the request. The password string exists only while the request is built.
 */
class JvmAccountPasswordConfirmedTransport(
    client: OkHttpClient,
    private val userAgent: String,
) : AccountPasswordConfirmedTransport {
    private val client = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun execute(
        session: NextcloudSession,
        request: NextcloudApiRequest,
        secret: AccountPasswordConfirmationSecret,
    ): PasswordConfirmedApiResult {
        require(request.ocsApiRequest && request.multipartBody == null) {
            "Password confirmation is limited to small OCS requests."
        }
        require(request.method != NextcloudApiMethod.GET) { "Password confirmation is only for writes." }
        val safeRequest = request.requireSafe()
        val httpRequest = secret.read { password ->
            NextcloudAuthenticatedRequestPolicy(session.serverUrl, session.loginName, String(password), userAgent)
        }.requestBuilder(buildNextcloudApiUrl(session.serverUrl, safeRequest))
            .header("Accept", "application/json")
            .header("OCS-APIRequest", "true")
            .method(
                safeRequest.method.name,
                (safeRequest.body ?: byteArrayOf()).toRequestBody(safeRequest.contentType?.toMediaType()),
            )
            .build()
        var exchangeStarted = false
        return try {
            executeCancellableNextcloudAuthenticatedRequest(client, httpRequest, onNetworkFailure = {}) { response, shouldContinue ->
                exchangeStarted = true
                val limit = if (response.isSuccessful) safeRequest.maximumResponseBytes else CONFIRMED_ERROR_RESPONSE_BYTES
                val body = response.body
                if (body.contentLength() > limit) throw NextcloudResponseTooLargeException(limit, response.code)
                val output = ByteArrayOutputStream()
                body.byteStream().copyBoundedNetworkResponseTo(
                    output = output,
                    maxBytes = limit,
                    onLimitExceeded = { throw NextcloudResponseTooLargeException(limit, response.code) },
                    onNetworkReadFailure = {},
                    shouldContinue = shouldContinue,
                )
                PasswordConfirmedApiResult.Delivered(
                    NextcloudApiResponse(response.code, output.toByteArray(), response.header("Content-Type"), null),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            if (!exchangeStarted && failure.isBeforeRequestWasSent()) {
                PasswordConfirmedApiResult.NotSent
            } else {
                PasswordConfirmedApiResult.Ambiguous
            }
        } catch (_: Exception) {
            // Fault boundary: an oversized body or rejected redirect follows a delivered request.
            PasswordConfirmedApiResult.Ambiguous
        }
    }
}

private fun IOException.isBeforeRequestWasSent(): Boolean =
    this is UnknownHostException || this is ConnectException || this is NoRouteToHostException

private const val CONFIRMED_ERROR_RESPONSE_BYTES = 64L * 1024L
