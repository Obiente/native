package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.NextcloudFileListing
import dev.obiente.nextcloudnative.app.NextcloudFileListingHttpException
import dev.obiente.nextcloudnative.app.NextcloudFileListingSource
import dev.obiente.nextcloudnative.app.NextcloudSession
import java.io.IOException

internal data class AndroidDavFileListingResponse(val status: Int, val files: List<NextcloudFile>)

/** The caller may reuse a lease only while its enclosing account operation still owns it. */
internal suspend fun loadAndroidAccountFileListing(
    session: NextcloudSession,
    resolveSession: suspend () -> NextcloudSession?,
    cache: AndroidFileReadCache,
    path: String,
    accountLeaseHeld: Boolean = false,
    guard: AndroidAccountOperationGuard = ANDROID_ACCOUNT_OPERATION_GUARD,
    request: suspend () -> AndroidDavFileListingResponse,
): NextcloudFileListing {
    val read: suspend () -> NextcloudFileListing = {
        readAndroidAccountFileListing(cache, NextcloudDocumentIds.accountKey(session), path, request)
    }
    return if (accountLeaseHeld) read() else withRetainedAndroidAccountFileRead(session, resolveSession, guard, read = read)
}

private suspend fun readAndroidAccountFileListing(
    cache: AndroidFileReadCache,
    accountId: String,
    path: String,
    request: suspend () -> AndroidDavFileListingResponse,
): NextcloudFileListing = try {
    val response = request()
    if (response.status == 207) {
        val files = response.files.drop(1)
            .sortedWith(compareByDescending<NextcloudFile> { it.isDirectory }.thenBy { it.name.lowercase() })
        runCatching { cache.storeListing(accountId, path, files) }
        NextcloudFileListing(files, NextcloudFileListingSource.Network)
    } else {
        val cached = if (response.status >= 500) cache.cachedListing(accountId, path) else null
        cached?.let { NextcloudFileListing(it.files, NextcloudFileListingSource.Cache) }
            ?: throw NextcloudFileListingHttpException(response.status)
    }
} catch (failure: IOException) {
    cache.cachedListing(accountId, path)?.files
        ?.let { NextcloudFileListing(it, NextcloudFileListingSource.Cache) }
        ?: throw failure
}

internal fun requireAndroidDocumentDirectory(
    reference: NextcloudDocumentReference,
    findDocument: (String) -> NextcloudFile,
) {
    if (reference.isRoot) return
    require(findDocument(reference.path).isDirectory) { "The selected parent is not a folder." }
}

internal fun NextcloudFileListing.filesForProviderRecovery(requireNetwork: Boolean): List<NextcloudFile> {
    check(!requireNetwork || source == NextcloudFileListingSource.Network) {
        "Folder recovery requires a confirmed server listing."
    }
    return files
}

internal suspend fun loadAndroidProviderChildren(
    recoveryAuthorized: Boolean,
    read: suspend () -> List<NextcloudFile>,
    cached: () -> List<NextcloudFile>,
    storedDirectory: () -> Boolean,
): List<NextcloudFile> = try {
    read()
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    if (recoveryAuthorized) throw failure
    val children = cached()
    if (children.isNotEmpty() || storedDirectory()) children
    else throw java.io.FileNotFoundException("Could not load this Nextcloud folder.").also { it.initCause(failure) }
}
