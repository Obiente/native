package dev.obiente.nextcloudnative

import dev.obiente.nextcloudnative.app.NextcloudFile
import dev.obiente.nextcloudnative.app.RemoteSyncEntry
import dev.obiente.nextcloudnative.app.SyncEntryKind
import dev.obiente.nextcloudnative.app.normalizeSyncSha256

internal data class AndroidRemoteSyncDocument(
    val entry: RemoteSyncEntry,
    val isDirectory: Boolean,
)

internal data class AndroidRemoteChildNameSnapshot(
    val names: Set<String>,
    val complete: Boolean,
)

internal data class AndroidRemoteScanDirectory(
    val logicalRelativePath: String,
    val physicalPath: String,
)

internal data class AndroidRemoteScanChild(
    val file: NextcloudFile,
    val logicalRelativePath: String,
)

internal fun NextcloudFile.toRemoteDocument(relativePath: String): AndroidRemoteSyncDocument {
    val safeEtag = etag?.takeIf(String::isNotBlank)
        ?: error("The server item has no usable revision.")
    return AndroidRemoteSyncDocument(
        RemoteSyncEntry(
            relativePath = relativePath,
            kind = if (isDirectory) SyncEntryKind.Directory else SyncEntryKind.File,
            etag = safeEtag,
            size = if (isDirectory) null else size,
            modifiedEpochMillis = lastModified.androidFileSyncModifiedEpochMillis(),
            contentHash = if (isDirectory) null else checksums.firstNotNullOfOrNull(::normalizeSyncSha256),
        ),
        isDirectory,
    )
}
