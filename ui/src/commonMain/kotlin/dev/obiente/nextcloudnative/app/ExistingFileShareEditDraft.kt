package dev.obiente.nextcloudnative.app

internal data class ExistingFileShareEditDraft(
    val allowEditing: Boolean,
    val allowResharing: Boolean,
    val newPassword: String,
    val removePassword: Boolean,
    val expirationDate: String,
    val note: String,
)

internal fun existingFileShareEditDraft(share: NextcloudFileShare): ExistingFileShareEditDraft {
    val permissions = fileSharePermissionsFromMask(share.permissions)
    return ExistingFileShareEditDraft(
        allowEditing = permissions.update,
        allowResharing = permissions.reshare,
        newPassword = "",
        removePassword = false,
        expirationDate = share.expiration.orEmpty(),
        note = share.note.orEmpty(),
    )
}

internal fun planExistingFileShareUpdate(
    share: NextcloudFileShare,
    draft: ExistingFileShareEditDraft,
    sourceIsDirectory: Boolean,
    target: FileShareTarget?,
    expirationPolicy: FileShareFeaturePolicy,
    dateSource: FileShareDateSource = DeviceLocalFileShareDateSource,
): UpdateFileShareRequest? {
    val originalDraft = existingFileShareEditDraft(share)
    val permissions = if (
        draft.allowEditing == originalDraft.allowEditing &&
        draft.allowResharing == originalDraft.allowResharing
    ) {
        null
    } else {
        FileSharePermissions(
            read = true,
            update = draft.allowEditing,
            create = draft.allowEditing && sourceIsDirectory,
            delete = draft.allowEditing && sourceIsDirectory,
            reshare = draft.allowResharing,
        )
    }
    val password = when {
        draft.removePassword -> ""
        draft.newPassword.isNotEmpty() -> draft.newPassword
        else -> null
    }
    val normalizedExpiration = draft.expirationDate.trim()
    val expirationDate = when {
        normalizedExpiration == share.expiration.orEmpty() -> null
        normalizedExpiration.isEmpty() && expirationPolicy.enforced ->
            error("This server requires an expiration date.")
        normalizedExpiration.isEmpty() -> ""
        else -> requireNonPastFileShareDate(normalizedExpiration, dateSource)
    }
    val note = draft.note.takeIf { it != share.note.orEmpty() }
    if (permissions == null && password == null && expirationDate == null && note == null) return null
    return UpdateFileShareRequest(
        shareId = share.id,
        target = target,
        permissions = permissions,
        password = password,
        expirationDate = expirationDate,
        note = note,
    )
}
