package dev.obiente.nextcloudnative.app

sealed interface FileVersionRestoreHttpResult {
    data object Restored : FileVersionRestoreHttpResult

    data class Rejected(
        val status: Int,
        val message: String,
    ) : FileVersionRestoreHttpResult
}

fun classifyFileVersionRestoreHttpResponse(status: Int): FileVersionRestoreHttpResult = when (status) {
    in 200..299 -> FileVersionRestoreHttpResult.Restored
    403 -> FileVersionRestoreHttpResult.Rejected(
        status,
        "You do not have permission to restore this file version.",
    )
    404 -> FileVersionRestoreHttpResult.Rejected(
        status,
        "This historical version no longer exists.",
    )
    409 -> FileVersionRestoreHttpResult.Rejected(
        status,
        "The server could not restore this version to the current file.",
    )
    else -> FileVersionRestoreHttpResult.Rejected(
        status,
        "Restoring the file version failed (HTTP $status).",
    )
}
