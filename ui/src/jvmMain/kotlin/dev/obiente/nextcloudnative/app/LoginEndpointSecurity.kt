package dev.obiente.nextcloudnative.app

import java.net.URI

data class LoginEndpointRelationships(
    val loginOriginMatchesEntered: Boolean,
    val pollOriginMatchesEntered: Boolean,
    val pollFallbackEndpoint: String?,
)

fun validateLoginEndpointRelationships(
    enteredServerUrl: String,
    loginUrl: String,
    pollEndpoint: String,
): LoginEndpointRelationships {
    val entered = requireLoginUri(enteredServerUrl, "server", allowPlainHttp = true)
    val allowPlainHttp = entered.scheme.equals("http", ignoreCase = true)
    val login = requireLoginUri(loginUrl, "browser login", allowPlainHttp)
    val poll = requireLoginUri(pollEndpoint, "login polling", allowPlainHttp)
    require(!login.scheme.equals("http", ignoreCase = true) || entered.hasSameOrigin(login)) {
        "The browser login address may use plain HTTP only on the entered server origin."
    }
    require(!poll.scheme.equals("http", ignoreCase = true) || entered.hasSameOrigin(poll)) {
        "The login polling address may use plain HTTP only on the entered server origin."
    }
    val canonicalPollEndpoint = entered.canonicalPollEndpoint()
    return LoginEndpointRelationships(
        loginOriginMatchesEntered = entered.hasSameOrigin(login),
        pollOriginMatchesEntered = entered.hasSameOrigin(poll),
        pollFallbackEndpoint = canonicalPollEndpoint.takeUnless {
            poll.hasSameRequestTarget(URI(canonicalPollEndpoint))
        },
    )
}

fun loginResultOriginMatchesEntered(enteredServerUrl: String, resultServerUrl: String): Boolean =
    requireLoginUri(enteredServerUrl, "server", allowPlainHttp = true).let { entered ->
        val result = requireLoginUri(
            resultServerUrl,
            "authenticated server",
            allowPlainHttp = entered.scheme.equals("http", ignoreCase = true),
        )
        val matches = entered.hasSameOrigin(result)
        require(!result.scheme.equals("http", ignoreCase = true) || matches) {
            "The authenticated server may use plain HTTP only on the entered server origin."
        }
        matches
    }

private fun requireLoginUri(value: String, label: String, allowPlainHttp: Boolean): URI {
    val uri = runCatching { URI(value) }
        .getOrElse { throw IllegalArgumentException("The $label address is invalid.") }
    val validScheme = uri.scheme.equals("https", ignoreCase = true) ||
        (allowPlainHttp && uri.scheme.equals("http", ignoreCase = true))
    require(validScheme && !uri.host.isNullOrBlank()) {
        "The $label address must use a valid HTTPS hostname."
    }
    require(uri.rawUserInfo == null && uri.rawFragment == null) {
        "The $label address contains unsupported URL components."
    }
    if (label == "server") {
        require(uri.rawQuery == null) { "The server address contains unsupported URL components." }
    }
    return uri
}

private fun URI.hasSameOrigin(other: URI): Boolean =
    scheme.equals(other.scheme, ignoreCase = true) &&
        host.equals(other.host, ignoreCase = true) &&
        effectivePort() == other.effectivePort()

private fun URI.hasSameRequestTarget(other: URI): Boolean =
    hasSameOrigin(other) &&
        rawPath.orEmpty() == other.rawPath.orEmpty() &&
        rawQuery == other.rawQuery

private fun URI.effectivePort(): Int = when {
    port >= 0 -> port
    scheme.equals("http", ignoreCase = true) -> 80
    else -> 443
}

private fun URI.canonicalPollEndpoint(): String {
    val basePath = rawPath.orEmpty().trimEnd('/')
    return URI("$scheme://$rawAuthority$basePath/index.php/login/v2/poll").toASCIIString()
}
