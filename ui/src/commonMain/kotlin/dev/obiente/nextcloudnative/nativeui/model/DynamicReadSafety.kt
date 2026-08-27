package dev.obiente.nextcloudnative.nativeui.model

/**
 * A few third-party route catalogs describe command-like endpoints with GET even though invoking
 * them changes server state. HTTP method alone must not turn those routes into automatic app
 * destinations. This check is deliberately limited to unambiguous command verbs; export,
 * download, preview, and other read-producing operations remain eligible.
 */
internal fun DynamicAction.looksLikeStateChangingGet(): Boolean {
    val terminalPathSegment = binding.path.substringBefore('?').trimEnd('/').substringAfterLast('/')
    return terminalPathSegment.semanticConceptTokens().any(STATE_CHANGING_GET_CONCEPTS::contains) ||
        id.semanticConceptTokens().lastOrNull() in STATE_CHANGING_GET_CONCEPTS
}

private val STATE_CHANGING_GET_CONCEPTS = setOf(
    "clear",
    "clearcache",
    "delete",
    "deleteall",
    "destroy",
    "disable",
    "enable",
    "execute",
    "flush",
    "rebuild",
    "regenerate",
    "reindex",
    "remove",
    "reset",
    "restart",
    "run",
    "toggle",
    "trigger",
)
