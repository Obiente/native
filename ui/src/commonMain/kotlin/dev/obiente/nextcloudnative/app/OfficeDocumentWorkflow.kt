package dev.obiente.nextcloudnative.app

/**
 * Read-only editor metadata advertised by Nextcloud's core direct-editing API.
 *
 * This model deliberately contains no direct-editing or WOPI token.
 */
data class NextcloudDocumentEditorCapability(
    val id: String,
    val displayName: String,
    val mimeTypes: Set<String>,
    val optionalMimeTypes: Set<String>,
    val secure: Boolean,
)

data class NextcloudDocumentCreatorCapability(
    val id: String,
    val editorId: String,
    val displayName: String,
    val extension: String,
    val templates: Boolean,
    val mimeType: String?,
)

/** Token-free metadata suitable for a native template picker. */
data class NextcloudDocumentTemplate(
    val id: String,
    val displayName: String,
    val extension: String,
    val creatorId: String,
    val mimeType: String?,
)

data class NextcloudDocumentEditingCapabilities(
    val editors: Map<String, NextcloudDocumentEditorCapability>,
    val creators: Map<String, NextcloudDocumentCreatorCapability>,
    val supportsFileId: Boolean,
) {
    companion object {
        val Unavailable = NextcloudDocumentEditingCapabilities(emptyMap(), emptyMap(), supportsFileId = false)
    }
}

/**
 * Token-free parameters for an explicit Office edit action.
 *
 * The ETag is retained as the version the user reviewed. Nextcloud's direct-editing endpoint does
 * not currently accept an If-Match validator, so callers should refresh the file metadata before
 * allowing this plan to remain actionable for a long time.
 */
data class NextcloudDocumentEditSessionRequest(
    /** Parent directory used with [fileId] so a rename cannot silently retarget the handoff. */
    val path: String,
    val fileId: Long,
    val editorId: String,
    val expectedEtag: String,
)

/**
 * A short-lived URL returned by Nextcloud after an explicit open request.
 *
 * Platform implementations validate this as same-origin and on the core direct-editing route.
 * [toString] stays redacted so routine diagnostics cannot accidentally log its token.
 */
class NextcloudDocumentEditSession(
    val sameOriginUrl: String,
) {
    override fun toString(): String = "NextcloudDocumentEditSession(url=<redacted>)"
}

sealed interface OfficeEditSessionPlan {
    data class Ready(val request: NextcloudDocumentEditSessionRequest) : OfficeEditSessionPlan

    data class Blocked(val reason: OfficeEditBlockedReason) : OfficeEditSessionPlan
}

enum class OfficeEditBlockedReason {
    Directory,
    OriginalAccessRestricted,
    MissingFileId,
    MissingVersion,
    MissingPermissions,
    ReadOnly,
    UnsupportedDocument,
    DirectEditingUnavailable,
    FileIdHandoffUnavailable,
    InsecureEditor,
    InsecureAccountOrigin,
    UnsupportedMimeType,
    UnsafePath,
}

/**
 * Produces an edit request only from fresh DAV metadata and an advertised secure Office editor.
 *
 * No endpoint is called and no token is created while planning.
 */
