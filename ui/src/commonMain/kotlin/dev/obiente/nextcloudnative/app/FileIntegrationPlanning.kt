package dev.obiente.nextcloudnative.app

/**
 * Token-free app presence discovered from Nextcloud's provisioning metadata.
 *
 * Presence alone never proves that an integration is configured for the current user.
 */
data class NextcloudIntegrationInventory(
    val installedAppIds: Set<String>,
) {
    fun contains(appId: String): Boolean = appId in installedAppIds
}

enum class IntegratedFileKind {
    Office,
    Whiteboard,
    Drawio,
}

sealed interface IntegratedFileHandoff {
    /** A handoff created by Nextcloud core only after a visible user action. */
    data class CoreDirectEditing(
        val request: NextcloudDocumentEditSessionRequest,
    ) : IntegratedFileHandoff

    /** A fixed, same-origin app page opened only after a visible user action. */
    data class SameOriginPage(
        val request: NextcloudApiRequest,
    ) : IntegratedFileHandoff
}

sealed interface IntegratedFileHandoffPlan {
    data object NotApplicable : IntegratedFileHandoffPlan

    data class Ready(
        val kind: IntegratedFileKind,
        val actionLabel: String,
        val handoff: IntegratedFileHandoff,
    ) : IntegratedFileHandoffPlan

    data class Blocked(
        val kind: IntegratedFileKind,
        val reason: IntegratedFileBlockedReason,
    ) : IntegratedFileHandoffPlan
}

enum class IntegratedFileBlockedReason {
    MissingApp,
    MissingFileId,
    MissingVersion,
    MissingPermissions,
    ReadOnly,
    OriginalAccessRestricted,
    MimeNotRegistered,
    DirectEditingUnavailable,
    FileIdHandoffUnavailable,
    UnsafePath,
}

/**
 * Builds an explicit file handoff without opening a document, board, URL, or token-producing route.
 *
 * Extensions may classify a file for UX, but only exact registered MIME types can enable Draw.io
 * or Whiteboard. Office retains its stricter secure-editor policy.
 */
fun planIntegratedFileHandoff(
    file: NextcloudFile,
    inventory: NextcloudIntegrationInventory,
    capabilities: NextcloudDocumentEditingCapabilities,
): IntegratedFileHandoffPlan {
    val descriptor = describeDocument(file)
    return when (descriptor.kind) {
        DocumentKind.WordProcessing,
        DocumentKind.Spreadsheet,
        DocumentKind.Presentation,
        DocumentKind.Drawing,
        -> planOfficeHandoff(file, capabilities)

        DocumentKind.Whiteboard -> planWhiteboardHandoff(file, inventory, capabilities)
        DocumentKind.Diagram -> planDrawioHandoff(file, inventory)
        else -> IntegratedFileHandoffPlan.NotApplicable
    }
}

private fun planOfficeHandoff(
    file: NextcloudFile,
    capabilities: NextcloudDocumentEditingCapabilities,
): IntegratedFileHandoffPlan = when (val plan = planOfficeEditSession(file, capabilities)) {
    is OfficeEditSessionPlan.Ready -> IntegratedFileHandoffPlan.Ready(
        kind = IntegratedFileKind.Office,
        actionLabel = "Edit in Office",
        handoff = IntegratedFileHandoff.CoreDirectEditing(plan.request),
    )

    is OfficeEditSessionPlan.Blocked -> IntegratedFileHandoffPlan.Blocked(
        kind = IntegratedFileKind.Office,
        reason = plan.reason.toIntegratedFileBlockedReason(),
    )
}

