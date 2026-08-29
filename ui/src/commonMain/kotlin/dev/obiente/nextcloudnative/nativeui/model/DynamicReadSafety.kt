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

/**
 * Parameterless detail GETs become automatic network work when an app opens. Require affirmative
 * read evidence instead of assuming that an unfamiliar GET is harmless. Collection intent is an
 * explicit read-producing contract; detail routes need verified provenance or an
 * explicit read-producing concept such as status, settings, or overview.
 */
internal fun DynamicAction.hasPositiveRootReadEvidence(): Boolean {
    if (looksLikeStateChangingGet()) return false
    if (intent == ActionIntent.list) return true
    if (provenance.any { it.kind == ProvenanceKind.successfulReadObservation }) return true
    val concepts = sequenceOf(resourceId, binding.path, id, label)
        .flatMap { it.semanticConceptTokens().asSequence() }
        .toSet()
    return concepts.any(POSITIVE_ROOT_READ_CONCEPTS::contains)
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
    "scan",
    "start",
    "toggle",
    "trigger",
)

private val POSITIVE_ROOT_READ_CONCEPTS = setOf(
    "capabilities",
    "configuration",
    "dashboard",
    "detail",
    "details",
    "get",
    "health",
    "info",
    "overview",
    "preferences",
    "profile",
    "read",
    "settings",
    "show",
    "statistics",
    "stats",
    "status",
    "summary",
    "version",
    "view",
)
