package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

internal data class AndroidProviderDirectoryListing(val files: List<NextcloudFile>, val directoryEtag: String?)

/** IO loads metadata; the exact provider caller records its thread-confined recovery evidence. */
internal fun loadAndroidProviderDirectoryListing(
    documentId: String,
    load: suspend () -> AndroidProviderDirectoryListing,
): List<NextcloudFile> {
    val listing = runBlocking(Dispatchers.IO) { load() }
    listing.directoryEtag?.let { recordAndroidProviderRecoveryDirectoryGeneration(documentId, it) }
    return listing.files
}