private fun planWhiteboardHandoff(
    file: NextcloudFile,
    inventory: NextcloudIntegrationInventory,
    capabilities: NextcloudDocumentEditingCapabilities,
): IntegratedFileHandoffPlan {
    val blocked = validateWritableIntegratedFile(file, WHITEBOARD_MIME_TYPE)
    if (blocked != null) return IntegratedFileHandoffPlan.Blocked(IntegratedFileKind.Whiteboard, blocked)
    if (!inventory.contains(WHITEBOARD_APP_ID)) {
        return IntegratedFileHandoffPlan.Blocked(
            IntegratedFileKind.Whiteboard,
            IntegratedFileBlockedReason.MissingApp,
        )
    }
    val editor = capabilities.editors[WHITEBOARD_DIRECT_EDITOR_ID]
        ?: return IntegratedFileHandoffPlan.Blocked(
            IntegratedFileKind.Whiteboard,
            IntegratedFileBlockedReason.DirectEditingUnavailable,
        )
    if (!capabilities.supportsFileId) {
        return IntegratedFileHandoffPlan.Blocked(
            IntegratedFileKind.Whiteboard,
            IntegratedFileBlockedReason.FileIdHandoffUnavailable,
        )
    }
    if (WHITEBOARD_MIME_TYPE !in editor.mimeTypes &&
        WHITEBOARD_MIME_TYPE !in editor.optionalMimeTypes
    ) {
        return IntegratedFileHandoffPlan.Blocked(
            IntegratedFileKind.Whiteboard,
            IntegratedFileBlockedReason.MimeNotRegistered,
        )
    }
    return IntegratedFileHandoffPlan.Ready(
        kind = IntegratedFileKind.Whiteboard,
        actionLabel = "Open Whiteboard",
        handoff = IntegratedFileHandoff.CoreDirectEditing(
            NextcloudDocumentEditSessionRequest(
                path = file.parentDocumentPath(),
                fileId = requireNotNull(file.fileId),
                editorId = editor.id,
                expectedEtag = requireNotNull(file.etag),
            ),
        ),
    )
}

private fun planDrawioHandoff(
    file: NextcloudFile,
    inventory: NextcloudIntegrationInventory,
): IntegratedFileHandoffPlan {
    val blocked = validateWritableIntegratedFile(file, DRAWIO_MIME_TYPE)
    if (blocked != null) return IntegratedFileHandoffPlan.Blocked(IntegratedFileKind.Drawio, blocked)
    if (!inventory.contains(DRAWIO_APP_ID)) {
        return IntegratedFileHandoffPlan.Blocked(
            IntegratedFileKind.Drawio,
            IntegratedFileBlockedReason.MissingApp,
        )
    }
    return IntegratedFileHandoffPlan.Ready(
        kind = IntegratedFileKind.Drawio,
        actionLabel = "Open in Draw.io",
        handoff = IntegratedFileHandoff.SameOriginPage(
            NextcloudApiRequest(
                method = NextcloudApiMethod.GET,
                relativePath = DRAWIO_EDITOR_PATH,
                queryParameters = mapOf("fileId" to requireNotNull(file.fileId).toString()),
            ),
        ),
    )
}

private fun validateWritableIntegratedFile(
    file: NextcloudFile,
    requiredMimeType: String,
): IntegratedFileBlockedReason? {
    if (!file.originalAccessAllowed) return IntegratedFileBlockedReason.OriginalAccessRestricted
    if (file.mimeType.normalizedMimeType() != requiredMimeType) {
        return IntegratedFileBlockedReason.MimeNotRegistered
    }
    if (file.fileId == null || file.fileId < 0) return IntegratedFileBlockedReason.MissingFileId
    if (file.etag.isNullOrBlank()) return IntegratedFileBlockedReason.MissingVersion
    if (file.permissions.isNullOrBlank()) return IntegratedFileBlockedReason.MissingPermissions
    if ('W' !in file.permissions) return IntegratedFileBlockedReason.ReadOnly
    if (!file.path.isSafeDocumentRelativePath()) return IntegratedFileBlockedReason.UnsafePath
    return null
}

private fun String?.normalizedMimeType(): String? =
    this?.substringBefore(';')?.trim()?.lowercase()?.takeIf(String::isNotEmpty)

private fun NextcloudFile.parentDocumentPath(): String =
    path.substringBeforeLast('/', missingDelimiterValue = "/").ifBlank { "/" }

