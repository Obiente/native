package dev.obiente.nextcloudnative.nativeui.model

/**
 * Session, token, connection-test, and keep-alive reads exist so a web client can operate. They
 * are not content a person browses, so they never become navigation tabs. The check matches whole
 * words in the resource ID and label, never substrings ("latest" and "shopping" stay visible), and
 * never the app ID.
 */
internal fun DynamicNavigationDestination.isAuxiliaryProtocolDestination(): Boolean =
    (resourceId + " " + label)
        .replace(AUXILIARY_CAMEL_CASE_BOUNDARY, "$1 $2")
        .lowercase()
        .split(AUXILIARY_WORD_SEPARATOR)
        .any { word -> word in AUXILIARY_PROTOCOL_WORDS }

/** Drops auxiliary protocol destinations unless doing so would leave no destination at all. */
internal fun List<DynamicNavigationDestination>.withoutAuxiliaryProtocolDestinations(): List<DynamicNavigationDestination> =
    filterNot(DynamicNavigationDestination::isAuxiliaryProtocolDestination).ifEmpty { this }

private val AUXILIARY_CAMEL_CASE_BOUNDARY = Regex("([a-z0-9])([A-Z])")
private val AUXILIARY_WORD_SEPARATOR = Regex("[^a-z0-9]+")
private val AUXILIARY_PROTOCOL_WORDS = setOf(
    "session", "sessions", "token", "tokens", "test", "tests", "ping", "heartbeat", "keepalive",
    "csrf", "nonce", "preflight",
)
