package dev.obiente.nextcloudnative.app

internal data class DesktopRemoteSyncDocument(
    val entry: RemoteSyncEntry,
    val isDirectory: Boolean,
    val lastModifiedEpochMillis: Long? = null,
    val physicalPath: String = entry.relativePath,
)

internal data class DesktopRemoteScanDirectory(
    val logicalRelativePath: String,
    val physicalPath: String,
)
