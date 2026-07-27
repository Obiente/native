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
): UpdateFileShareRequest? {
    val originalDraft = existingFileShareEditDraft(share)
    val permissions = if (
        draft.allowEditing == originalDraft.allowEditing &&
        draft.allowResharing == originalDraft.allowResharing
    ) {
        null
    } else {
        var mask = share.permissions ?: FileSharePermissions(read = true).mask
        if (draft.allowEditing != originalDraft.allowEditing) {
            val editingMask = FILE_SHARE_UPDATE_PERMISSION or
                if (sourceIsDirectory) {
                    FILE_SHARE_CREATE_PERMISSION or FILE_SHARE_DELETE_PERMISSION
                } else {
                    0
                }
            mask = if (draft.allowEditing) mask or editingMask else mask and editingMask.inv()
        }
        if (draft.allowResharing != originalDraft.allowResharing) {
            mask = if (draft.allowResharing) {
                mask or FILE_SHARE_RESHARE_PERMISSION
            } else {
                mask and FILE_SHARE_RESHARE_PERMISSION.inv()
            }
        }
        fileSharePermissionsFromMask(mask)
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
        else -> requireValidFileShareDate(normalizedExpiration)
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

private const val FILE_SHARE_UPDATE_PERMISSION = 2
private const val FILE_SHARE_CREATE_PERMISSION = 4
private const val FILE_SHARE_DELETE_PERMISSION = 8
private const val FILE_SHARE_RESHARE_PERMISSION = 16
