package dev.obiente.nextcloudnative.app

import java.net.URI
import java.nio.charset.StandardCharsets
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class NextcloudAuthenticatedRequestPolicy(
    session: NextcloudSession,
    private val userAgent: String,
) {
    private val account = requireAccountUrl(session.serverUrl)
    private val authorization = Credentials.basic(
        session.loginName,
        session.appPassword,
        StandardCharsets.UTF_8,
    )

    fun requestBuilder(targetUrl: String): Request.Builder {
        val target = requireSafeTarget(targetUrl)
        return Request.Builder()
            .url(target)
            .header("Authorization", authorization)
            .header("User-Agent", userAgent)
            .tag(NextcloudAuthenticatedRequestPolicy::class.java, this)
    }

    internal fun redirectDecision(
        request: Request,
        status: Int,
        location: String?,
    ): NextcloudAuthenticatedRedirectDecision {
        requireSafeTarget(request.url)
        if (status !in REDIRECT_STATUS_RANGE || status == 304) {
            return NextcloudAuthenticatedRedirectDecision.DeliverResponse
        }
        val safeBodylessReadRedirect =
            status in BODYLESS_READ_REDIRECT_STATUSES &&
                request.method in BODYLESS_READ_METHODS &&
                request.body == null
        if (status !in METHOD_PRESERVING_REDIRECT_STATUSES && !safeBodylessReadRedirect) {
            return NextcloudAuthenticatedRedirectDecision.Reject(
                NextcloudAuthenticatedRedirectRejection.MethodMayChange,
            )
        }
        if (location.isNullOrBlank()) {
            return NextcloudAuthenticatedRedirectDecision.Reject(
                NextcloudAuthenticatedRedirectRejection.MissingLocation,
            )
        }
        if (!hasSafeRawPath(location)) {
            return NextcloudAuthenticatedRedirectDecision.Reject(
                NextcloudAuthenticatedRedirectRejection.InvalidLocation,
            )
        }
        val resolved = request.url.resolve(location)
            ?: return NextcloudAuthenticatedRedirectDecision.Reject(
                NextcloudAuthenticatedRedirectRejection.InvalidLocation,
            )
        val target = try {
            requireSafeTarget(resolved)
        } catch (_: IllegalArgumentException) {
            return NextcloudAuthenticatedRedirectDecision.Reject(
                NextcloudAuthenticatedRedirectRejection.UnsafeTarget,
            )
        }
        if (request.body?.let { it.isOneShot() || it.isDuplex() } == true) {
            return NextcloudAuthenticatedRedirectDecision.Reject(
                NextcloudAuthenticatedRedirectRejection.NonReplayableBody,
            )
        }
        return NextcloudAuthenticatedRedirectDecision.Follow(
            request.newBuilder().url(target).build(),
        )
    }

    internal fun requireSafeTarget(target: HttpUrl): HttpUrl {
        require(hasSafeEncodedPath(target.encodedPath)) {
            "An authenticated request target contains path traversal."
        }
        require(target.encodedUsername.isEmpty() && target.encodedPassword.isEmpty()) {
            "An authenticated request target cannot contain user information."
        }
        require(target.fragment == null) { "An authenticated request target cannot contain a fragment." }
        require(
            target.scheme == account.scheme &&
                target.host == account.host &&
                target.port == account.port,
        ) { "An authenticated request target must remain on the account origin." }
        require(hasAccountBasePath(target)) {
            "An authenticated request target must remain inside the account base path."
        }
        return target
    }

    private fun requireSafeTarget(value: String): HttpUrl {
        require(hasSafeRawPath(value)) { "An authenticated request target contains path traversal." }
        val target = value.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("The authenticated request target is invalid.")
        return requireSafeTarget(target)
    }

    private fun hasAccountBasePath(target: HttpUrl): Boolean {
        val accountPath = account.encodedPath.trimEnd('/').takeUnless { it == "/" }.orEmpty()
        return accountPath.isEmpty() ||
            target.encodedPath == accountPath ||
            target.encodedPath.startsWith("$accountPath/")
    }

    private companion object {
        val REDIRECT_STATUS_RANGE = 300..399
        val BODYLESS_READ_REDIRECT_STATUSES = setOf(301, 302)
        val BODYLESS_READ_METHODS = setOf("GET", "HEAD")
        val METHOD_PRESERVING_REDIRECT_STATUSES = setOf(307, 308)
    }
}

