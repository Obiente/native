package dev.obiente.nextcloudnative.app

/** A refreshed source must still be the exact writable version the user chose to edit. */
internal suspend fun beginRevalidatedOfficeEdit(
    request: NextcloudDocumentEditSessionRequest,
    capabilities: NextcloudDocumentEditingCapabilities,
    resolveFile: suspend (Long) -> NextcloudFile?,
    beginSession: suspend (NextcloudDocumentEditSessionRequest) -> NextcloudDocumentEditSession,
): NextcloudDocumentEditSession {
    val current = resolveFile(request.fileId) ?: throw OfficeEditSourceChangedException()
    val refreshed = planOfficeEditSession(current, capabilities, editorId = request.editorId)
    if (refreshed !is OfficeEditSessionPlan.Ready || refreshed.request != request) {
        throw OfficeEditSourceChangedException()
    }
    return beginSession(refreshed.request)
}

internal class OfficeEditSourceChangedException : IllegalStateException(
    "The document changed or is no longer writable. Close this preview and refresh the folder before editing.",
)

/** Shared by the response boundary and embedded navigator; tokens never need URL decoding. */
fun isValidOfficeDirectEditingToken(token: String): Boolean =
    token.length in 1..1_024 && token.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