private fun OfficeEditBlockedReason.toIntegratedFileBlockedReason(): IntegratedFileBlockedReason = when (this) {
    OfficeEditBlockedReason.OriginalAccessRestricted -> IntegratedFileBlockedReason.OriginalAccessRestricted
    OfficeEditBlockedReason.MissingFileId -> IntegratedFileBlockedReason.MissingFileId
    OfficeEditBlockedReason.MissingVersion -> IntegratedFileBlockedReason.MissingVersion
    OfficeEditBlockedReason.MissingPermissions -> IntegratedFileBlockedReason.MissingPermissions
    OfficeEditBlockedReason.ReadOnly -> IntegratedFileBlockedReason.ReadOnly
    OfficeEditBlockedReason.FileIdHandoffUnavailable -> IntegratedFileBlockedReason.FileIdHandoffUnavailable
    OfficeEditBlockedReason.UnsafePath -> IntegratedFileBlockedReason.UnsafePath
    OfficeEditBlockedReason.UnsupportedMimeType,
    OfficeEditBlockedReason.UnsupportedDocument,
    -> IntegratedFileBlockedReason.MimeNotRegistered
    OfficeEditBlockedReason.DirectEditingUnavailable,
    OfficeEditBlockedReason.InsecureEditor,
    OfficeEditBlockedReason.InsecureAccountOrigin,
    OfficeEditBlockedReason.Directory,
    -> IntegratedFileBlockedReason.DirectEditingUnavailable
}

sealed interface IntegrationAppExperience {
    data class Ready(val capabilityAppId: String) : IntegrationAppExperience
    data class FileHandoffOnly(val appId: String) : IntegrationAppExperience
    data class AuthenticationRequired(val settingsAppId: String) : IntegrationAppExperience
    data class Unsupported(val appId: String) : IntegrationAppExperience
}

/**
 * Stable app-level classification. GitHub installation cannot prove user authentication because
 * its safe capability/config surface does not expose that state, so native repository access stays
 * gated behind an explicit authentication-required state.
 */
fun planIntegrationAppExperience(
    app: NextcloudAppEntry,
    inventory: NextcloudIntegrationInventory,
    capabilities: NextcloudDocumentEditingCapabilities,
): IntegrationAppExperience = when (app.id) {
    OFFICE_NAVIGATION_APP_ID -> {
        val editor = capabilities.editors.values
            .filter { it.secure && it.id.isSafeDocumentCapabilityId() }
            .sortedWith(compareBy(NextcloudDocumentEditorCapability::displayName, NextcloudDocumentEditorCapability::id))
            .firstOrNull { inventory.contains(it.id) || inventory.contains(OFFICE_NAVIGATION_APP_ID) }
        if (editor != null) {
            IntegrationAppExperience.Ready(editor.id)
        } else {
            IntegrationAppExperience.Unsupported(app.id)
        }
    }

    WHITEBOARD_APP_ID,
    DRAWIO_APP_ID,
    -> if (inventory.contains(app.id)) {
        IntegrationAppExperience.FileHandoffOnly(app.id)
    } else {
        IntegrationAppExperience.Unsupported(app.id)
    }

    GITHUB_INTEGRATION_APP_ID,
    GITHUB_NAVIGATION_ALIAS,
    -> if (inventory.contains(GITHUB_INTEGRATION_APP_ID)) {
        IntegrationAppExperience.AuthenticationRequired(GITHUB_INTEGRATION_APP_ID)
    } else {
        IntegrationAppExperience.Unsupported(app.id)
    }

    else -> IntegrationAppExperience.Unsupported(app.id)
}

internal const val DRAWIO_APP_ID = "drawio"
internal const val DRAWIO_MIME_TYPE = "application/x-drawio"
internal const val DRAWIO_EDITOR_PATH = "/index.php/apps/drawio/edit"
internal const val WHITEBOARD_APP_ID = "whiteboard"
internal const val WHITEBOARD_DIRECT_EDITOR_ID = "whiteboard"
internal const val WHITEBOARD_MIME_TYPE = "application/vnd.excalidraw+json"
internal const val GITHUB_INTEGRATION_APP_ID = "integration_github"
internal const val GITHUB_NAVIGATION_ALIAS = "github"
internal const val OFFICE_NAVIGATION_APP_ID = "office"
internal const val OFFICE_CAPABILITY_APP_ID = OFFICE_DIRECT_EDITOR_ID
