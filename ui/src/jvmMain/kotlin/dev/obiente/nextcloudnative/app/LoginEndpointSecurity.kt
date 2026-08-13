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
    val entered = requireSecureLoginUri(enteredServerUrl, "server")
    val login = requireSecureLoginUri(loginUrl, "browser login")
    val poll = requireSecureLoginUri(pollEndpoint, "login polling")
    return LoginEndpointRelationships(
        loginOriginMatchesEntered = entered.hasSameOrigin(login),
        pollOriginMatchesEntered = entered.hasSameOrigin(poll),
        pollFallbackEndpoint = entered.canonicalPollEndpoint().takeUnless { entered.hasSameOrigin(poll) },
    )
}

fun loginResultOriginMatchesEntered(enteredServerUrl: String, resultServerUrl: String): Boolean =
    requireSecureLoginUri(enteredServerUrl, "server")
        .hasSameOrigin(requireSecureLoginUri(resultServerUrl, "authenticated server"))

private fun requireSecureLoginUri(value: String, label: String): URI {
    val uri = runCatching { URI(value) }
        .getOrElse { throw IllegalArgumentException("The $label address is invalid.") }
    require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
        "The $label address must use a valid https:// hostname."
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

private fun URI.effectivePort(): Int = if (port >= 0) port else 443

private fun URI.canonicalPollEndpoint(): String {
    val basePath = rawPath.orEmpty().trimEnd('/')
    return URI(
        scheme,
        null,
        host,
        port,
        "$basePath/index.php/login/v2/poll",
        null,
        null,
    ).toASCIIString()
}