internal sealed interface NextcloudAuthenticatedRedirectDecision {
    data object DeliverResponse : NextcloudAuthenticatedRedirectDecision
    data class Follow(val request: Request) : NextcloudAuthenticatedRedirectDecision
    data class Reject(
        val reason: NextcloudAuthenticatedRedirectRejection,
    ) : NextcloudAuthenticatedRedirectDecision
}

internal enum class NextcloudAuthenticatedRedirectRejection(val diagnosticValue: String) {
    InvalidLocation("invalid_location"),
    MethodMayChange("method_may_change"),
    MissingLocation("missing_location"),
    NonReplayableBody("non_replayable_body"),
    TooManyHops("too_many_hops"),
    UnsafeTarget("unsafe_target"),
}

class NextcloudAuthenticatedRedirectException internal constructor(
    internal val reason: NextcloudAuthenticatedRedirectRejection,
    val status: Int,
) : IllegalStateException("The authenticated request redirect was rejected ($reason, HTTP $status).") {
    val diagnosticReason: String = reason.diagnosticValue
}

fun <T> executeNextcloudAuthenticatedRequest(
    client: OkHttpClient,
    initialRequest: Request,
    executeCall: (Call) -> Response = { call -> call.execute() },
    consume: (Response) -> T,
): T {
    require(!client.followRedirects && !client.followSslRedirects) {
        "Authenticated request execution requires automatic redirects to be disabled."
    }
    val policy = requireNotNull(initialRequest.tag(NextcloudAuthenticatedRequestPolicy::class.java)) {
        "The authenticated request has not passed the account request policy."
    }
    policy.requireSafeTarget(initialRequest.url)
    var request = initialRequest
    repeat(MAX_AUTHENTICATED_REDIRECT_HOPS + 1) { hop ->
        executeCall(client.newCall(request)).use { response ->
            when (val decision = policy.redirectDecision(request, response.code, response.header("Location"))) {
                NextcloudAuthenticatedRedirectDecision.DeliverResponse -> return consume(response)
                is NextcloudAuthenticatedRedirectDecision.Reject -> {
                    throw NextcloudAuthenticatedRedirectException(decision.reason, response.code)
                }
                is NextcloudAuthenticatedRedirectDecision.Follow -> {
                    if (hop == MAX_AUTHENTICATED_REDIRECT_HOPS) {
                        throw NextcloudAuthenticatedRedirectException(
                            NextcloudAuthenticatedRedirectRejection.TooManyHops,
                            response.code,
                        )
                    }
                    request = decision.request
                }
            }
        }
    }
    error("The authenticated redirect limit was not enforced.")
}

private fun requireAccountUrl(value: String): HttpUrl {
    require(hasSafeRawPath(value)) { "The account server address contains path traversal." }
    val account = value.toHttpUrlOrNull()
        ?: throw IllegalArgumentException("The account server address is invalid.")
    require(account.scheme == "https" || account.scheme == "http") {
        "The account server address must use HTTP or HTTPS."
    }
    require(account.encodedUsername.isEmpty() && account.encodedPassword.isEmpty()) {
        "The account server address cannot contain user information."
    }
    require(account.encodedQuery == null && account.fragment == null) {
        "The account server address contains unsupported URL components."
    }
    return account
}

private fun hasSafeRawPath(value: String): Boolean {
    val rawPath = try {
        URI(value).rawPath.orEmpty()
    } catch (_: Exception) {
        return false
    }
    return rawPath.split('/').none(::containsEncodedTraversal)
}

private fun hasSafeEncodedPath(rawPath: String): Boolean =
    rawPath.split('/').none(::containsEncodedTraversal)

private fun containsEncodedTraversal(rawSegment: String): Boolean {
    var candidate = rawSegment
    repeat(MAX_PERCENT_DECODING_PASSES) {
        if (candidate.split('/', '\\').any { segment -> segment == "." || segment == ".." }) return true
        val decoded = decodePercentAscii(candidate) ?: return true
        if (decoded == candidate) return false
        candidate = decoded
    }
    return candidate.split('/', '\\').any { segment -> segment == "." || segment == ".." }
}

private fun decodePercentAscii(value: String): String? {
    val decoded = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val current = value[index]
        if (current != '%') {
            decoded.append(current)
            index += 1
            continue
        }
        if (index + 2 >= value.length) return null
        val high = value[index + 1].digitToIntOrNull(16) ?: return null
        val low = value[index + 2].digitToIntOrNull(16) ?: return null
        decoded.append(((high shl 4) or low).toChar())
        index += 3
    }
    return decoded.toString()
}

private const val MAX_AUTHENTICATED_REDIRECT_HOPS = 3
private const val MAX_PERCENT_DECODING_PASSES = 3