fun planOfficeEditSession(
    file: NextcloudFile,
    capabilities: NextcloudDocumentEditingCapabilities,
    accountOriginSecure: Boolean = true,
): OfficeEditSessionPlan {
    if (!accountOriginSecure) {
        return OfficeEditSessionPlan.Blocked(OfficeEditBlockedReason.InsecureAccountOrigin)
    }
    if (file.isDirectory) return OfficeEditSessionPlan.Blocked(OfficeEditBlockedReason.Directory)
    if (!file.originalAccessAllowed) {
        return OfficeEditSessionPlan.Blocked(OfficeEditBlockedReason.OriginalAccessRestricted)
    }
    val fileId = file.fileId?.takeIf { it >= 0L }
        ?: return OfficeEditSessionPlan.Blocked(OfficeEditBlockedReason.MissingFileId)
    val etag = file.etag?.takeIf(String::isNotBlank)
        ?: return OfficeEditSessionPlan.Blocked(OfficeEditBlockedReason.MissingVersion)
    val permissions = file.permissions?.takeIf(String::isNotBlank)
        ?: return OfficeEditSessionPlan.Blocked(OfficeEditBlockedReason.MissingPermissions)
    if ('W' !in permissions) return OfficeEditSessionPlan.Blocked(OfficeEditBlockedReason.ReadOnly)

    val descriptor = describeDocument(file)
    if (!descriptor.officeEditable) {
        return OfficeEditSessionPlan.Blocked(OfficeEditBlockedReason.UnsupportedDocument)
    }
    val editor = capabilities.editors[OFFICE_DIRECT_EDITOR_ID]
        ?: return OfficeEditSessionPlan.Blocked(OfficeEditBlockedReason.DirectEditingUnavailable)
    if (!editor.secure) return OfficeEditSessionPlan.Blocked(OfficeEditBlockedReason.InsecureEditor)
    if (!capabilities.supportsFileId) {
        return OfficeEditSessionPlan.Blocked(OfficeEditBlockedReason.FileIdHandoffUnavailable)
    }

    val mimeType = descriptor.mimeType
        ?.takeUnless { it == "application/octet-stream" }
        ?: return OfficeEditSessionPlan.Blocked(OfficeEditBlockedReason.UnsupportedMimeType)
    if (mimeType !in editor.mimeTypes && mimeType !in editor.optionalMimeTypes) {
        return OfficeEditSessionPlan.Blocked(OfficeEditBlockedReason.UnsupportedMimeType)
    }
    if (!file.path.isSafeDocumentRelativePath()) {
        return OfficeEditSessionPlan.Blocked(OfficeEditBlockedReason.UnsafePath)
    }

    val parentPath = file.path.substringBeforeLast('/', missingDelimiterValue = "/").ifBlank { "/" }
    return OfficeEditSessionPlan.Ready(
        NextcloudDocumentEditSessionRequest(
            path = parentPath,
            fileId = fileId,
            editorId = editor.id,
            expectedEtag = etag,
        ),
    )
}

internal fun String.isSafeDocumentRelativePath(): Boolean =
    isNotBlank() &&
        !startsWith('/') &&
        none(Char::isISOControl) &&
        split('/').all { segment -> segment.isNotBlank() && segment != "." && segment != ".." }

internal fun String.isSafeDocumentLookupPath(): Boolean =
    this == "/" || isSafeDocumentRelativePath()

internal fun String.isSafeDocumentCapabilityId(): Boolean =
    isNotBlank() && length <= 128 && all { it.isLetterOrDigit() || it == '-' || it == '_' }

internal fun OfficeEditBlockedReason.userMessage(): String = when (this) {
    OfficeEditBlockedReason.Directory -> "Folders cannot be opened in Office."
    OfficeEditBlockedReason.OriginalAccessRestricted -> "This shared file allows preview only."
    OfficeEditBlockedReason.MissingFileId -> "Refresh the folder to load the document ID."
    OfficeEditBlockedReason.MissingVersion -> "Refresh the folder before editing this document."
    OfficeEditBlockedReason.MissingPermissions -> "Refresh the folder to verify edit permission."
    OfficeEditBlockedReason.ReadOnly -> "This document is read-only."
    OfficeEditBlockedReason.UnsupportedDocument -> "This file type is not an Office document."
    OfficeEditBlockedReason.DirectEditingUnavailable -> "Nextcloud Office is unavailable for this account."
    OfficeEditBlockedReason.FileIdHandoffUnavailable ->
        "This server cannot bind an Office handoff to the document ID."
    OfficeEditBlockedReason.InsecureEditor -> "The advertised Office handoff is not marked secure."
    OfficeEditBlockedReason.InsecureAccountOrigin -> "Office editing requires an HTTPS account connection."
    OfficeEditBlockedReason.UnsupportedMimeType -> "Nextcloud Office did not advertise this exact file type."
    OfficeEditBlockedReason.UnsafePath -> "The document path is unsafe."
}

internal const val OFFICE_DIRECT_EDITOR_ID = "richdocuments"

internal data class CachedDocumentEditingCapabilities(
    val capabilities: NextcloudDocumentEditingCapabilities,
    val etag: String?,
)

/** Small process-local capability cache; it never stores edit or WOPI tokens. */
internal class NextcloudDocumentEditingCapabilitiesCache {
    private val entries = mutableMapOf<String, CachedDocumentEditingCapabilities>()

    fun get(session: NextcloudSession): CachedDocumentEditingCapabilities? = entries[session.cacheKey()]

    fun store(
        session: NextcloudSession,
        capabilities: NextcloudDocumentEditingCapabilities,
        etag: String?,
    ) {
        entries[session.cacheKey()] = CachedDocumentEditingCapabilities(
            capabilities = capabilities,
            etag = etag?.takeIf(String::isNotBlank),
        )
    }

    private fun NextcloudSession.cacheKey(): String =
        serverUrl.trim().trimEnd('/').lowercase() + '\u0000' + loginName
}

internal val sharedDocumentEditingCapabilitiesCache = NextcloudDocumentEditingCapabilitiesCache()
