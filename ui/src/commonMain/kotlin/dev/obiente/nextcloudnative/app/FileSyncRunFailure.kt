package dev.obiente.nextcloudnative.app

import kotlinx.serialization.Serializable

/** A bounded, non-secret failure of the pair check, separate from per-file transfer failures. */
@Serializable
data class FileSyncRunFailure(val kind: FileSyncRunFailureKind, val occurredAtEpochMillis: Long) {
    init { require(occurredAtEpochMillis >= 0L) }
}

@Serializable
enum class FileSyncRunFailureKind {
    FolderAccess, SignInRequired, PermissionDenied, RemoteFolderMissing,
    Network, Throttled, ServerUnavailable, RecoveryRequired, Unknown,
}

fun FileSyncRunFailure.recoveryMessage(): String = when (kind) {
    FileSyncRunFailureKind.FolderAccess -> "Folder access was lost. Use Add sync to select the same device folder and restore access."
    FileSyncRunFailureKind.SignInRequired -> "Sign in to your account again, then check this sync."
    FileSyncRunFailureKind.PermissionDenied -> "Nextcloud denied access. Check your permissions for this folder."
    FileSyncRunFailureKind.RemoteFolderMissing -> "The Nextcloud folder could not be found. Check that it still exists."
    FileSyncRunFailureKind.Network -> "The connection was interrupted. Check your connection, then check this sync again."
    FileSyncRunFailureKind.Throttled -> "Nextcloud is limiting requests. Wait before checking this sync again."
    FileSyncRunFailureKind.ServerUnavailable -> "Nextcloud could not complete the check. Try again when the server is available."
    FileSyncRunFailureKind.RecoveryRequired -> "An unfinished upload needs recovery. Check this sync again before changing its settings."
    FileSyncRunFailureKind.Unknown -> "This sync could not finish its check. Open diagnostics for more information."
}

internal fun FileSyncPairSummary.needsSyncAttention(): Boolean =
    runFailure != null || conflictCount > 0 || failedCount > 0
